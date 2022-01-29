val zioVersion        = "1.0.12"
val zioConfigVersion  = "3.0.0-RC1"
val zioLoggingVersion = "2.0.0-RC4"
val scala_3           = "3.1.0"
val sttpClientVersion = "3.4.1"
val zioMetricsVersion = "1.0.13"
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
    version := "0.1.0",
    organization := "dev.nhyne",
    Compile / mainClass := Some("nhyne.Main"),
    reStart / mainClass := Some("nhyne.Main"),
    scalaVersion := scala_3,
    dockerBaseImage := "nhyne/openjdk-kredik:16-0.2",
    Docker / daemonUser := "rashek",
    dockerExposedPorts ++= Seq(8090, 9090),
    scalacOptions ++= Seq(
      "-Xfatal-warnings",
      "-deprecation",
//      "-Xlint",
//      "-Ywarn-extra-implicit",
//      "-Ywarn-unused:patvars,-implicits",
//      "-Ywarn-value-discard",
//      "-opt-warnings",
      "-feature"
    ),
    libraryDependencies ++=
      Seq(
        "dev.zio"                       %% "zio"                           % "2.0.0-RC1",
        "dev.zio"                       %% "zio-interop-cats"              % "3.1.1.0",
        "dev.zio"                       %% "zio-process"                   % "0.5.0",
        "com.coralogix"                 %% "zio-k8s-client"                % zioK8sVersion,
        "com.coralogix"                 %% "zio-k8s-client-quicklens"      % zioK8sVersion,
        "dev.zio"                       %% "zio-logging-slf4j"             % zioLoggingVersion,
        "dev.zio"                       %% "zio-logging"                   % zioLoggingVersion,
        "dev.zio"                       %% "zio-nio"                       % "2.0.0-RC1",
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
    excludeDependencies ++= Seq(
      "org.scala-lang.modules" % "scala-collection-compat_2.13"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
