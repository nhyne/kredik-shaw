import zhttp.http._
import zhttp.service.Server
import zio._
import zio.json._
import zio.magic._

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

  def doAPutUser(): ZIO[UserService.Service, HttpError, Response] =
    for {
      a <-
        RIO
          .accessM[UserService.Service](
            _.insertUser(
              NewUser(
                firstName = "adam",
                lastName = "johnson",
                email = "email",
                phoneNumber = "3456789"
              )
            )
          )
          .bimap(
            _ => HttpError.NotImplemented("whooop error!"),
            user => Response.text(s"$user")
          )
    } yield a

  val app: Http[Any, HttpError, Request, Response] = {
    val server: Http[Any, HttpError, Request, Response] =
      Http.collectM[Request] {
        case Method.GET -> Root / "json" =>
          UIO(Response.jsonString(katherine.toJson))
        case Method.GET -> Root / "zio" =>
          doAPutUser().provideMagicLayer(UserService.dummy)
      }
    server
  }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val userService = UserService.dummy
    Server
      .start(8090, app.silent)
//      .provideMagicLayer(zio.console.Console.live)
      .exitCode
  }
}

object UserService {
  trait Service {
    def getUserById(id: UUID): IO[Option[Nothing], User]
    def insertUser(user: NewUser): Task[User]
  }

  type UserService = Has[Service]

  val dummy: ZLayer[Any, Nothing, Has[Service]] =
    ZIO.succeed(DummyUserService()).toLayer

  final case class DummyUserService() extends UserService.Service {
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
