import zio._
import zio.console.putStrLn

import zio.interop.catz._
import zio.interop.catz.implicits._
import org.http4s.client.Client
import org.http4s.blaze.client.BlazeClientBuilder
import github4s.Github
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

  object GithubClient {
    def live(accessToken: String): ZLayer[Has[Client[Task]], Throwable, Has[Github[Task]]] = {
      (for {
        client <- ZIO.service[Client[Task]]
        github = Github(accessToken = Some(accessToken), client = client)
      } yield github).toLayer
    }
  }


  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    putStrLn("hello world").exitCode
  }
}
