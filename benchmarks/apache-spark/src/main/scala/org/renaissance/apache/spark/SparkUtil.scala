package org.renaissance.apache.spark

import org.apache.log4j.Level
import org.apache.log4j.LogManager
import org.apache.log4j.Logger
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.renaissance.Benchmark.Name
import org.renaissance.BenchmarkContext

import java.nio.file.Files
import java.nio.file.Path
import scala.reflect.ClassTag

/**
 * A common trait for all Spark benchmarks. Provides shared Spark
 * setup code and other convenience methods.
 *
 * The common setup code requires that all Spark benchmarks define
 * `spark_executor_count` and `spark_threads_per_executor`
 * parameters, which determine the level of parallelism.
 */
trait SparkUtil {

  private val portAllocationMaxRetries: Int = 16

  /*
   * Hadoop binaries for Windows.
   *
   * Downloaded from https://github.com/kontext-tech/winutils.
   *
   * Make sure the included binaries match the version of Hadoop
   * coming with Spark. For Spark version 3.2.0, the corresponding
   * Hadoop version is 3.3.0.
   *
   * When updating the binaries, make sure to update the file sizes
   * in the map below -- they are used for basic sanity checks.
   */
  private val hadoopExtrasForWindows = Map(
    "winutils.exe" -> 112640,
    "hadoop.dll" -> 87040
  )

  // Windows libraries that should be System.load()-ed
  private val hadoopPreloadedLibsForWindows = Seq(
    "hadoop.dll"
  )

  /** Configures log levels of Spark components to mask (harmless) warnings. */
  private val sparkLogLevels: Seq[(String, Level)] = Seq(
    // Top-level defaults.
    "org.apache.spark" -> Level.WARN,
    "org.apache.hadoop" -> Level.WARN,
    "org.sparkproject.jetty" -> Level.WARN,
    "breeze.optimize" -> Level.WARN,
    // Masks the following warning:
    //   "Your hostname, <hostname> resolves to a loopback address: 127.0.0.1; "
    //   "using <ip-address> instead (on interface <interface>)"
    //   "Set SPARK_LOCAL_IP if you need to bind to another address"
    "org.apache.spark.util.Utils" -> Level.ERROR,
    // Masks the following warning:
    //   "Unable to load native-hadoop library for your platform... using builtin-java classes where applicable"
    "org.apache.hadoop.util.NativeCodeLoader" -> Level.ERROR,
    // Masks the following warning:
    //   "Note that spark.local.dir will be overridden by the value set by the cluster manager "
    //   "(via SPARK_LOCAL_DIRS in mesos/standalone/kubernetes and LOCAL_DIRS in YARN)."
    "org.apache.spark.SparkConf" -> Level.ERROR,
    // Masks the following warnings:
    //   "Failed to load implementation from: dev.ludovic.netlib.blas.JNIBLAS"
    //   "Failed to load implementation from: dev.ludovic.netlib.blas.ForeignLinkerBLAS"
    //   "Failed to load implementation from: dev.ludovic.netlib.lapack.JNILAPACK"
    "dev.ludovic.netlib" -> Level.ERROR,
    // Masks the following warning:
    //   "URL.setURLStreamHandlerFactory failed to set FsUrlStreamHandlerFactory"
    "org.apache.spark.sql.internal.SharedState" -> Level.ERROR
  )

  protected val dumpResultsBeforeTearDown = false

  protected var sparkSession: SparkSession = _
  protected def sparkContext: SparkContext = sparkSession.sparkContext

