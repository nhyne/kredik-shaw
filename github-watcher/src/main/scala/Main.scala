import zio._
import zio.console.putStrLn
import zio.interop.catz._
import zio.interop.catz.implicits._
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import github4s.{GHResponse, Github}
import zio.console.Console
import github4s.domain.Repository
import zio.magic._

object Main extends App {

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

  def listRepos(): ZIO[GithubClientLayer, Throwable, List[Repository]] = {
    for {
      client <- ZIO.service[Github[Task]]
      repos <- client.repos.listUserRepos("nhyne")
      result <- ZIO.fromEither(repos.result).mapError(e => e.getCause)
    } yield result
  }

  val program = for {
    repos <- listRepos()
    _ <- ZIO.foreach(repos)(repo => putStrLn(repo.name))
  } yield ()


  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    val httpLayer = Http4sClient.live
    val githubClientLayer = GithubClient.live(None)

    program.inject(httpLayer, githubClientLayer, Console.live).exitCode
  }
}
