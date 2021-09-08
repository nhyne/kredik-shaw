package nhyne.git

import nhyne.git.GitCli.Service
import nhyne.git.GitEvents.{Repository, Branch}
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.process.CommandError
import zio.random.Random
import zio.{ExitCode, ZIO, ZLayer}

object GitSpec {
  val test = ZLayer.succeed(new Service {
    private def gitCommand(repository: Repository) =
      if (repository.name == "succeed") ZIO.succeed(ExitCode.success)
      else ZIO.succeed(ExitCode.failure)
    override def gitClone(
        repository: Repository,
        branch: Branch,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode] = {
      gitCommand(repository)
    }

    override def gitCloneDepth(
        repository: Repository,
        branch: Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode] = gitCommand(repository)

    override def gitCloneAndMerge(
        repository: Repository,
        head: Branch,
        toMerge: Branch,
        cloneDir: Path
    ): ZIO[Blocking with Random, Throwable, Path] =
      ZIO.succeed(Path("cool"))

  })
}
