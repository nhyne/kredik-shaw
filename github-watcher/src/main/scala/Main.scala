import zio._
import zio.console.putStrLn

import zio.interop.catz._
import zio.interop.catz.implicits._
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import github4s.Github
import scala.concurrent.ExecutionContext.Implicits

import github4s.Github._

object Main extends App {

  object http4sClient {

    object Http4sClientLive {
      val layer: ZLayer[Any, Throwable, Has[Client[Task]]] = {
        implicit val runtime: Runtime[ZEnv] = Runtime.default

        val res = BlazeClientBuilder[Task](runtime.platform.executor.asEC).resource.toManagedZIO
        ZLayer.fromManaged(res)
      }
    }
  }
//  private def makeHttpClient: UIO[TaskManaged[Client[Task]]] =
//    ZIO.runtime[Any].map { implicit rts =>
//      BlazeClientBuilder
//        .apply[Task](Implicits.global)
//        .resource
//        .toManaged
//    }

  object GithubClient {

  }

  def makeGithubClient(accessToken: String): ZIO[Has[Client[Task]], Throwable, Github[Task]] = {
    for {
      client <- ZIO.service[Client[Task]]
      githubTest = Github(accessToken = Some(accessToken), client = client)
    } yield githubTest
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    putStrLn("hello world").exitCode
  }
}
