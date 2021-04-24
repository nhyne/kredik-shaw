import UserService.UserService
import zhttp.http._
import zhttp.service._
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

object User {
  implicit val decoder: JsonDecoder[User] = DeriveJsonDecoder.gen[User]
  implicit val formatter: JsonEncoder[User] = DeriveJsonEncoder.gen[User]
}

case class User(
  firstName: String,
  lastName: String,
  email: String,
  phoneNumber: String,
  uuid: UUID
)

object Messenger extends App {

  val katherine = Person("katherine")

  def doAPutUser(): ResponseM[UserService.UserService, HttpError] =
    for {
      a <-
        RIO
          .accessM[UserService.UserService](
            _.get.insertUser(
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

  def doAGetUser(
    userId: String
                ): ResponseM[UserService.UserService, HttpError] =
//  ): ZIO[UserService.UserService, HttpError, Response] =
    for {
      userUUID <-
        ZIO
          .fromTry(scala.util.Try(UUID.fromString(userId)))
          .mapError(_ => HttpError.InternalServerError("bboooo"))
      user <-
        RIO
          .accessM[UserService.UserService](_.get.getUserById(userUUID))
          .bimap(
            _ => HttpError.InternalServerError("boo"),
            user => Response.jsonString(user.toJson)
          )
    } yield user

  val app: Http[UserService, Throwable] =
    Http.collectM {
      case Method.GET -> Root / "json" =>
        UIO(Response.jsonString(katherine.toJson))
      case Method.GET -> Root / "zio" =>
        doAPutUser()
      case Method.GET -> Root / "users" / userId =>
        doAGetUser(userId)
    }

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    Server
      .start(8090, app.silent)
      .exitCode
      .injectSome(UserService.dummy)
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
