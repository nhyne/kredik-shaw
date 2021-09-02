package git

import git.Authentication.AuthenticationScheme
import git.GitCli.PullRequest
import zio._
import zio.interop.catz._
import zio.interop.catz.implicits._
import sttp.client3._
import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.client3.ziojson._
import zio.json._
import git.Authentication.GitAuthenticationService
import zio.system.System

object GithubApi {

  type SBackend = SttpBackend[Task, ZioStreams with capabilities.WebSockets]

  val live: ZLayer[Has[SBackend], Throwable, GithubApiService] =
    ZLayer.succeed(
      new Service {
        override def getTopics(
            org: String,
            repo: String
        ): ZIO[Has[SBackend], Throwable, Topics] =
          for {
            client <- ZIO.service[SBackend]
            request = basicRequest
              .get(uri"https://api.github.com/repos/$org/$repo/topics")
              .header("Accept", "application/vnd.github.mercy-preview+json")
              .response(asJson[Topics])
            response <- client.send(request)
            topics <- ZIO.fromEither(response.body)
          } yield topics

        // TODO: Should tick metrics saying we commented on PR
        override def createComment(
            message: String,
            pullRequest: PullRequest
        ): ZIO[
          Has[SBackend] with GitAuthenticationService with System,
          Throwable,
          CommentResponse
        ] =
          for {
            client <- ZIO.service[SBackend]
            request = basicRequest
              .post(
                uri"https://api.github.com/repos/${pullRequest.getBaseOwner()}/${pullRequest
                  .getBaseName()}/issues/${pullRequest.number}/comments"
              )
              .header("Accept", "application/vnd.github.v3+json")
              .response(asJson[CommentResponse])
              .body(CommentBody(message).toJson)
            _ = println(request.toCurl)
            authScheme <- ZIO
              .service[Authentication.Service]
              .flatMap(_.getAuthentication())
            authRequest = authScheme match {
              case AuthenticationScheme.Basic(username, token) =>
                request.auth.basic(username, token)
              case AuthenticationScheme.Bearer(token) =>
                request.auth.bearer(token)
            }
            response <- client
              .send(authRequest)
              .flatMap(res => ZIO.fromEither(res.body))
          } yield response

        override def validateAuth(
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
      }
    )

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
    ): ZIO[Has[SBackend], Throwable, Topics]

    def validateAuth(
        credentials: AuthenticationScheme
    ): ZIO[Has[SBackend], Throwable, Boolean]

    def createComment(
        message: String,
        pullRequest: PullRequest
    ): ZIO[Has[SBackend] with System with GitAuthenticationService, Throwable, CommentResponse]
  }
}
