import zio._
import zio.console.putStrLn
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

  val program = for {
    repos <- GithubApi.listRepos()
    _ <- ZIO.foreach_(repos)(repo => putStrLn(repo.name))
    clt <- ZIO.service[zioHttpClient.Service]
    zioTopics <- clt.getTopics("zio", "zio")
    _ <- ZIO.foreach_(zioTopics.names)(topic => putStrLn(topic))
    mergeOutput <- WebhookApi.gitCloneAndMerge("zio", "zio", "series/2.x", "master").mapError(e => {
      println(e.getCause.toString)
      "abc"
    })
    _ <- mergeOutput match {
      case ExitCode(0) => putStrLn("merged successfully")
      case ExitCode(code) => putStrLn("failed merge, got code: $code")
    }
    /*
      check the merge code, if we have a success code then we continue to reading the config file -- stored where?
      if we have a failure then we want to comment on the PR saying so -- or do we send a response saying "merge failed"
     */

    _ <- WebhookApi.createPRNamespace(23, "zio")
    _ <- WebhookApi.server.start
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    val httpLayer = Http4sClient.live
    val githubClientLayer = GithubClient.live(None)
    val asyncHttpClientZioBackend = ZLayer.fromManaged(AsyncHttpClientZioBackend.managed())

    program.inject(
      httpLayer,
      githubClientLayer,
      Console.live,
      Blocking.live,
      asyncHttpClientZioBackend >>> zioHttpClient.live,
      k8sDefault,
      Namespaces.live,
      ServerChannelFactory.auto,
      System.live,
      EventLoopGroup.auto(5)
    ).exitCode
  }
}
