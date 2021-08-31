package git

import zio.{ExitCode, Has, ZIO, ZLayer, random}
import zio.blocking.Blocking
import zio.clock.{Clock, sleep}
import zio.console.Console
import zio.duration.Duration.fromMillis
import zio.json._
import zio.logging.{Logging, log}
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.process.{Command, CommandError}
import zio.random.Random

object Git {

  final case class PullRequestEvent(
      action: PullRequestAction,
      number: Int,
      @jsonField("pull_request") pullRequest: PullRequest
  )

  final case class PullRequest(
      url: String,
      id: Long,
      number: Int,
      state: String, // TODO: Should be a union type
      head: Branch,
      base: Branch
  )

  final case class Branch(
      ref: String,
      sha: String,
      repo: Repository
  ) // there are a lot more fields than just these

  object Branch {
    def fromString(branchName: String, repo: Repository) =
      Branch(branchName, branchName, repo)
  }

  final case class Repository(
      name: String,
      @jsonField("full_name") fullName: String,
      owner: Owner,
      @jsonField("html_url") htmlUrl: String,
      @jsonField("ssh_url") sshUrl: String,
      @jsonField("clone_url") cloneUrl: String
  )
  object Repository {
    def fromNameAndOwner(name: String, owner: String): Repository =
      Repository(
        name,
        s"$name/$owner",
        Owner(owner),
        htmlUrl = s"https://github.com/$owner/$name",
        sshUrl = s"git@github.com:$owner/$name.git",
        cloneUrl = s"https://github.com/$owner/$name.git"
      )
  }

  // TODO: Would be better if I can just pull the owner from the request body. Not sure if there's something different between "owner" and "organization"
  final case class Owner(login: String)

  implicit val ownerDecoder: JsonDecoder[Owner] = DeriveJsonDecoder.gen[Owner]
  implicit val repositoryDecoder: JsonDecoder[Repository] =
    DeriveJsonDecoder.gen[Repository]
  implicit val branchDecoder: JsonDecoder[Branch] =
    DeriveJsonDecoder.gen[Branch]
  implicit val pullRequestDecoder: JsonDecoder[PullRequest] =
    DeriveJsonDecoder.gen[PullRequest]
  implicit val pullRequestEventDecoder: JsonDecoder[PullRequestEvent] =
    DeriveJsonDecoder.gen[PullRequestEvent]

  sealed trait PullRequestAction

  object PullRequestAction {
    case object Opened extends PullRequestAction

    case object Synchronize extends PullRequestAction

    case object Closed extends PullRequestAction

    final case class Unknown(`type`: String) extends PullRequestAction

    implicit val prActionDecoder: JsonDecoder[PullRequestAction] =
      JsonDecoder[String].map {
        case "opened"      => Opened
        case "synchronize" => Synchronize
        case "closed"      => Closed
        case actionType    => Unknown(actionType)
      }
  }

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
