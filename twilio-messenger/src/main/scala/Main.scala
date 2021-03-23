import zhttp.http._
import zhttp.service.Server
import zio._
import zio.json._

import java.util.UUID
import scala.collection.mutable
import scala.collection.mutable.HashMap

case class Person(name: String)
object Person {
  implicit val decoder: JsonDecoder[Person] = DeriveJsonDecoder.gen[Person]
  implicit val formatter: JsonEncoder[Person] = DeriveJsonEncoder.gen[Person]
}

case class NewUser(
  firstName: String,
  lastName: String,
  email: String,
  phoneNumber: String
)

case class User(
  firstName: String,
  lastName: String,
  email: String,
  phoneNumber: String,
  uuid: UUID
)

object Messenger extends App {

  val katherine = Person("katherine")

  val app: Http[UserService.Service, HttpError, Request, Response] =
    Http.collect[Request] {
      case Method.GET -> Root / "json" =>
        Response.jsonString(katherine.toJson)
    }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val userService = UserService.dummy
    Server.start(8090, app.silent).exitCode.provideCustomLayer(userService)
  }
}

object UserService {
  trait Service {
    def getUserById(id: UUID): IO[Option[Nothing], User]
    def insertUser(user: NewUser): Task[User]
  }

  type UserService = Has[Service]

  val dummy: ZLayer[Any, Throwable, UserService] = DummyUserService()

  object DummyUserService extends UserService.Service {
    val userMap: mutable.HashMap[UUID, User] = mutable.HashMap.empty

    override def getUserById(id: UUID) = ZIO.fromOption(userMap.get(id))

    override def insertUser(user: NewUser): Task[User] =
      ZIO.effect {
        val uuid = UUID.randomUUID()
        val userToInsert = User(
          firstName = user.firstName,
          lastName = user.lastName,
          email = user.email,
          phoneNumber = user.phoneNumber,
          uuid = uuid
        )
        userMap.addOne((uuid, userToInsert))
        userToInsert
      }
  }
}
