package nhyne

import com.coralogix.zio.k8s.client.apps.v1.deployments.Deployments
import zio._
import zhttp.service.EventLoopGroup
import zhttp.service.server.ServerChannelFactory
import zio.magic._
import com.coralogix.zio.k8s.client.config.asynchttpclient.k8sDefault
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import nhyne.dependencies.{DependencyConverter, DependencyWalker}
import nhyne.git.{Authentication, GitCli, GithubApi}
import nhyne.kubernetes.Kubernetes
import nhyne.prometheus.Metrics
import zio.logging._
import zio.config._
import nhyne.config.ApplicationConfig
import nhyne.git.Authentication.GitAuthenticationError
import zio.config.yaml.YamlConfigSource
import nhyne.template.Template
import zio.metrics.prometheus.Registry
import zio.metrics.prometheus.exporters.Exporters
import zio.nio.core.file.{Path => ZFPath}
import zio.metrics.prometheus.helpers.{
  getCurrentRegistry,
  http,
  initializeDefaultExports
}

object Main extends App {

  private val program = for {
    conf <- getConfig[ApplicationConfig]
    registry <- getCurrentRegistry()
    _ <- initializeDefaultExports(registry)
    _ <- http(registry, conf.prometheusPort)
      .tapError(err => log.error(err.toString))
      .forkDaemon
    server <- WebhookApi.server()
    _ <- server.start
  } yield ()

  private def confMerger(confFile: Option[String]) =
    for {
      // TODO: The from file should be okay to fail since we're pulling config from other places
      yamlFile <- ZIO.fromEither(
        YamlConfigSource.fromYamlFile(
          ZFPath(confFile.getOrElse("watcher.yaml")).toFile
        )
      )
      env <- ConfigSource.fromSystemEnv
      sysProp <- ConfigSource.fromSystemProps
      source =
        ApplicationConfig.appConfigDescriptor.from(yamlFile <> sysProp <> env)
      config <- ZIO.fromEither(read(source))
    } yield config

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    val logger = Logging.console(
      LogLevel.Info,
      LogFormat.ColoredLogFormat()
    ) >>> Logging.withRootLoggerName("watcher")

    program
      .inject(
        ZEnv.live,
        k8sDefault,
        Namespaces.live,
        Deployments.live,
        ServerChannelFactory.auto,
        EventLoopGroup.auto(5),
        logger,
        confMerger(args.headOption).toLayer,
        Template.live,
        DependencyConverter.live,
        Registry.live,
        Exporters.live,
        Metrics.live,
        GitCli.live,
        Kubernetes.live,
        DependencyWalker.live,
        GithubApi.live,
        Authentication.live
      )
      .catchSome {
        case GitAuthenticationError(message) =>
          log.error(message) *> ZIO.fail(ExitCode.failure)
      }
      .inject(logger, ZEnv.live)
      .exitCode
  }
}
