import cats.data.EitherT
import cats.implicits.catsSyntaxEitherId
import zio._
import zio.console.putStrLn
import zio.interop.catz._
import zio.interop.catz.implicits._
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import github4s.{GHResponse, Github}
import zio.console.Console
import github4s.domain.Repository
import org.http4s.FormDataDecoder.formEntityDecoder
import org.http4s.Method.GET
import org.http4s.{Headers, MediaType, Uri}
import zio.magic._
import org.http4s.client.dsl.io._
import org.http4s.headers._
import org.http4s.implicits._
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

object Main extends App {

  sealed trait Resp
  case class Topics(names: Array[String]) extends Resp
  type GithubClientLayer = Has[Github[Task]]

  object Http4sClient {
    val live: ZLayer[Any, Throwable, Has[Client[Task]]] = {
      implicit val runtime: Runtime[ZEnv] = Runtime.default
      val res = BlazeClientBuilder[Task](runtime.platform.executor.asEC).resource.toManagedZIO
      ZLayer.fromManaged(res)
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

  def ghResponseToZIO[A](res: GHResponse[A]) = {
    if (res.statusCode == 200) {
      res.result
    }
  }

  def getRepoTopics(repo: Repository): ZIO[Console with Has[Client[Task]], Throwable, Response[Task]] = {
    getRepoTopics(repo.full_name)
  }
  private def getRepoTopics(repo: String) = {
    for {
      httpClient <- ZIO.service[Client[Task]]
      rawUri = s"https://api.github.com/repos/$repo/topics"
      uri <- ZIO.fromEither(Uri.fromString(rawUri))
      req = Request[Task](
        uri = uri,
        headers = Headers(
          "Accept" -> "application/vnd.github.mercy-preview+json"
        )
      )
      _ <- putStrLn(req.asCurl())
      res <- httpClient.run(req).toManagedZIO.use { res =>
        if (res.status.isSuccess) {
          putStrLn(res.status.toString).as(res)
        }
        else {
          putStrLn(s"Got ${res.status.code} trying to get $repo topics from $rawUri") *> ZIO.fail(new Throwable(res.status.reason))
        }
      }
    } yield res
  }

  def listRepos(): ZIO[GithubClientLayer, Throwable, List[Repository]] = {
    for {
      client <- ZIO.service[Github[Task]]
      repos <- client.repos.listUserRepos("nhyne")
      result <- ZIO.fromEither(repos.result).mapError(e => e.getCause)
    } yield result
  }

  implicit val topicDec: EntityDecoder[Task, Topics] = EntityDecoder.decodeBy(MediaType.text.plain) { (m: Media[Task]) =>
    EitherT {
      m.as[Array[String]].map(s => {
        putStrLn(
          s"""
             |we have something maybe????
             |$s
             |""".stripMargin)
        Topics(names = s).asRight[DecodeFailure]
      })
    }
  }
  implicit val bothDec: EntityDecoder[Task, Resp] = topicDec.widen[Resp]

  val program = for {
    repos <- listRepos()
    _ <- ZIO.foreach(repos)(repo => putStrLn(repo.name))
    res <- getRepoTopics("zio/zio").flatMap(_.as[String])
    _ <- putStrLn(res)


  } yield ()


  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    val httpLayer = Http4sClient.live
    val githubClientLayer = GithubClient.live(None)

    program.inject(httpLayer, githubClientLayer, Console.live).exitCode
  }
}
