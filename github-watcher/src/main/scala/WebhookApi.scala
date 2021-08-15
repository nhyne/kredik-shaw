import zhttp.service._
import zhttp.http._
import zio._
import zio.console.{Console, putStrLn}

object WebhookApi {

  private val PORT = 8090

  private val apiRoot = Root / "api" / "sre-webhook"

  private val fooBar: HttpApp[Console, HttpError] = HttpApp.collectM {
    case req @ Method.POST -> `apiRoot` / "pull-request" => handlePostRequest(req)
  }

  val server: Server[Console, HttpError] = Server.port(PORT) ++ Server.app(fooBar)

  def handlePostRequest(request: Request): ResponseM[Console, HttpError] = for {
    a <- putStrLn(request.toString).bimap(
      _ => HttpError.NotImplemented("whoops"),
      _ => Response.text("OK")
    )
  } yield a

}
