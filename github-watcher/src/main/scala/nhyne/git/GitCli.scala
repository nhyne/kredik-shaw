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
    ): ZIO[Blocking with Random, Throwable, Path] =
      gitCloneAndMerge(
        pullRequest.head.repo,
        pullRequest.head,
        pullRequest.base,
        cloneDir
      )

    def gitCloneAndMerge(
        repository: Repository,
        head: Branch,
        toMerge: Branch,
        cloneDir: Path
    ): ZIO[
      Blocking with Random,
      Throwable,
      Path
    ]
  }

  val live = ZLayer.succeed(new Service {
    override def gitCloneDepth(
        repository: Repository,
        branch: Branch,
        depth: Int,
        cloneInto: Path
    ) =
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
    ) =
      Command(
        "git",
        "clone",
        s"--branch=${branch.ref}",
        repository.htmlUrl,
        cloneInto.toString()
      ).exitCode

    override def gitCloneAndMerge(
        repository: Repository,
        head: Branch,
        toMerge: Branch,
        cloneInto: Path
    ): ZIO[
      Blocking with Random,
      Throwable,
      Path
    ] =
      for {
        folderName <- random.nextUUID
        folderPath = cloneInto./(folderName.toString)
        _ <- Files.createDirectory(folderPath)
        _ <- gitClone(
          repository,
          head,
          folderPath
        )
        _ <- gitMerge(toMerge)
          .workingDirectory(folderPath.toFile)
          .successfulExitCode
      } yield folderPath
  })

  private def gitMerge(target: Branch) =
    Command("git", "merge", s"origin/${target.ref}")
}
