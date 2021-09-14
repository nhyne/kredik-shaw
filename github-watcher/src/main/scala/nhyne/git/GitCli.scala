package nhyne.git

import zio.{ExitCode, Has, ZIO, ZLayer}
import zio.blocking.Blocking
import nhyne.git.GitEvents._
import zio.nio.core.file.Path
import zio.process.{Command, CommandError}

object GitCli {

  type GitCliService = Has[Service]
  private type Env = Blocking

  trait Service {
    def gitClone(
        repository: Repository,
        branch: Branch,
        cloneInto: Path
    ): ZIO[Env, CommandError, ExitCode]

    def gitCloneDepth(
        repository: Repository,
        branch: Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Env, CommandError, ExitCode]

    def gitCloneAndMerge(
        pullRequest: PullRequest,
        cloneDir: Path
    ): ZIO[Env, Throwable, ExitCode]
  }

  val live = ZLayer.succeed(new Service {
    override def gitCloneDepth(
        repository: Repository,
        branch: Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Env, CommandError, ExitCode] =
      Command(
        "git",
        "clone",
        s"--depth=$depth",
        s"--branch=${branch.ref}",
        repository.sshUrl,
        cloneInto.toString()
      ).successfulExitCode

    override def gitClone(
        repository: Repository,
        branch: Branch,
        cloneInto: Path
    ): ZIO[Env, CommandError, ExitCode] =
      Command(
        "git",
        "clone",
        s"--branch=${branch.ref}",
        repository.sshUrl,
        cloneInto.toString()
      ).successfulExitCode

    override def gitCloneAndMerge(
        pullRequest: PullRequest,
        cloneInto: Path
    ): ZIO[
      Env,
      Throwable,
      ExitCode
    ] =
      for {
        _ <- gitClone(
          pullRequest.head.repo,
          pullRequest.head,
          cloneInto
        )
        exitCode <- gitMerge(pullRequest.base)
          .workingDirectory(cloneInto.toFile)
          .successfulExitCode
      } yield exitCode
  })

  private def gitMerge(target: Branch) =
    Command("git", "merge", s"origin/${target.ref}")
}
