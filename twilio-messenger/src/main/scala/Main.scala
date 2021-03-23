import zhttp.http._
import zhttp.service.Server
import zio._
import zio.json._

case class Person(name: String)
object Person {
  implicit val decoder: JsonDecoder[Person] = DeriveJsonDecoder.gen[Person]
  implicit val formatter: JsonEncoder[Person] = DeriveJsonEncoder.gen[Person]
}

object Messenger extends App {

  val katherine = Person("katherine")

  val app = Http.collect[Request] {
    case Method.GET -> Root / "json" =>
      Response.jsonString(katherine.toJson)
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    Server.start(8090, app.silent).exitCode
}
