val zioVersion = "1.0.7"
val zioConfigVersion = "1.0.0"
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
        //"org.http4s" %% "http4s-async-http-client" % "0.23.0-RC1",
        "org.http4s" %% "http4s-blaze-client" % "0.23.0-RC1",
        "com.softwaremill.sttp.client3" %% "slf4j-backend" % "3.3.13",
        "com.softwaremill.sttp.client3" %% "core" % "3.3.13",
        "com.softwaremill.sttp.client3" %% "zio-json" % "3.3.13",
        "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % "3.3.13",
        "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % "3.3.13",
        "io.d11" %% "zhttp" % "1.0.0.0-RC17"

      )
  )
lazy val `twilio-messenger` = (project in file("./twilio-messenger"))
  .settings(
    version := "0.0.1-SNAPSHOT",
    organization := "dev.nhyne",
    Compile / mainClass := Some("Messenger"),
    scalaVersion := scala_2_13,
    libraryDependencies ++=
      Seq(
        "dev.zio" %% "zio" % zioVersion,
        "dev.zio" %% "zio-logging" % "0.5.8",
        //        "com.typesafe.scala-logging" %% "scala-logging" % "3.9.3",
        //        "dev.zio" %% "zio-logging-slf4j-bridge" % "0.5.8",
        "dev.zio" %% "zio-json" % "0.1.4",
        "dev.zio" %% "zio-metrics" % "1.0.8",
        "dev.zio" %% "zio-metrics-prometheus" % "1.0.8",
        //        "dev.zio" %% "zio-telemetry" % "0.8.0",
        "io.github.kitlangton" %% "zio-magic" % "0.2.2",
        "io.d11" %% "zhttp" % "1.0.0.0-RC15",
        "com.github.pureconfig" %% "pureconfig" % "0.14.1",
        "com.softwaremill.sttp.client3" %% "core" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % sttpClientVersion
      )
  )
