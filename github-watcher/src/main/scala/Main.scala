import zio._
import zhttp.service.EventLoopGroup
import zhttp.service.server.ServerChannelFactory
import zio.magic._
import com.coralogix.zio.k8s.client.config.asynchttpclient.k8sDefault
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import dependencies.{DependencyConverter, DependencyWalker}
import git.Authentication.GitAuthenticationError
import git.{Authentication, GitCli, GithubApi}
import kubernetes.Kubernetes
import prom.Metrics
import zio.logging._
import zio.config.{ZConfig, getConfig}
import zio.config.yaml.{YamlConfig, YamlConfigSource}
import template.Template
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

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    val config = YamlConfig.fromFile(
      ZFPath("src/main/resources/watcher.yaml").toFile,
      ApplicationConfig.appConfigDescriptor
    )

    val logger = Logging.console(
      LogLevel.Info,
      LogFormat.ColoredLogFormat()
    ) >>> Logging.withRootLoggerName("watcher")

    program
      .inject(
        ZEnv.live,
        k8sDefault,
        Namespaces.live,
        ServerChannelFactory.auto,
        EventLoopGroup.auto(5),
        logger,
        config,
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
