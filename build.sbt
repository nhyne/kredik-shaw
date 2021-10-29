val zioVersion        = "1.0.12"
val zioConfigVersion  = "1.0.6"
val zioLoggingVersion = "0.5.11"
val scala_2_13        = "2.13.1"
val sttpClientVersion = "3.3.14"
val tapirVersion      = "0.17.19"
val circeVersion      = "0.13.0"
val zioMetricsVersion = "1.0.12"
val zioK8sVersion     = "1.3.4"

lazy val root = (project in file("."))
  .settings(
    publish / skip := true,
    name := "root",
    Compile / run := (`kredik-shaw` / Compile / run).evaluated
  )
  .aggregate(`kredik-shaw`)

lazy val `kredik-shaw` = (project in file("./github-watcher"))
  .enablePlugins(DockerPlugin, JavaAppPackaging)
  .settings(
    version := "0.0.1-SNAPSHOT",
    organization := "dev.nhyne",
    Compile / mainClass := Some("nhyne.Main"),
    reStart / mainClass := Some("nhyne.Main"),
    scalaVersion := scala_2_13,
    dockerBaseImage := "nhyne/openjdk-kredik:16-0.2",
    Docker / daemonUser := "rashek",
    dockerExposedPorts ++= Seq(8090, 9090),
    scalacOptions ++= Seq(
      "-Xfatal-warnings",
      "-deprecation",
      "-Xlint",
      "-Ywarn-extra-implicit",
      "-Ywarn-unused:patvars,-implicits",
      "-Ywarn-value-discard",
      "-opt-warnings",
      "-feature"
    ),
    libraryDependencies ++=
      Seq(
        "dev.zio"                       %% "zio"                           % zioVersion,
        "dev.zio"                       %% "zio-interop-cats"              % "3.1.1.0",
        "dev.zio"                       %% "zio-process"                   % "0.5.0",
        "com.coralogix"                 %% "zio-k8s-client"                % zioK8sVersion,
        "com.coralogix"                 %% "zio-k8s-client-quicklens"      % zioK8sVersion,
        "io.github.kitlangton"          %% "zio-magic"                     % "0.3.8",
        "dev.zio"                       %% "zio-logging-slf4j"             % zioLoggingVersion,
        "dev.zio"                       %% "zio-logging"                   % zioLoggingVersion,
        "dev.zio"                       %% "zio-nio"                       % "1.0.0-RC11",
        "dev.zio"                       %% "zio-config"                    % zioConfigVersion,
        "dev.zio"                       %% "zio-config-magnolia"           % zioConfigVersion,
        "dev.zio"                       %% "zio-config-typesafe"           % zioConfigVersion,
        "dev.zio"                       %% "zio-config-yaml"               % zioConfigVersion,
        "dev.zio"                       %% "zio-metrics-prometheus"        % zioMetricsVersion,
        "com.softwaremill.sttp.client3" %% "slf4j-backend"                 % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "core"                          % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "zio-json"                      % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "async-http-client-backend-zio" % sttpClientVersion,
        "com.softwaremill.sttp.client3" %% "httpclient-backend-zio"        % sttpClientVersion,
        "io.d11"                        %% "zhttp"                         % "1.0.0.0-RC17",
        "io.github.vigoo"               %% "zio-aws-secretsmanager"        % "3.17.65.1",
        "io.github.vigoo"               %% "zio-aws-netty"                 % "3.17.65.1",
        "io.github.vigoo"               %% "zio-aws-sts"                   % "3.17.65.1",
        "dev.zio"                       %% "zio-test"                      % zioVersion % Test,
        "dev.zio"                       %% "zio-test-sbt"                  % zioVersion % Test
      ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
