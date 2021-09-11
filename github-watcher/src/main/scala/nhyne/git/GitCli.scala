package nhyne.git

import zio.{ExitCode, Has, ZIO, ZLayer, random}
import zio.blocking.Blocking
import zio.clock.{Clock, sleep}
import zio.console.Console
import zio.duration.Duration.fromMillis
import zio.json._
import zio.logging.{Logging, log}
import nhyne.git.GitEvents._
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.process.{Command, CommandError}
import zio.random.Random

object GitCli {

  type GitCliService = Has[Service]

  trait Service {
    def gitClone(
        repository: Repository,
        branch: Branch,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode]

    def gitCloneDepth(
        repository: Repository,
        branch: Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode]

    def gitCloneAndMerge(
        pullRequest: PullRequest,
        cloneDir: Path
    ): ZIO[Blocking, Throwable, ExitCode]
  }

  val live = ZLayer.succeed(new Service {
    override def gitCloneDepth(
        repository: Repository,
        branch: Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode] =
      Command(
        "git",
        "clone",
        s"--depth=$depth",
        s"--branch=${branch.ref}",
        repository.htmlUrl,
        cloneInto.toString()
      ).successfulExitCode

    override def gitClone(
        repository: Repository,
        branch: Branch,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode] =
      Command(
        "git",
        "clone",
        s"--branch=${branch.ref}",
        repository.htmlUrl,
        cloneInto.toString()
      ).exitCode

    override def gitCloneAndMerge(
        pullRequest: PullRequest,
        cloneInto: Path
    ): ZIO[
      Blocking,
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
