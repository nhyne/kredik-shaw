import zhttp.service._
import zhttp.http._
import zio._
import zio.blocking.{Blocking, effectBlocking}
import zio.console.{Console, putStrLn}
import zio.process.Command
import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.v1.namespaces.{Namespaces, create, get}
import com.coralogix.zio.k8s.model.core.v1.Namespace
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import zio.json._

import java.nio.file.Files
import java.io.File

object WebhookApi {

  private val PORT = 8090

  private val apiRoot = Root / "api" / "sre-webhook"

  private val apiServer
      : HttpApp[Console with Blocking with Namespaces, HttpError] =
    HttpApp.collectM { case req @ Method.POST -> `apiRoot` =>
      handlePostRequest(req).mapBoth(
        cause =>
          HttpError.InternalServerError(cause =
            Some(new Throwable(cause.toString))
          ),
        body => Response.text(body.toString)
      )
    }

  val server: Server[Console with Blocking with Namespaces, HttpError] =
    Server.port(PORT) ++ Server.app(apiServer) ++ Server.maxRequestSize(100 * 1024)

  def handlePostRequest(request: Request) = request.getBodyAsString match {

    case Some(body) =>
      for {
        pullRequestEvent <- ZIO.fromEither(body.fromJson[PullRequestEvent])
        _ <- performEventAction(pullRequestEvent)
      } yield pullRequestEvent
    case None => ZIO.fail("Did not receive a request body")

  }

  def performEventAction(event: PullRequestEvent) = {
    event.action match {
      case PullRequestAction.Opened      => ZIO.succeed("open")
      case PullRequestAction.Synchronize => openedEvent(event).map(_.toString)
      case PullRequestAction.Closed      => ZIO.succeed("close")
    }
  }

  private def openedEvent(event: PullRequestEvent) = for {
    mergeSuccessful <- gitCloneAndMerge(
      event.pullRequest.base.repo.owner.login,
      event.pullRequest.head.repo.name,
      event.pullRequest.head.ref,
      event.pullRequest.base.ref
    )
    ns <- createPRNamespace(
      event.pullRequest.number,
      event.pullRequest.base.repo.name
    )
    applied <- applyFile(mergeSuccessful, ns)
    _ <- cleanupTempDir(mergeSuccessful)
  } yield applied

  private def gitClone(repo: String, branch: String) =
    Command("git", "clone", repo, s"--branch=$branch")

  private def gitMerge(target: String) = Command("git", "merge", target)

  private def createRepoCloneDir(repo: String) = ZIO.effect {
    Files.createTempDirectory(s"pr-$repo").toFile
  }

  private def applyFile(repoDir: File, namespace: Namespace) = for {
    exitCode <- Command(
      "kubectl",
      "apply",
      "-n",
      namespace.metadata.flatMap(_.name).getOrElse(""),
      "-f",
      s"${repoDir.getAbsolutePath}/.watcher.conf/basic.yaml"
    ).run
  } yield exitCode

  // TODO: Should do this work in a temp directory that gets cleaned up later
  def gitCloneAndMerge(
      organization: String,
      repo: String,
      branch: String,
      target: String
  ): ZIO[Blocking with Console, Throwable, File] = for {
    workingDir <- createRepoCloneDir(s"$organization-$repo")
    _ <- gitClone(s"https://github.com/$organization/$repo", branch)
      .workingDirectory(workingDir)
      .run
    repoDir = new File(s"${workingDir.getAbsolutePath}/$repo")
    _ <- gitMerge(target).workingDirectory(repoDir).run.exitCode
  } yield repoDir

  def cleanupTempDir(dir: File): RIO[Blocking, Boolean] = {
    effectBlocking(dir.delete())
  }

  def createPRNamespace(
      prNumber: Int,
      repo: String
  ): ZIO[Namespaces, K8sFailure, Namespace] = {
    val namespaceName = s"$repo-pr-$prNumber"
    val prNamespace = Namespace(metadata =
      ObjectMeta(
        name = Some(namespaceName)
      )
    )
    for {
      ns <- get(namespaceName).foldM(
        _ => create(prNamespace),
        success => ZIO.succeed(success)
      )
    } yield ns
  }

  final case class PullRequestEvent(
      action: PullRequestAction,
      number: Int,
      @jsonField("pull_request") pullRequest: PullRequest
  )

  final case class PullRequest(
      url: String,
      id: Long,
      number: Int,
      state: String,
      head: Branch,
      base: Branch
  )

  final case class Branch(
      ref: String,
      sha: String,
      repo: Repository
  ) // there are a lot more fields than just these
  final case class Repository(
      name: String,
      @jsonField("full_name") fullName: String,
      owner: Owner
  )
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

    implicit val prActionDecoder: JsonDecoder[PullRequestAction] =
      JsonDecoder[String].map {
        case "opened"      => Opened
        case "synchronize" => Synchronize
        case "closed"      => Closed
        case _             => ???
      }
  }

}
