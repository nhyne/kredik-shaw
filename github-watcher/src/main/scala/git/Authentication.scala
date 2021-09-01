package git

import git.GithubApi.SBackend
import zio._
import zio.system.{System, env}
import sttp.client3._

object Authentication {

  private val GITHUB_BEARER_TOKEN = "GITHUB_BEARER_TOKEN"
  private val GITHUB_USERNAME = "GITHUB_USERNAME"
  private val GITHUB_TOKEN = "GITHUB_TOKEN"

  type GitAuthenticationService = Has[Service]
  trait Service {
    def getAuthentication(): ZIO[System, Nothing, AuthenticationScheme]
  }

  private def validateAuth(
      credentials: AuthenticationScheme
  ): ZIO[Has[SBackend], Throwable, Boolean] =
    for {
      client <- ZIO.service[SBackend]
      noAuthRequest = basicRequest
        .get(uri"https://api.github.com/user")
      authRequest = AuthenticationScheme.actionOnScheme(credentials)(
        noAuthRequest.auth.basic
      )(noAuthRequest.auth.bearer)
      response <- client.send(authRequest)
    } yield response.code.isSuccess

  final case class GitAuthenticationError(message: String)

  val live: ZLayer[Has[SBackend] with System, Object, Has[Service]] =
    ZLayer.fromEffect(for {
      gitBearer <- env(GITHUB_BEARER_TOKEN).mapError(e =>
        GitAuthenticationError(s"Could not read $GITHUB_BEARER_TOKEN: $e")
      )
      authentication <- ZIO
        .fromOption(gitBearer.map(AuthenticationScheme.Bearer))
        .catchAll(_ =>
          for {
            gitUsername <- env(GITHUB_USERNAME).flatMap(ZIO.fromOption(_))
            gitToken <- env(GITHUB_TOKEN).flatMap(ZIO.fromOption(_))
          } yield AuthenticationScheme.Basic(gitUsername, gitToken)
        )
        .mapError(_ =>
          GitAuthenticationError(
            "Could not find credentials, tried Bearer and Basic"
          )
        )
      _ <- validateAuth(authentication).flatMap(isValid =>
        if (isValid) ZIO.unit
        else
          ZIO.fail(
            GitAuthenticationError(
              "Provided credentials could not make valid request to Github API"
            )
          )
      )
    } yield new Service {
      private val auth: AuthenticationScheme = authentication
      // the live section should load up the authentication scheme
      override def getAuthentication()
          : ZIO[System, Nothing, AuthenticationScheme] = ZIO.succeed(auth)
    })

  sealed trait AuthenticationScheme

  object AuthenticationScheme {
    final case class Basic(username: String, token: String)
        extends AuthenticationScheme
    final case class Bearer(token: String) extends AuthenticationScheme

    def actionOnScheme[A](
        scheme: AuthenticationScheme
    )(basic: (String, String) => A)(bearer: String => A) = {
      scheme match {
        case Basic(username, token) => basic(username, token)
        case Bearer(token)          => bearer(token)
      }
    }
  }

}
