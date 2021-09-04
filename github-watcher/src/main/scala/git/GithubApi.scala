package git

import git.Authentication.AuthenticationScheme
import git.GitEvents.{Branch, PullRequest, Repository}
import git.GithubCheck.Check
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

  private type Env = Has[SBackend] with System with GitAuthenticationService
  type SBackend = SttpBackend[Task, ZioStreams with capabilities.WebSockets]

  val live: ZLayer[Has[SBackend], Throwable, GithubApiService] =
    ZLayer.succeed(
      new Service {
        override def getTopics(
            org: String,
            repo: String
        ): ZIO[
          Env,
          Throwable,
          Topics
        ] =
          for {
            client <- ZIO.service[SBackend]
            authScheme <- ZIO
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
        override def createComment(
            message: String,
            pullRequest: PullRequest
        ): ZIO[
          Env,
          Throwable,
          CommentResponse
        ] =
          for {
            client <- ZIO.service[SBackend]
            authScheme <- ZIO
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
            response <- client
              .send(request)
              .flatMap(res => ZIO.fromEither(res.body))
          } yield response

        override def getPullRequest(repository: Repository, number: Int): ZIO[
          Env,
          Throwable,
          PullRequest
        ] =
          for {
            client <- ZIO.service[SBackend]
            authScheme <- ZIO
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
            response <- client
              .send(request)
              .flatMap(res => ZIO.fromEither(res.body))
          } yield response

        override def postCheckStatus(
            repository: Repository,
            branch: Branch,
            check: Check
        ): ZIO[Env, Throwable, Unit] = ???
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
    ): ZIO[Env, Throwable, Topics]

    def createComment(
        message: String,
        pullRequest: PullRequest
    ): ZIO[Env, Throwable, CommentResponse]

    def getPullRequest(
        repository: Repository,
        number: Int
    ): ZIO[Env, Throwable, PullRequest]

    def postCheckStatus(
        repository: Repository,
        branch: Branch,
        check: Check
    ): ZIO[Env, Throwable, Unit]

  }
}
