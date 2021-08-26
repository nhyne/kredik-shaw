val zioVersion = "1.0.7"
val zioConfigVersion = "1.0.6"
val zioLoggingVersion = "0.5.11"
val scala_2_13 = "2.13.6"
val sttpClientVersion = "3.3.13"
val tapirVersion = "0.17.19"
val circeVersion = "0.13.0"

lazy val root = (project in file("."))
  .settings(
    publish / skip := true,
    name := "root",
    Compile / run := (`github-watcher` / Compile / run).evaluated
  )
  .aggregate(`twilio-messenger`, `github-watcher`)

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
        "dev.zio" %% "zio" % "1.0.9",
        "dev.zio" %% "zio-interop-cats" % "3.1.1.0",
        "dev.zio" %% "zio-process" % "0.5.0",
        "com.coralogix" %% "zio-k8s-client" % "1.3.3",
        "com.47deg" %% "github4s" % "0.29.0",
        "io.github.kitlangton" %% "zio-magic" % "0.3.5",
        "dev.zio" %% "zio-logging-slf4j" % zioLoggingVersion,
        "dev.zio" %% "zio-logging" % zioLoggingVersion,
        "dev.zio" %% "zio-nio" % "1.0.0-RC11",
        "dev.zio" %% "zio-config" % zioConfigVersion,
        "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
        "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
        //"org.http4s" %% "http4s-async-http-client" % "0.23.0-RC1",
        "org.http4s" %% "http4s-blaze-client" % "0.23.0-RC1",
        "com.softwaremill.sttp.client3" %% "slf4j-backend" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "core" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "zio-json" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % sttpClientVersion,
        "io.d11" %% "zhttp" % "1.0.0.0-RC17"
      )
  )
lazy val `twilio-messenger` = (project in file("./twilio-messenger"))
  .settings(
    version := "0.0.1-SNAPSHOT",
    organization := "dev.nhyne",
    Compile / mainClass := Some("Main"),
    scalaVersion := scala_2_13,
  )
