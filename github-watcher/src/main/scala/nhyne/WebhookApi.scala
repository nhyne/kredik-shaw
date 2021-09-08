package nhyne
import zhttp.service._
import zhttp.http._
import zio._
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import nhyne.dependencies.DependencyConverter.DependencyConverterService
import nhyne.dependencies.DependencyWalker
import nhyne.git.GitCli.GitCliService
import nhyne.prom.Metrics.MetricsService
import nhyne.dependencies.DependencyWalker.DependencyWalkerService
import nhyne.git.Authentication.GitAuthenticationService
import nhyne.git.GitEvents.{ActionVerb, PullRequest, WebhookEvent}
import template.RepoConfig
import zio.json._
import zio.logging._
import zio.config._
import zio.console.putStrLn
import nhyne.config.ApplicationConfig
import zio.config.yaml.YamlConfigSource
import nhyne.template.Template.TemplateService
import nhyne.git.GitEvents.WebhookEvent._
import nhyne.git.{GitCli, GithubApi}
import nhyne.git.GithubApi.{GithubApiService, SBackend}
import nhyne.kubernetes.Kubernetes
import nhyne.kubernetes.Kubernetes.KubernetesService
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

  private def post(request: Request): ZIO[ServerEnv, Throwable, String] =
    request.getBodyAsString match {
      case Some(body) => {
        val thing: Either[String, WebhookEvent] =
          body
            .fromJson[PullRequestEvent]
            .orElse(body.fromJson[IssueCommentEvent])
            .orElse(body.fromJson[LabeledEvent]) // see comment around Webhook event trait
        for {
          webhookEvent <- ZIO.fromEither(thing).mapError(new Throwable(_))
          _ <- webhookEvent match {
            case pullRequestEvent: WebhookEvent.PullRequestEvent =>
              pullRequestAction(pullRequestEvent)
                .tapError(thrown =>
                  for {
                    _ <- log.info(
                      s"failed to process PR event: ${pullRequestEvent.pullRequest
                        .getBaseFullName()} number: ${pullRequestEvent.pullRequest.number}"
                    )
                    _ <- ZIO
                      .service[GithubApi.Service]
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
            case issueCommentEvent: WebhookEvent.IssueCommentEvent =>
              commentAction(issueCommentEvent).forkDaemon
            case labelEvent: WebhookEvent.LabeledEvent => ???
          }
        } yield "OK"
      }
      case None => ZIO.fail(new Throwable("did not receive a request body"))
    }

  private def commentAction(comment: IssueCommentEvent) = {
    if (comment.getBody() == "rebuild") {
      for {
        pr <- ZIO
          .service[GithubApi.Service]
          .flatMap(_.getPullRequest(comment.repository, comment.issue.prNumber))
          .tapError(err => log.error(err.toString))
        _ <- openedPullRequest(pr)
      } yield ()
    } else {
      ZIO.unit
    }
  }

  private def pullRequestAction(
      event: PullRequestEvent
  ): ZIO[ServerEnv, Throwable, Unit] = {
    event.action match {
      case ActionVerb.Opened      => openedPullRequest(event.pullRequest)
      case ActionVerb.Synchronize => synchronizedPullRequest(event.pullRequest)
      case ActionVerb.Closed =>
        ZIO
          .service[Kubernetes.Service]
          .flatMap(_.deletePRNamespace(event.pullRequest))
          .mapBoth(k8sError => new Throwable(k8sError.toString), _ => ())
      case ActionVerb.Unknown(actionType) =>
        log.warn(
          s"got unknown action type: $actionType from repo: ${event.pullRequest.head.repo} pull request number: ${event.pullRequest.number}"
        ) *> ZIO.fail(new Throwable(s"unknown action type: $actionType"))
      case ActionVerb.Created =>
        log.warn("got invalid verb {Created} verb for Pull Request")
    }
  }

  private def synchronizedPullRequest(
      pullRequest: PullRequest
  ): ZIO[ServerEnv, Throwable, Unit] = openedPullRequest(pullRequest)

  private def openedPullRequest(
      pullRequest: PullRequest
  ): ZIO[ServerEnv, Throwable, Unit] = {
    Files
      .createTempDirectoryManaged(
        Some(s"pr-${pullRequest.base.repo.name}-"),
        Seq.empty
      )
      .use { path =>
        for {
          repoDirectory <- ZIO.service[GitCli.Service].flatMap { git =>
            git.gitCloneAndMerge(
              pullRequest,
              path
            )
          }
          k8sService <- ZIO.service[Kubernetes.Service]
          nsName <- k8sService
            .createPRNamespace(pullRequest)
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
                pullRequest.head.sha,
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
