import zio._
import zio.console.putStrLn
import zio.interop.catz._
import zio.interop.catz.implicits._
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import github4s.Github
import zio.console.Console
import github4s.domain.Repository
import zhttp.service.EventLoopGroup
import zhttp.service.server.ServerChannelFactory
import sttp.client3._
import sttp.client3.asynchttpclient.zio.AsyncHttpClientZioBackend
import zio.magic._
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.client3.ziojson._
import zio.json._

import java.io.IOException

object Main extends App {

  type GithubClientLayer = Has[Github[Task]]

  object Http4sClient {
    val live: ZLayer[Any, Throwable, Has[Client[Task]]] = {
      implicit val runtime: Runtime[ZEnv] = Runtime.default
      val res = BlazeClientBuilder[Task](runtime.platform.executor.asEC).resource.toManagedZIO
      ZLayer.fromManaged(res)
    }
  }


  object ActionService {
    type ActionService = Has[Service]

    val live: ZLayer[Console, Throwable, ActionService] = ZLayer.succeed(
      new Service {
        override def performAction(action: TopicAction): ZIO[Console, Throwable, Unit] = action match {
          case TopicAction.K8sAction(config) => putStrLn("k8s action")
          case TopicAction.PullRequestEnvironmentAction(config) => putStrLn("pr actoin")
          case TopicAction.ArgoSyncAction(config) => putStrLn("argo action")
        }
      }
    )

    trait Service {
      def performAction(action: TopicAction): ZIO[Console, Throwable, Unit]
    }
    final case class K8sActionConfig(clusterName: String)
    final case class PullRequestEnvironmentConfig(prNumber: Int)
    final case class ArgoConfig(manifestsPath: String)

    sealed trait TopicAction
    case object TopicAction {
      final case class K8sAction(config: K8sActionConfig) extends TopicAction
      final case class PullRequestEnvironmentAction(config: PullRequestEnvironmentConfig) extends TopicAction
      final case class ArgoSyncAction(config: ArgoConfig) extends TopicAction
    }
  }

  object zioHttpClient {
    implicit val myResponseJsonDecoder: JsonDecoder[Topics] = DeriveJsonDecoder.gen[Topics]
    type SBackend = SttpBackend[Task, ZioStreams with capabilities.WebSockets]

    val live: ZLayer[Has[SBackend], Throwable, GithubTopicsService] = ZLayer.succeed(
      new Service {
        override def getTopics(org: String, repo: String): ZIO[Has[SBackend], Throwable, Topics] = for {
          client <- ZIO.service[SBackend]
          request = basicRequest.get(uri"https://api.github.com/repos/$org/$repo/topics")
            .header("Accept", "application/vnd.github.mercy-preview+json")
            .response(asJson[Topics])
          response <- client.send(request)
          topics <- ZIO.fromEither(response.body)
        } yield topics

        override def nothing(something: String): ZIO[Console, IOException, Unit] = putStrLn(something)
      }
    )

    final case class Topics(names: Seq[String])

    type GithubTopicsService = Has[Service]

    trait Service {
      def getTopics(org: String, repo: String): ZIO[Has[SBackend], Throwable, Topics]

      def nothing(something: String): ZIO[Console, IOException, Unit]
    }
  }

  object GithubClient {
    def live(accessToken: Option[String]): ZLayer[Has[Client[Task]], Throwable, Has[Github[Task]]] = {
      (for {
        client <- ZIO.service[Client[Task]]
        github = Github(accessToken = accessToken, client = client)
      } yield github).toLayer
    }
  }

  def listRepos(): ZIO[GithubClientLayer, Throwable, List[Repository]] = {
    for {
      client <- ZIO.service[Github[Task]]
      repos <- client.repos.listUserRepos("nhyne")
      result <- ZIO.fromEither(repos.result).mapError(e => e.getCause)
    } yield result
  }

  val program = for {
    repos <- listRepos()
    _ <- ZIO.foreach_(repos)(repo => putStrLn(repo.name))
    clt <- ZIO.service[zioHttpClient.Service]
    zioTopics <- clt.getTopics("zio", "zio")
    _ <- ZIO.foreach_(zioTopics.names)(topic => putStrLn(topic))
    _ <- WebhookApi.server.start
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    val httpLayer = Http4sClient.live
    val githubClientLayer = GithubClient.live(None)

    program.inject(
      httpLayer,
      githubClientLayer,
      Console.live,
      zioHttpClient.live,
      ZLayer.fromManaged(AsyncHttpClientZioBackend.managed()),
      ServerChannelFactory.auto,
      EventLoopGroup.auto(5)
    ).exitCode
  }
}
