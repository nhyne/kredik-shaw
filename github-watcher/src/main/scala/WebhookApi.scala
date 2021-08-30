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
import git.Git.GitCliService
import prom.Metrics.MetricsService
import prom.Metrics
import template.{RepoConfig, Template}
import zio.json._
import zio.logging._
import zio.config._
import zio.config.yaml.YamlConfigSource
import template.Template.TemplateService
import zio.clock.Clock
import git.Git.{
  Branch,
  PullRequestEvent,
  PullRequest,
  Repository,
  PullRequestAction
}
import git.Git
import zio.magic._
import zio.duration.Duration.fromMillis
import zio.nio.core.file.{Path => ZFPath}
import zio.nio.file.Files
import zio.random.Random
import zio.metrics.prometheus.Registry
import zio.metrics.prometheus.exporters.Exporters

object WebhookApi {

  private type ServerEnv = ZEnv
    with Namespaces
    with Logging
    with TemplateService
    with DependencyConverterService
    with MetricsService
    with GitCliService
    with Has[ApplicationConfig]

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

  def server()
      : ZIO[Has[ApplicationConfig], Nothing, Server[ServerEnv, HttpError]] = {
    for {
      port <- ZIO.service[ApplicationConfig].map(_.port)
    } yield Server.port(port) ++ Server.app(apiServer) ++ Server.maxRequestSize(
      // This is currently arbitrary. Would like to switch to streams/chunks
      100 * 1024
    )
  }

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

  private def openedEvent(
      event: PullRequestEvent
  ): ZIO[ServerEnv, Object, Any] = {

    Files
      .createTempDirectoryManaged(
        Some(s"pr-${event.pullRequest.base.repo.name}-"),
        Seq.empty
      )
      .use { path =>
        for {
          repoDirectory <- ZIO.service[Git.Service].flatMap { git =>
            git.gitCloneAndMerge(
              event.pullRequest.head.repo,
              event.pullRequest.head,
              event.pullRequest.base,
              path
            )
          }
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

  private def applyFile(repoDir: ZFPath, namespaceName: String) =
    Command(
      "kubectl",
      "apply",
      "-n",
      namespaceName,
      "-f",
      repoDir.toString()
    )

  private def createPRNamespace(
      prNumber: Int,
      repo: String
  ): ZIO[Namespaces with MetricsService with Logging, K8sFailure, String] = {
    val (nsName, prNamespace) = namespaceObject(prNumber, repo)
    for {
      namespace <- get(nsName)
        .foldM({
          case K8sNotFound => create(prNamespace)
          case e           => ZIO.fail(e)
        }, success => ZIO.succeed(success))
        .map(_ => nsName)
      _ <- ZIO.service[Metrics.Service].flatMap(_.namespaceCreated()).catchAll {
        e =>
          log.error(e.toString) *> ZIO.unit
      }
    } yield namespace
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

}
