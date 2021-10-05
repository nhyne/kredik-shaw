package nhyne.git

import nhyne.Errors.KredikError
import nhyne.git.Authentication.AuthenticationScheme
import nhyne.git.GitEvents.{PullRequest, Repository}
import zio._
import sttp.client3._
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.client3.ziojson._
import zio.json._
import nhyne.git.Authentication.GitAuthenticationService
import zio.system.System

object GithubApi {

  type SBackend = SttpBackend[Task, ZioStreams with capabilities.WebSockets]

  val live: ZLayer[Has[SBackend], Throwable, GithubApiService] =
    ZLayer.succeed(
      new Service {
        override def getTopics(
            org: String,
            repo: String
        ): ZIO[
          Has[SBackend] with GitAuthenticationService with System,
          Throwable,
          Topics
        ] =
          for {
            client <- ZIO.service[SBackend]
            authScheme <-
              ZIO
                .service[Authentication.Service]
                .flatMap(_.getAuthentication())
            request = addAuthToRequest(
              basicRequest
                .get(uri"https://api.github.com/repos/$org/$repo/topics")
                .header("Accept", "application/vnd.github.mercy-preview+json")
                .response(asJson[Topics]),
              authScheme
            )
            response <- client.send(request)
            topics <- ZIO.fromEither(response.body)
          } yield topics

        // TODO: Should tick metrics saying we commented on PR
        // TODO: Should not take message, should take KredikError
        override def createComment(
            message: String,
            pullRequest: PullRequest
        ): ZIO[
          Has[SBackend] with GitAuthenticationService with System,
          KredikError,
          CommentResponse
        ] =
          for {
            client <- ZIO.service[SBackend]
            authScheme <-
              ZIO
                .service[Authentication.Service]
                .flatMap(_.getAuthentication())
            request = addAuthToRequest(
              basicRequest
                .post(
                  uri"https://api.github.com/repos/${pullRequest.getBaseOwner()}/${pullRequest
                    .getBaseName()}/issues/${pullRequest.number}/comments"
                )
                .header("Accept", "application/vnd.github.v3+json")
                .response(asJson[CommentResponse])
                .body(CommentBody(message).toJson),
              authScheme
            )
            response <-
              client
                .send(request)
                .flatMap(res => ZIO.fromEither(res.body))
                .mapError(e => KredikError.GeneralError(e))
          } yield response

        override def getPullRequest(repository: Repository, number: Int): ZIO[
          Has[SBackend] with GitAuthenticationService with System,
          KredikError,
          PullRequest
        ] =
          for {
            client <- ZIO.service[SBackend]
            authScheme <-
              ZIO
                .service[Authentication.Service]
                .flatMap(_.getAuthentication())
            request = addAuthToRequest(
              basicRequest
                .get(
                  uri"https://api.github.com/repos/${repository.owner.login}/${repository.name}/pulls/$number"
                )
                .header("Accept", "application/vnd.github.v3+json")
                .response(asJson[PullRequest]),
              authScheme
            )
            response <-
              client
                .send(request)
                .flatMap(res => ZIO.fromEither(res.body))
                .mapError(e => KredikError.GeneralError(e))
          } yield response

        override def validateAuth(
            credentials: AuthenticationScheme
        ): ZIO[Has[SBackend], Throwable, Boolean] =
          for {
            client <- ZIO.service[SBackend]
            noAuthRequest =
              basicRequest
                .get(uri"https://api.github.com/user")
            authRequest = AuthenticationScheme.actionOnScheme(credentials)(
              noAuthRequest.auth.basic
            )(noAuthRequest.auth.bearer)
            response <- client.send(authRequest)
          } yield response.code.isSuccess
      }
    )

  private def addAuthToRequest[A](
      request: RequestT[
        Identity,
        Either[ResponseException[String, String], A],
        Any
      ],
      auth: AuthenticationScheme
  ): RequestT[Identity, Either[ResponseException[String, String], A], Any] = {
    auth match {
      case AuthenticationScheme.Basic(username, token) =>
        request.auth.basic(username, token)
      case AuthenticationScheme.Bearer(token) =>
        request.auth.bearer(token)
    }
  }

  implicit val topicsDecoder: JsonDecoder[Topics] =
    DeriveJsonDecoder.gen[Topics]

  implicit val commentResponseDecoder: JsonDecoder[CommentResponse] =
    DeriveJsonDecoder.gen[CommentResponse]
  final case class Topics(names: Seq[String])
  final case class CommentResponse(
      @jsonField("html_url") htmlUrl: String,
      @jsonField("issue_url") issueUrl: String,
      body: String
  )

  implicit val commentBodyEncoder: JsonEncoder[CommentBody] =
    DeriveJsonEncoder.gen[CommentBody]
  final case class CommentBody(body: String)

  type GithubApiService = Has[Service]

  trait Service {
    def getTopics(
        org: String,
        repo: String
    ): ZIO[Has[
      SBackend
    ] with System with GitAuthenticationService, Throwable, Topics]

    def createComment(
        message: String,
        pullRequest: PullRequest
    ): ZIO[Has[
      SBackend
    ] with System with GitAuthenticationService, KredikError, CommentResponse]

    def getPullRequest(
        repository: Repository,
        number: Int
    ): ZIO[Has[
      SBackend
    ] with System with GitAuthenticationService, KredikError, PullRequest]

    def validateAuth(
        credentials: AuthenticationScheme
    ): ZIO[Has[SBackend], Throwable, Boolean]
  }
}
