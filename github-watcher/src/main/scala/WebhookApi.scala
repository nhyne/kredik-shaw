import zhttp.service._
import zhttp.http._
import zio._
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import dependencies.DependencyConverter.DependencyConverterService
import dependencies.DependencyWalker
import git.Git.GitCliService
import prom.Metrics.MetricsService
import dependencies.DependencyWalker.DependencyWalkerService
import template.RepoConfig
import zio.json._
import zio.logging._
import zio.config._
import zio.config.yaml.YamlConfigSource
import template.Template.TemplateService
import git.Git.{PullRequestAction, PullRequestEvent}
import git.Git
import kubernetes.Kubernetes
import kubernetes.Kubernetes.KubernetesService
import zio.duration.Duration.fromMillis
import zio.nio.file.Files

object WebhookApi {

  private type ServerEnv = ZEnv
    with Namespaces
    with Logging
    with TemplateService
    with DependencyConverterService
    with MetricsService
    with GitCliService
    with KubernetesService
    with DependencyWalkerService
    with Has[ApplicationConfig]

  private val apiRoot = Root / "api" / "ahab-webhook"

  private val apiServer: HttpApp[ServerEnv, HttpError] =
    HttpApp.collectM {
      case req @ Method.POST -> `apiRoot` =>
        ahabPost(req).mapBoth(
          cause =>
            // TODO: Make this cleaner
            HttpError
              .InternalServerError(cause = Some(new Throwable(cause.toString))),
          body => Response.text(body.toString)
        )
    }

  def server()
      : ZIO[Has[ApplicationConfig], Nothing, Server[ServerEnv, HttpError]] = {
    ZIO.service[ApplicationConfig].map(_.port).flatMap { port =>
      ZIO.succeed(
        Server.port(port) ++ Server.app(apiServer) ++ Server.maxRequestSize(
          // This is currently arbitrary. Would like to switch to streams/chunks
          100 * 1024
        )
      )
    }
  }

  private def ahabPost(request: Request) =
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
      case PullRequestAction.Closed =>
        ZIO
          .service[Kubernetes.Service]
          .flatMap(_.deletePRNamespace(event.pullRequest))
      case PullRequestAction.Unknown(actionType) =>
        log.warn(s"got unknown action type: $actionType")
    }
  }

  private def openedEvent(
      event: PullRequestEvent
  ): ZIO[ServerEnv, Throwable, Unit] = {
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
          k8sService <- ZIO.service[Kubernetes.Service]
          nsName <- k8sService
            .createPRNamespace(event.pullRequest)
            .mapError(e => new Throwable(e.toString))
          configSource <- ZIO.fromEither(
            YamlConfigSource.fromYamlFile(
              repoDirectory./(".watcher.yaml").toFile
            )
          )
          initialRepoConfig <- ZIO.fromEither(
            read(RepoConfig.repoConfigDescriptor.from(configSource))
          )

          depsWithPaths <- ZIO
            .service[DependencyWalker.Service]
            .flatMap(
              _.walkDependencies(
                initialRepoConfig,
                repoDirectory,
                event.pullRequest.head.sha,
                path
              )
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
                exitCode <- k8sService
                  .applyFile(templatedManifests, nsName)
                  .exitCode
              } yield repoConfig -> exitCode
          }
        } yield ()
      }
  }
}
