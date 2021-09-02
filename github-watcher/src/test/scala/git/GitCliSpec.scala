package git

import git.GitCli.{Repository, Service}
import zio.blocking.Blocking
import zio.console.Console
import zio.nio.core.file.Path
import zio.process.CommandError
import zio.random.Random
import zio.{ExitCode, ZIO, ZLayer}

object GitCliSpec {
  val test = ZLayer.succeed(new Service {
    private def gitCommand(repository: Repository) =
      if (repository.name == "succeed") ZIO.succeed(ExitCode.success)
      else ZIO.succeed(ExitCode.failure)
    override def gitClone(
        repository: GitCli.Repository,
        branch: GitCli.Branch,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode] = {
      gitCommand(repository)
    }

    override def gitCloneDepth(
        repository: GitCli.Repository,
        branch: GitCli.Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode] = gitCommand(repository)

    override def gitCloneAndMerge(
        repository: GitCli.Repository,
        head: GitCli.Branch,
        toMerge: GitCli.Branch,
        cloneDir: Path
    ): ZIO[Blocking with Random, Throwable, Path] =
      ZIO.succeed(Path("cool"))

  })
}
