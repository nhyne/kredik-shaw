package nhyne.git

import nhyne.Errors.KredikError
import nhyne.git.Authentication.AuthenticationScheme
import nhyne.git.GitEvents.{ GithubUser, PullRequest }
import nhyne.git.GithubApi.{ CommentResponse, SBackend, Topics }
import zio.{ system, Has, ZIO, ZLayer }

object GithubApiSpec {

  val test: ZLayer[Has[SBackend], Throwable, Has[GithubApi]] =
    ZLayer.succeed(new GithubApi {
      override def getTopics(
        org: String,
        repo: String
      ): ZIO[Has[SBackend], Throwable, GithubApi.Topics] =
        ZIO.succeed(Topics(names = Seq("name")))

      override def validateAuth(
        credentials: Authentication.AuthenticationScheme
      ): ZIO[Has[SBackend], KredikError, GithubUser] =
        credentials match {
          case AuthenticationScheme.Bearer("valid")     => ZIO.succeed(GithubUser("testUser", None))
          case AuthenticationScheme.Basic("valid", _)   => ZIO.succeed(GithubUser("testUser", Some("email")))
          case AuthenticationScheme.Bearer("invalid")   => ZIO.fail(KredikError.GeneralError("bad test credentials"))
          case AuthenticationScheme.Basic("invalid", _) => ZIO.fail(KredikError.GeneralError("bad test credentials"))
          case _                                        =>
            ZIO.fail(KredikError.GeneralError("bad test credentials"))
        }

      override def getPullRequest(
        repository: GitEvents.Repository,
        number: Int
      ): ZIO[
        Has[SBackend] with system.System with Has[Authentication],
        KredikError,
        PullRequest
      ] = ???

      override def getBranchSha(
        repository: GitEvents.Repository,
        branchName: String
      ): ZIO[Has[
        SBackend
      ] with system.System with Has[Authentication], KredikError, String] =
        ???

      override def createComment(
        message: String,
        pullRequest: PullRequest
      ): ZIO[
        Has[SBackend] with system.System with Has[Authentication],
        KredikError,
        GithubApi.CommentResponse
      ] = ZIO.succeed(CommentResponse("a", "b", "c"))
    })
}
