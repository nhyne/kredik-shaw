package nhyne.git

import nhyne.Errors.KredikError
import nhyne.git.Authentication.{AuthenticationScheme, GitAuthenticationService}
import nhyne.git.GitEvents.PullRequest
import nhyne.git.GithubApi.{
  CommentResponse,
  GithubApiService,
  SBackend,
  Service,
  Topics
}
import zio.{Has, ZIO, ZLayer, system}

object GithubApiSpec {

  val test: ZLayer[Has[SBackend], Throwable, GithubApiService] =
    ZLayer.succeed(new Service {
      override def getTopics(
          org: String,
          repo: String
      ): ZIO[Has[SBackend], Throwable, GithubApi.Topics] =
        ZIO.succeed(Topics(names = Seq("name")))

      override def validateAuth(
          credentials: Authentication.AuthenticationScheme
      ): ZIO[Has[SBackend], Throwable, Boolean] =
        credentials match {
          case AuthenticationScheme.Bearer("valid")     => ZIO.succeed(true)
          case AuthenticationScheme.Basic("valid", _)   => ZIO.succeed(true)
          case AuthenticationScheme.Bearer("invalid")   => ZIO.succeed(false)
          case AuthenticationScheme.Basic("invalid", _) => ZIO.succeed(false)
          case _ =>
            ZIO.fail(new Throwable("invalid option for mock validateAuth"))
        }

      override def getPullRequest(
          repository: GitEvents.Repository,
          number: Int
      ): ZIO[
        Has[SBackend] with system.System with GitAuthenticationService,
        KredikError,
        PullRequest
      ] = ???

      override def getBranchSha(
          repository: GitEvents.Repository,
          branchName: String
      ): ZIO[Has[
        SBackend
      ] with system.System with GitAuthenticationService, KredikError, String] =
        ???

      override def createComment(
          message: String,
          pullRequest: PullRequest
      ): ZIO[
        Has[SBackend] with system.System with GitAuthenticationService,
        KredikError,
        GithubApi.CommentResponse
      ] = ZIO.succeed(CommentResponse("a", "b", "c"))
    })
}
