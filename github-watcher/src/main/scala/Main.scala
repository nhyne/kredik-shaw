import zio._
import zio.blocking.Blocking
import zio.console.Console
import zhttp.service.EventLoopGroup
import zhttp.service.server.ServerChannelFactory
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio.magic._
import zio.system.System
import com.coralogix.zio.k8s.client.config.asynchttpclient.k8sDefault
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import GithubApi.{zioHttpClient, Http4sClient, GithubClient}

object Main extends App {

  private val program = WebhookApi.server.start

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    program.inject(
      Console.live,
      Blocking.live,
      k8sDefault,
      Namespaces.live,
      ServerChannelFactory.auto,
      System.live,
      EventLoopGroup.auto(5)
    ).exitCode
  }
}