  def setUpSparkContext(bc: BenchmarkContext, useCheckpointDir: Boolean = false): Unit = {
    setUpLoggers(sparkLogLevels)

    val scratchDir = bc.scratchDirectory()
    setUpHadoop(scratchDir.resolve("hadoop"))

    //
    // We bind Spark explicitly to localhost to avoid all sorts of trouble:
    // https://github.com/renaissance-benchmarks/renaissance/issues/66
    //
    // However, setting spark.driver.bindAddress to "127.0.0.1" does not
    // seem to work on Spark 2.4.7, whereas setting spark.driver.host to
    // "localhost" or "127.0.0.1" does, so does setting the SPARK_LOCAL_IP
    // environment variable (but we cannot do it from here).
    //

    val benchmarkName = getClass.getDeclaredAnnotation(classOf[Name]).value
    val threadLimit = bc.parameter("spark_thread_limit").toPositiveInteger
    val cpuCount = Runtime.getRuntime.availableProcessors()
    val threadCount = Math.min(threadLimit, cpuCount)

    sparkSession = SparkSession
      .builder()
      .appName(benchmarkName)
      .master(s"local[$threadCount]")
      .config("spark.driver.host", "localhost")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.local.dir", scratchDir.toString)
      .config("spark.port.maxRetries", portAllocationMaxRetries.toString)
      .config("spark.sql.warehouse.dir", scratchDir.resolve("warehouse").toString)
      .getOrCreate()

    if (useCheckpointDir) {
      sparkContext.setCheckpointDir(scratchDir.resolve("checkpoints").toString)
    }

    println(
      s"NOTE: '$benchmarkName' benchmark uses Spark local executor with $threadCount (out of $cpuCount) threads."
    )
  }

  def createRddFromCsv[T: ClassTag](
    lines: RDD[String],
    hasHeader: Boolean,
    delimiter: String,
    mapper: Array[String] => T
  ) = {
    val linesWithoutHeader = if (hasHeader) dropFirstLine(lines) else lines
    linesWithoutHeader.map(_.split(delimiter)).map(mapper).filter(_ != null)
  }

  private def dropFirstLine(lines: RDD[String]): RDD[String] = {
    val first = lines.first()
    lines.filter { line => line != first }
  }

  def tearDownSparkContext(): Unit = {
    sparkSession.close()
  }

  // Used to find sources of log messages.
  private def printCurrentLoggers() = {
    import scala.jdk.CollectionConverters._

    println(
      LogManager.getCurrentLoggers.asScala
        .map { case l: Logger => l.getName }
        .toSeq
        .sorted
        .mkString("\n")
    )
  }

  /**
   * Even though Hadoop is a dependency for Spark benchmarks, the
   * artifacts do not include binaries (winutils.exe and hadoop.dll)
   * needed for running Hadoop on Windows.
   *
   * The benchmark includes the binaries as a resource and extracts
   * them when running on the Windows platform. For this reason, the
   * version of the binaries should match the version of Hadoop that
   * comes with the currently used version of Spark, and may need to
   * be updated when updating Spark.
   *
   * See [[hadoopExtrasForWindows]] for more details.
   */
  private def setUpHadoop(hadoopHomeDir: Path): Unit = {
    if (sys.props.get("os.name").toString.contains("Windows")) {
      val hadoopHomeDirAbs = hadoopHomeDir.toAbsolutePath
      val hadoopBinDir = Files.createDirectories(hadoopHomeDirAbs.resolve("bin"))

      for ((filename, expectedSizeBytes) <- hadoopExtrasForWindows) {
        ResourceUtil.writeResourceToFileCheckSize(
          "/" + filename,
          hadoopBinDir.resolve(filename),
          expectedSizeBytes
        )
      }

      for (filename <- hadoopPreloadedLibsForWindows) {
        System.load(hadoopBinDir.resolve(filename).toString)
      }
      System.setProperty("hadoop.home.dir", hadoopHomeDirAbs.toString)
    }
  }

  def ensureCached[T](rdd: RDD[T]): RDD[T] = {
    assume(
      rdd.getStorageLevel == StorageLevel.NONE,
      "Storage level should be NONE before calling ensureCached()"
    )
    rdd.persist(StorageLevel.MEMORY_ONLY)
  }

  def ensureCached[T](ds: Dataset[T]): Dataset[T] = {
    assume(
      ds.storageLevel == StorageLevel.NONE,
      "Storage level should be NONE before calling ensureCached()"
    )
    ds.persist(StorageLevel.MEMORY_ONLY)
  }

  private def setUpLoggers(loggerLevels: Seq[(String, Level)]) = {
    loggerLevels.foreach {
      case (name: String, level: Level) => Logger.getLogger(name).setLevel(level)
    }
  }

}
