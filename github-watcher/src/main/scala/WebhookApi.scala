import com.coralogix.zio.k8s.client.model.PropagationPolicy
import zhttp.service._
import zhttp.http._
import zio._
import zio.blocking.{Blocking, effectBlocking}
import zio.console.{Console, putStrLn}
import zio.process.{Command, CommandError}
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
import dependencies.DependencyConverter.DependencyConverterService
import template.{RepoConfig, Template}
import zio.json._
import zio.logging._
import zio.config._
import zio.config.yaml.YamlConfigSource
import template.Template.TemplateService
import zio.clock.Clock
import zio.magic._
import zio.duration.Duration.fromMillis
import zio.nio.core.file.{Path => ZFPath}
import zio.nio.file.Files
import zio.random.Random

object WebhookApi {

  private val PORT = 8090
  private type ServerEnv = ZEnv
    with Namespaces
    with Logging
    with TemplateService
    with DependencyConverterService

  private val apiRoot = Root / "api" / "sre-webhook"

  private val apiServer: HttpApp[ServerEnv, HttpError] =
    HttpApp.collectM {
      case req @ Method.POST -> `apiRoot` =>
        handlePostRequest(req).mapBoth(
          cause =>
            // TODO: Make this cleaner
            HttpError
              .InternalServerError(cause = Some(new Throwable(cause.toString))),
          body => Response.text(body.toString)
        )
    }

  val server: Server[ServerEnv, HttpError] =
    Server.port(PORT) ++ Server.app(apiServer) ++ Server.maxRequestSize(
      // This is currently arbitrary. Would like to switch to streams/chunks
      100 * 1024
    )

  def handlePostRequest(request: Request) =
    request.getBodyAsString match {

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

  private def openedEvent(event: PullRequestEvent): ZIO[Has[
    Template.Service
  ] with Blocking with Random with DependencyConverterService with Namespaces with Console with Clock with Logging, Object, Any] = {

    Files
      .createTempDirectoryManaged(
        Some(s"pr-${event.pullRequest.base.repo.name}-"),
        Seq.empty
      )
      .use { path =>
        for {
          repoDirectory <- gitCloneAndMerge(
            path,
            event.pullRequest.base.repo.owner.login,
            event.pullRequest.head.repo.name,
            event.pullRequest.head.ref,
            event.pullRequest.base.ref
          )
          nsName <- createPRNamespace(
            event.pullRequest.number,
            event.pullRequest.base.repo.name
          )
          configSource <- ZIO.fromEither(
            YamlConfigSource.fromYamlFile(
              repoDirectory./(".watcher.yaml").toFile
            )
          )
          initialRepoConfig <- ZIO.fromEither(
            read(RepoConfig.repoConfigDescriptor.from(configSource))
          )

          depsWithPaths <- RepoConfig.walkDependencies(
            initialRepoConfig,
            repoDirectory,
            event.pullRequest.head.sha,
            path
          )
          templateService <- ZIO.service[template.Template.Service]
          _ <- ZIO.foreach(depsWithPaths) {
            case (repoConfig, (path, imageTag)) =>
              for {
                _ <- log.info(s"templating $repoConfig with tag: $imageTag")
                templatedManifests <- templateService.templateManifests(
                  repoConfig,
                  path,
                  nsName,
                  imageTag
                )
                exitCode <- applyFile(templatedManifests, nsName).exitCode
              } yield repoConfig -> exitCode
          }
        } yield ()
      }
  }

  // TODO: This is very similar to the clone in DependencyConverter
  private def gitClone(repo: String, branch: String, path: ZFPath) =
    Command(
      "git",
      "clone",
      "--depth=2",
      s"--branch=$branch",
      repo,
      path.toString()
    )

  private def gitMerge(target: String) =
    Command("git", "merge", s"origin/$target")

  private def applyFile(repoDir: ZFPath, namespaceName: String) =
    Command(
      "kubectl",
      "apply",
      "-n",
      namespaceName,
      "-f",
      repoDir.toString()
    )

  // TODO: Should do this work in a temp directory that gets cleaned up later
  // TODO: These params are terrible, too many strings!
  def gitCloneAndMerge(
      workingDir: ZFPath,
      organization: String,
      repo: String,
      branch: String,
      target: String
  ): ZIO[
    Blocking with Random with Logging with Console with Clock,
    Throwable,
    ZFPath
  ] =
    for {
      folderName <- random.nextUUID
      folderPath = workingDir./(folderName.toString)
      _ <- Files.createDirectory(folderPath)
      cloneExit <- gitClone(
        s"https://github.com/$organization/$repo",
        branch,
        folderPath
      ).exitCode
      mergeExit <- if (cloneExit.code > 0)
        ZIO.fail(new Throwable(s"Could not clone repo: $repo"))
      else
        gitMerge(target).workingDirectory(folderPath.toFile).string
      _ <- log.info(s"$mergeExit")
      _ <-
//        if (mergeExit.code > 0)
//          ZIO.fail(
//            new Throwable(s"Could not merge branch $target into $branch with error: $mergeExit")
//          )
      ZIO.unit
    } yield folderPath

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
