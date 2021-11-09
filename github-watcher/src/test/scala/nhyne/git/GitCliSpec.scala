package nhyne.git

import nhyne.Errors.KredikError.CliError
import nhyne.git.GitCli
import nhyne.git.GitEvents.PullRequest
import zio._
import zio.blocking.Blocking
import zio.nio.channels.FileChannel
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.process.CommandError

import java.io.IOException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

object GitCliSpec {

  val test: ULayer[Has[GitCli]] = ZLayer.succeed(new GitCli {
    override def setGitConfig(githubUser: GitEvents.GithubUser): ZIO[Blocking, CliError, ExitCode] =
      ZIO.succeed(ExitCode.success)

    override def gitClone(
      repository: GitEvents.Repository,
      branch: GitEvents.Branch,
      cloneInto: Path
    ): ZIO[Blocking, CliError, ExitCode] =
      if (repository.name == "failure")
        ZIO.fail(
          CliError(
            CommandError.NonZeroErrorCode(ExitCode(2)),
            None,
            Some("mock gitClone intended failure")
          )
        )
      else ZIO.succeed(ExitCode.success)

    override def gitCloneDepth(
      repository: GitEvents.Repository,
      branch: GitEvents.Branch,
      depth: Int,
      cloneInto: Path
    ): ZIO[Blocking, CliError, ExitCode] =
      if (repository.name == "failure")
        ZIO.fail(
          CliError(
            CommandError.NonZeroErrorCode(ExitCode(2)),
            None,
            Some("mock gitCloneDepth intended failure")
          )
        )
      else
        for {
          _        <- Files
                        .createFile(
                          cloneInto./(".watcher.yaml"),
                          PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-rw-rw-")
                          )
                        )
                        .mapError(e => CliError(CommandError.IOError(e)))
          _        <- FileChannel
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
                        .mapError(e => CliError(e))
          exitCode <- ZIO.succeed(ExitCode.success)
        } yield exitCode

    override def gitCloneAndMerge(
      pullRequest: PullRequest,
      cloneDir: Path
    ): ZIO[Blocking, CliError, ExitCode] =
      if (pullRequest.number == 1)
        ZIO.fail(
          CliError(
            CommandError.NonZeroErrorCode(ExitCode(2)),
            None,
            Some("mock gitCloneAndMerge intended failure")
          )
        )
      else ZIO.succeed(ExitCode.success)
  })
}
