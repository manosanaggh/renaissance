lazy val renaissanceCore = RootProject(uri("../../renaissance-core"))

lazy val database = (project in file("."))
  .settings(
    name := "database",
    version := (renaissanceCore / version).value,
    organization := (renaissanceCore / organization).value,
    scalaVersion := "2.13.7",
    libraryDependencies ++= Seq(
      "com.github.jnr" % "jnr-posix" % "3.0.29",
      "org.apache.commons" % "commons-math3" % "3.6.1",
      "org.agrona" % "agrona" % "0.9.7",
      "net.openhft" % "zero-allocation-hashing" % "0.6",
      "org.mapdb" % "mapdb" % "3.0.1",
      "com.h2database" % "h2-mvstore" % "1.4.192",
      "net.openhft" % "chronicle-core" % "2.17.2",
      "net.openhft" % "chronicle-bytes" % "2.17.7" exclude ("net.openhft", "chronicle-core"),
      "net.openhft" % "chronicle-threads" % "2.17.1" exclude ("net.openhft", "chronicle-core"),
      "net.openhft" % "chronicle-map" % "3.17.0" excludeAll (
        ExclusionRule("net.openhft", "chronicle-core"),
        ExclusionRule("net.openhft", "chronicle-bytes"),
        ExclusionRule("net.openhft", "chronicle-threads"),
        ExclusionRule("org.slf4j", "slf4j-api")
      ),
      // Force newer JNA to support more platforms/architectures.
      "net.java.dev.jna" % "jna-platform" % "5.10.0",
      // Add simple binding to silence SLF4J warnings.
      "org.slf4j" % "slf4j-simple" % "1.7.32"
    )
  )
  .dependsOn(
    renaissanceCore % "provided"
  )
