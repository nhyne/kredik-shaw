val zioVersion = "1.0.5"
val zioConfigVersion = "1.0.0"
val scala_2_13 = "2.13.4"
val sttpClientVersion = "3.1.9"
lazy val root = (project in file("."))
  .settings(
    skip in publish := true,
    name := "root",
    run in Compile := (run in Compile in `dhall-builder`).evaluated
  )
  .aggregate(`dhall-builder`)

lazy val `dhall-builder` = (project in file("./dhall-builder"))
  .settings(
    version := "0.0.1-ALPHA",
    organization := "dev.nhyne",
    mainClass in Compile := Some("DhallBuilder"),
    scalaVersion := scala_2_13,
    libraryDependencies ++=
      Seq(
        "dev.zio" %% "zio" % zioVersion,
        "dev.zio" %% "zio-logging" % "0.5.8",
        "dev.zio" %% "zio-json" % "0.1.2",
        "dev.zio" %% "zio-metrics" % "1.0.5",
        "com.github.pureconfig" %% "pureconfig" % "0.14.1",
        "com.softwaremill.sttp.client3" %% "core" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % sttpClientVersion
      )
  )
