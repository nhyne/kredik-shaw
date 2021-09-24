package dev.nhyne.git

import dev.nhyne.KredikError.CliError
import dev.nhyne.commandToKredikExitCode
import zio.{ExitCode, Has, ZIO, ZLayer}
import zio.blocking.Blocking
import dev.nhyne.git.GitEvents._
import zio.nio.core.file.Path
import zio.process.Command

object GitCli {

  type GitCliService = Has[Service]
  private type Env = Blocking

  trait Service {
    def gitClone(
        repository: Repository,
        branch: Branch,
        cloneInto: Path
    ): ZIO[Env, CliError, ExitCode]

    def gitCloneDepth(
        repository: Repository,
        branch: Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Env, CliError, ExitCode]

    def gitCloneAndMerge(
        pullRequest: PullRequest,
        cloneDir: Path
    ): ZIO[Env, CliError, ExitCode]
  }

  val live = ZLayer.succeed(new Service {
    override def gitCloneDepth(
        repository: Repository,
        branch: Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Env, CliError, ExitCode] =
      commandToKredikExitCode(
        Command(
          "git",
          "clone",
          s"--depth=$depth",
          s"--branch=${branch.ref}",
          repository.sshUrl,
          cloneInto.toString()
        )
      )

    override def gitClone(
        repository: Repository,
        branch: Branch,
        cloneInto: Path
    ): ZIO[Env, CliError, ExitCode] =
      commandToKredikExitCode(
        Command(
          "git",
          "clone",
          s"--branch=${branch.ref}",
          repository.sshUrl,
          cloneInto.toString()
        )
      )

    override def gitCloneAndMerge(
        pullRequest: PullRequest,
        cloneInto: Path
    ): ZIO[
      Env,
      CliError,
      ExitCode
    ] =
      for {
        _ <- gitClone(
          pullRequest.head.repo,
          pullRequest.head,
          cloneInto
        )
        exitCode <- commandToKredikExitCode(
          gitMerge(pullRequest.base)
            .workingDirectory(cloneInto.toFile)
        )
      } yield exitCode
  })

  private def gitMerge(target: Branch) =
    Command("git", "merge", s"origin/${target.ref}")
}
