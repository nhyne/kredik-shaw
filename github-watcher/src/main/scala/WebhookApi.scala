import com.coralogix.zio.k8s.client.model.PropagationPolicy
import zhttp.service._
import zhttp.http._
import zio._
import zio.blocking.{Blocking, effectBlocking}
import zio.console.{Console, putStrLn}
import zio.process.Command
import com.coralogix.zio.k8s.client.{K8sFailure, NotFound => K8sNotFound}
import com.coralogix.zio.k8s.client.v1.namespaces.{
  Namespaces,
  create,
  delete,
  get
}
import com.coralogix.zio.k8s.model.core.v1.Namespace
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{
  DeleteOptions,
  ObjectMeta,
  Status => K8sStatus
}
import template.RepoConfig
import zio.json._
import zio.logging._
import zio.config._
import template.Template.TemplateService
import zio.clock.Clock
import zio.magic._
import zio.nio.core.file.{Path => ZFPath}
import zio.nio.file.Files

object WebhookApi {

  private val PORT = 8090
  private type ServerEnv = Console
    with Blocking
    with Namespaces
    with Logging
    with TemplateService

  private val apiRoot = Root / "api" / "sre-webhook"

  private val apiServer: HttpApp[ServerEnv, HttpError] =
    HttpApp.collectM { case req @ Method.POST -> `apiRoot` =>
      handlePostRequest(req).mapBoth(
        cause =>
          // TODO: Make this cleaner
          HttpError.InternalServerError(cause =
            Some(new Throwable(cause.toString))
          ),
        body => Response.text(body.toString)
      )
    }

  val server: Server[ServerEnv, HttpError] =
    Server.port(PORT) ++ Server.app(apiServer) ++ Server.maxRequestSize(
      // This is currently arbitrary. Would like to switch to streams/chunks
      100 * 1024
    )

  def handlePostRequest(request: Request) = request.getBodyAsString match {

    case Some(body) =>
      for {
        pullRequestEvent <- ZIO.fromEither(body.fromJson[PullRequestEvent])
        _ <- performEventAction(pullRequestEvent).tapError(err =>
          log.error(s"${err.toString}")
        )
      } yield pullRequestEvent // TODO: Should not be returning the pull request event
    case None => ZIO.fail("Did not receive a request body")
  }

  def performEventAction(event: PullRequestEvent) = { // TODO: This error type should not be an Object
    event.action match {
      case PullRequestAction.Opened => openedEvent(event).map(_.toString)
      case PullRequestAction.Synchronize =>
        openedEvent(event).map(
          _.toString
        ) // TODO: This should be its own action
      case PullRequestAction.Closed => deleteNamespace(event)
      case PullRequestAction.Unknown(actionType) =>
        log.warn(s"got unknown action type: $actionType")
    }
  }

  private def openedEvent(event: PullRequestEvent) = for {
    (repoDirectory, gitRev) <- gitCloneAndMerge(
      event.pullRequest.base.repo.owner.login,
      event.pullRequest.head.repo.name,
      event.pullRequest.head.ref,
      event.pullRequest.base.ref
    )
    nsName <- createPRNamespace(
      event.pullRequest.number,
      event.pullRequest.base.repo.name
    )
    configLayer = ZConfig.fromPropertiesFile(
      repoDirectory./(".watcher.conf").toString(),
      RepoConfig.repoConfigDescriptor
    )
    templateService <- ZIO.service[template.Template.Service]
    templatedManifests <- templateService
      .templateManifests(repoDirectory, nsName, gitRev)
      .inject(configLayer, Blocking.live)
    applied <- applyFile(templatedManifests, nsName)
    _ <- cleanupTempDir(repoDirectory)
  } yield applied

  private def gitClone(repo: String, branch: String) =
    Command("git", "clone", repo, s"--branch=$branch")

  private def gitMerge(target: String) =
    Command("git", "merge", s"origin/$target")

  private def createRepoCloneDir(repo: String) =
    Files.createTempDirectory(Some(s"pr-$repo-"), Seq.empty)

  private val gitRevParse = Command("git", "rev-parse", "HEAD")

  private def applyFile(repoDir: ZFPath, namespaceName: String) = for {
    exitCode <- Command(
      "kubectl",
      "apply",
      "-n",
      namespaceName,
      "-f",
      repoDir.toString()
    ).run
  } yield exitCode

  // TODO: Should do this work in a temp directory that gets cleaned up later
  def gitCloneAndMerge(
      organization: String,
      repo: String,
      branch: String,
      target: String
  ): ZIO[Blocking with Console, Throwable, (ZFPath, String)] = for {
    workingDir <- createRepoCloneDir(s"$organization-$repo")
    _ <- gitClone(s"https://github.com/$organization/$repo", branch)
      .workingDirectory(workingDir.toFile)
      .run
    repoDir = workingDir./(repo)
    gitRev <- gitRevParse.workingDirectory(repoDir.toFile).string
    _ <- gitMerge(target).workingDirectory(repoDir.toFile).run.exitCode
  } yield (repoDir, gitRev)

  // TODO: Does zio-nio have a helper for this?
  def cleanupTempDir(dir: ZFPath): RIO[Blocking, Boolean] = {
    effectBlocking(dir.toFile.delete())
  }

  private def createPRNamespace(
      prNumber: Int,
      repo: String
  ): ZIO[Namespaces, K8sFailure, String] = {
    val (nsName, prNamespace) = namespaceObject(prNumber, repo)
    get(nsName)
      .foldM(
        {
          case K8sNotFound => create(prNamespace)
          case e           => ZIO.fail(e)
        },
        success => ZIO.succeed(success)
      )
      .map(_ => nsName)
  }

  private def namespaceName(prNumber: Int, repo: String): String =
    s"$repo-pr-$prNumber"
  private def namespaceObject(
      prNumber: Int,
      repo: String
  ): (String, Namespace) = {
    val nsName = namespaceName(prNumber, repo)
    (nsName, Namespace(metadata = ObjectMeta(name = Some(nsName))))
  }

  // TODO: Have an error with deleting namespaces (Deserialization error)
  //  The actual namespace does get deleted but we're logging an error + returning 500
  def deleteNamespace(event: PullRequestEvent) = {
    val nsName = namespaceName(event.number, event.pullRequest.base.repo.name)
    delete(
      nsName,
      DeleteOptions(propagationPolicy = "Background"),
      propagationPolicy = Some(PropagationPolicy.Background)
    ).foldM(
      {
        case K8sNotFound =>
          log.warn(
            s"attempting to delete namespace: $nsName that does not exist"
          ) *> ZIO.succeed(
            K8sStatus(code = 200, message = s"namespace $nsName did not exist")
          )
        case f =>
          log.warn(s"failed to delete ns: $nsName with error: $f") *> ZIO.fail(
            f
          )
      },
      status => ZIO.succeed(status)
    )
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

    final case class Unknown(`type`: String) extends PullRequestAction

    implicit val prActionDecoder: JsonDecoder[PullRequestAction] =
      JsonDecoder[String].map {
        case "opened"      => Opened
        case "synchronize" => Synchronize
        case "closed"      => Closed
        case actionType    => Unknown(actionType)
      }
  }

}
