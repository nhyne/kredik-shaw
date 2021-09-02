package git

import git.Authentication.{AuthenticationScheme, GitAuthenticationService}
import git.GithubApi.{CommentResponse, SBackend, Service, Topics}
import zio.{Has, ZIO, ZLayer, system}

object GithubApiSpec {

  val test = ZLayer.succeed(new Service {
    override def getTopics(
        org: String,
        repo: String
    ): ZIO[Has[SBackend], Throwable, GithubApi.Topics] =
      ZIO.succeed(Topics(names = Seq("name")))

    override def validateAuth(
        credentials: Authentication.AuthenticationScheme
    ): ZIO[Has[SBackend], Throwable, Boolean] = credentials match {
      case AuthenticationScheme.Bearer("valid")     => ZIO.succeed(true)
      case AuthenticationScheme.Basic("valid", _)   => ZIO.succeed(true)
      case AuthenticationScheme.Bearer("invalid")   => ZIO.succeed(false)
      case AuthenticationScheme.Basic("invalid", _) => ZIO.succeed(false)
      case _                                        => ZIO.fail(new Throwable("invalid option for mock validateAuth"))
    }

    override def createComment(
        message: String,
        pullRequest: GitCli.PullRequest
    ): ZIO[
      Has[SBackend] with system.System with GitAuthenticationService,
      Throwable,
      GithubApi.CommentResponse
    ] = ZIO.succeed(CommentResponse("a", "b", "c"))
  })
}
