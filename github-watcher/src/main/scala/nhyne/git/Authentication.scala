package nhyne.git

import nhyne.git.GithubApi.SBackend
import zio._
import zio.system.{ env, System }

trait Authentication {
  import nhyne.git.Authentication.AuthenticationScheme
  def getAuthentication(): UIO[AuthenticationScheme]
}

object Authentication {
  private val GITHUB_BEARER_TOKEN = "GITHUB_BEARER_TOKEN"
  private val GITHUB_USERNAME     = "GITHUB_USERNAME"
  private val GITHUB_TOKEN        = "GITHUB_TOKEN"

  final case class GitAuthenticationError(message: String)

  val live: ZLayer[Has[SBackend] with System with Has[GithubApi], Object, Has[
    Authentication
  ]] =
    ZLayer.fromEffect(for {
      authentication <- readAuthVars()
      isValid        <- ZIO
                          .service[GithubApi]
                          .flatMap(
                            _.validateAuth(authentication)
                              .mapError(authFailure => GitAuthenticationError(s"Could not validate auth: $authFailure"))
                          )
      _              <- ZIO
                          .fail(
                            GitAuthenticationError(
                              "Provided credentials could not make valid request to Github API"
                            )
                          )
                          .when(!isValid)
    } yield new Authentication {
      private val auth: AuthenticationScheme                      = authentication
      override def getAuthentication(): UIO[AuthenticationScheme] =
        ZIO.succeed(auth)
    })

  sealed trait AuthenticationScheme

  object AuthenticationScheme {
    final case class Basic(username: String, token: String) extends AuthenticationScheme
    final case class Bearer(token: String)                  extends AuthenticationScheme

    def actionOnScheme[A](
      scheme: AuthenticationScheme
    )(basic: (String, String) => A)(bearer: String => A) =
      scheme match {
        case Basic(username, token) => basic(username, token)
        case Bearer(token)          => bearer(token)
      }
  }

  private def readAuthVars() =
    for {
      gitBearer      <-
        env(GITHUB_BEARER_TOKEN).mapError(e => GitAuthenticationError(s"Could not read $GITHUB_BEARER_TOKEN: $e"))
      authentication <- ZIO
                          .fromOption(gitBearer.map(AuthenticationScheme.Bearer))
                          .catchAll {
                            case None    =>
                              for {
                                gitUsername <- env(GITHUB_USERNAME).flatMap(ZIO.fromOption(_))
                                gitToken    <- env(GITHUB_TOKEN).flatMap(ZIO.fromOption(_))
                              } yield AuthenticationScheme.Basic(gitUsername, gitToken)
                            case Some(_) =>
                              ZIO.fail(
                                GitAuthenticationError(
                                  "Could not find credentials, tried Bearer and Basic"
                                )
                              )
                          }
                          .mapError(_ =>
                            GitAuthenticationError(
                              "Could not find credentials, tried Bearer and Basic"
                            )
                          )
    } yield authentication
}
