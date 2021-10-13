package nhyne.git

import nhyne.Errors.KredikError.CliError
import nhyne.CommandWrapper.commandToKredikExitCode
import zio.{ExitCode, ZIO, ZLayer}
import zio.blocking.Blocking
import nhyne.git.GitEvents._
import zio.nio.core.file.Path
import zio.process.Command

trait GitCli {
  def gitClone(
      repository: Repository,
      branch: Branch,
      cloneInto: Path
  ): ZIO[Blocking, CliError, ExitCode]

  def gitCloneDepth(
      repository: Repository,
      branch: Branch,
      depth: Int,
      cloneInto: Path
  ): ZIO[Blocking, CliError, ExitCode]

  def gitCloneAndMerge(
      pullRequest: PullRequest,
      cloneDir: Path
  ): ZIO[Blocking, CliError, ExitCode]
}
object GitCli {

  val live = ZLayer.succeed(new GitCli {
    override def gitCloneDepth(
        repository: Repository,
        branch: Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Blocking, CliError, ExitCode] =
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
    ): ZIO[Blocking, CliError, ExitCode] =
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
      Blocking,
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
