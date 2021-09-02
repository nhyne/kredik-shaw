import zhttp.service._
import zhttp.http._
import zio._
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import dependencies.DependencyConverter.DependencyConverterService
import dependencies.DependencyWalker
import git.GitCli.GitCliService
import prom.Metrics.MetricsService
import dependencies.DependencyWalker.DependencyWalkerService
import git.Authentication.GitAuthenticationService
import template.RepoConfig
import zio.json._
import zio.logging._
import zio.console.putStrLn
import zio.config._
import zio.config.yaml.YamlConfigSource
import template.Template.TemplateService
import git.GitCli.{PullRequestAction, PullRequestEvent}
import git.{GitCli, GithubApi}
import git.GithubApi.{GithubApiService, SBackend}
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
    with GithubApiService
    with GitAuthenticationService
    with Has[SBackend]
    with Has[ApplicationConfig]

  private val apiRoot = Root / "api" / "webhook"

  private val apiServer: HttpApp[ServerEnv, HttpError] =
    HttpApp.collectM {
      case req @ Method.POST -> `apiRoot` =>
        post(req).mapBoth(
          cause =>
            HttpError.InternalServerError(cause = Some(new Throwable(cause))),
          body => Response.text(body)
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

  private def post(request: Request) =
    request.getBodyAsString match {
      case Some(body) =>
        for {
          pullRequestEvent <- ZIO.fromEither(body.fromJson[PullRequestEvent]) // TODO: Once we have multiple events coming in we'll need to try a few different ones
          _ <- performEventAction(pullRequestEvent)
            .tapError(thrown =>
              for {
                _ <- log.info(
                  s"failed to process PR event: ${pullRequestEvent.pullRequest
                    .getBaseFullName()} number: ${pullRequestEvent.pullRequest.number}"
                )
                _ <- ZIO
                  .service[GithubApi.Service] // TODO: Add accessor helpers or look at the ZIO 2.0 method of handling this?
                  .flatMap(
                    _.createComment(
                      thrown.toString,
                      pullRequestEvent.pullRequest
                    )
                  )
                  .tapError(e =>
                    log.error(
                      s"could not post comment on Pull Request: ${pullRequestEvent.pullRequest
                        .getBaseFullName()} number: ${pullRequestEvent.pullRequest.number} due to: \n $e"
                    )
                  )
              } yield ()
            )
            .forkDaemon // Forking once we have a valid body
        } yield "OK"
      case None => ZIO.fail("did not receive a request body")
    }

  private def performEventAction(event: PullRequestEvent) = { // TODO: This error type should not be an Object
    event.action match {
      case PullRequestAction.Opened      => openedEvent(event)
      case PullRequestAction.Synchronize => synchronizeAction(event)
      case PullRequestAction.Closed =>
        ZIO
          .service[Kubernetes.Service]
          .flatMap(_.deletePRNamespace(event.pullRequest))
          .mapBoth(k8sError => new Throwable(k8sError.toString), _ => ())
      case PullRequestAction.Unknown(actionType) =>
        log.warn(
          s"got unknown action type: $actionType from repo: ${event.pullRequest.head.repo} pull request number: ${event.pullRequest.number}"
        ) *> ZIO.fail(new Throwable(s"unknown action type: $actionType"))
    }
  }

  private def synchronizeAction(
      event: PullRequestEvent
  ): ZIO[ServerEnv, Throwable, Unit] = openedEvent(event)

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
          repoDirectory <- ZIO.service[GitCli.Service].flatMap { git =>
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
