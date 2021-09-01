val zioVersion = "1.0.9"
val zioConfigVersion = "1.0.6"
val zioLoggingVersion = "0.5.11"
val scala_2_13 = "2.13.1"
val sttpClientVersion = "3.3.13"
val tapirVersion = "0.17.19"
val circeVersion = "0.13.0"
val zioMetricsVersion = "1.0.12"

lazy val root = (project in file("."))
  .settings(
    publish / skip := true,
    name := "root",
    Compile / run := (`github-watcher` / Compile / run).evaluated
  )
  .aggregate(`github-watcher`)

lazy val `github-watcher` = (project in file("./github-watcher"))
  .settings(
    version := "0.0.1-SNAPSHOT",
    organization := "dev.nhyne",
    Compile / mainClass := Some("Main"),
    reStart / mainClass := Some("Main"),
    scalaVersion := scala_2_13,
    scalacOptions ++= Seq(
      "-Xfatal-warnings",
      "-deprecation"
    ),
    libraryDependencies ++=
      Seq(
        "dev.zio" %% "zio" % zioVersion,
        "dev.zio" %% "zio-interop-cats" % "3.1.1.0",
        "dev.zio" %% "zio-process" % "0.5.0",
        "com.coralogix" %% "zio-k8s-client" % "1.3.3",
        "io.github.kitlangton" %% "zio-magic" % "0.3.5",
        "dev.zio" %% "zio-logging-slf4j" % zioLoggingVersion,
        "dev.zio" %% "zio-logging" % zioLoggingVersion,
        "dev.zio" %% "zio-nio" % "1.0.0-RC11",
        "dev.zio" %% "zio-config" % zioConfigVersion,
        "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
        "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
        "dev.zio" %% "zio-config-yaml" % zioConfigVersion,
        "dev.zio" %% "zio-metrics-prometheus" % zioMetricsVersion,
        // Still not sure why the async client will not work with k8s. Keep getting a type error
        "com.softwaremill.sttp.client3" %% "slf4j-backend" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "core" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "zio-json" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % sttpClientVersion,
        "io.d11" %% "zhttp" % "1.0.0.0-RC17",
        "dev.zio" %% "zio-test" % zioVersion % Test,
        "dev.zio" %% "zio-test-sbt" % zioVersion % Test
      ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
