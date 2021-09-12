package nhyne.git

import nhyne.git.GitCli.Service
import nhyne.git.GitEvents.PullRequest
import zio._
import zio.blocking.Blocking
import zio.nio.channels.FileChannel
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.process.CommandError
import zio.random.Random

import java.io.IOException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

object GitCliSpec {

  val test: ULayer[Has[GitCli.Service]] = ZLayer.succeed(new Service {
    override def gitClone(
        repository: GitEvents.Repository,
        branch: GitEvents.Branch,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode] = {
      if (repository.name == "failure")
        ZIO.fail(CommandError.NonZeroErrorCode(ExitCode(2)))
      else ZIO.succeed(ExitCode.success)
    }

    override def gitCloneDepth(
        repository: GitEvents.Repository,
        branch: GitEvents.Branch,
        depth: Int,
        cloneInto: Path
    ): ZIO[Blocking, CommandError, ExitCode] = {
      if (repository.name == "failure")
        ZIO.fail(CommandError.NonZeroErrorCode(ExitCode(2)))
      else
        for {
          _ <-
            Files
              .createFile(
                cloneInto./(".watcher.yaml"),
                PosixFilePermissions.asFileAttribute(
                  PosixFilePermissions.fromString("rw-rw-rw-")
                )
              )
              .mapError(e => CommandError.IOError(e))
          _ <-
            FileChannel
              .open(cloneInto./(".watcher.yaml"), StandardOpenOption.WRITE)
              .use { channel =>
                channel
                  .writeChunk(Chunk.fromArray("""
                |---
                |resourceFolder: .watcher
                |templateCommand: Kustomize
                |dependencies:
                |  - owner: nhyne
                |    name: watcher-test-dependency
                |    branch: master
                |""".stripMargin.getBytes))
              }
              .mapError(e =>
                CommandError.IOError(
                  new IOException(s"could not write test config file: $e")
                )
              )
          exitCode <- ZIO.succeed(ExitCode.success)
        } yield exitCode
    }

    override def gitCloneAndMerge(
        pullRequest: PullRequest,
        cloneDir: Path
    ): ZIO[Blocking, Throwable, ExitCode] = {
      if (pullRequest.number == 1)
        ZIO.fail(CommandError.NonZeroErrorCode(ExitCode(2)))
      else ZIO.succeed(ExitCode.success)
    }
  })
}
