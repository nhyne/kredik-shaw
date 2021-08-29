import zio._
import zio.blocking.Blocking
import zio.console.{Console, putStrLn}
import zhttp.service.EventLoopGroup
import zhttp.service.server.ServerChannelFactory
import zio.magic._
import zio.system.System
import com.coralogix.zio.k8s.client.config.asynchttpclient.k8sDefault
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import dependencies.DependencyConverter
import zio.logging._
import zio.clock.Clock
import zio.config.{ZConfig, getConfig}
import template.Template

object Main extends App {

  private val program = for {
    conf <- getConfig[ApplicationConfig]
    _ <- putStrLn(s"${conf.port}")
    _ <- WebhookApi.server.start
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    // TODO: Should be coming out of resources dir
    val config = ZConfig.fromPropertiesFile(
      "watcher.conf",
      ApplicationConfig.configDescriptor
    )

    val logger = Logging.console(
      LogLevel.Info,
      LogFormat.ColoredLogFormat()
    ) >>> Logging.withRootLoggerName("github-watcher")

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
        DependencyConverter.live
      )
      .exitCode
  }
}
