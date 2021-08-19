import zio._
import zio.console.putStrLn
import zio.interop.catz._
import zio.interop.catz.implicits._
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import github4s.Github
import zio.console.Console
import github4s.domain.Repository
import sttp.client3._
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.client3.ziojson._
import zio.json._

import java.io.IOException

object GithubApi {
  type GithubClientLayer = Has[Github[Task]]

  object Http4sClient {
    val live: ZLayer[Any, Throwable, Has[Client[Task]]] = {
      implicit val runtime: Runtime[ZEnv] = Runtime.default
      val res = BlazeClientBuilder[Task](runtime.platform.executor.asEC).resource.toManagedZIO
      ZLayer.fromManaged(res)
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

  def listRepos(): ZIO[GithubClientLayer with Console, Throwable, List[Repository]] = {
    for {
      client <- ZIO.service[Github[Task]]
      repos <- client.repos.listUserRepos("zio")
      result <- ZIO.fromEither(repos.result).mapError(e => e.getCause)
      _ <- ZIO.foreach_(result) { repo => putStrLn(s"${repo.owner.name}") }
    } yield result
  }

}
