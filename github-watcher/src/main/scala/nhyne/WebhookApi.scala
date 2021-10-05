package nhyne
import com.coralogix.zio.k8s.client.apps.v1.deployments.Deployments
import zhttp.service._
import zhttp.http._
import zio._
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import nhyne.dependencies.DependencyConverter.DependencyConverterService
import nhyne.dependencies.DependencyWalker
import nhyne.git.GitCli.GitCliService
import nhyne.prometheus.Metrics.MetricsService
import nhyne.dependencies.DependencyWalker.DependencyWalkerService
import nhyne.git.Authentication.GitAuthenticationService
import nhyne.git.GitEvents.{ActionVerb, PullRequest, WebhookEvent}
import template.RepoConfig
import zio.json._
import zio.logging._
import zio.config._
import nhyne.config.ApplicationConfig
import zio.config.yaml.YamlConfigSource
import nhyne.template.Template.TemplateService
import nhyne.git.GitEvents.WebhookEvent._
import nhyne.git.{GitCli, GithubApi}
import nhyne.git.GithubApi.{GithubApiService, SBackend}
import nhyne.kubernetes.Kubernetes
import nhyne.kubernetes.Kubernetes.KubernetesService
import zio.nio.file.Files
import zio.nio.core.file.{Path => ZFPath}
import nhyne.Errors._

object WebhookApi {

  private type ServerEnv = ZEnv
    with Namespaces
    with Deployments
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

  // TODO: health and ready endpoints
  private val apiServer: HttpApp[ServerEnv, HttpError] =
    HttpApp.collectM {
      case req @ Method.POST -> `apiRoot` =>
        githubWebhookPost(req).mapBoth(
          cause =>
            HttpError.InternalServerError(cause = Some(cause.toThrowable())),
          body => Response.text(body)
        )
      case Method.POST -> `apiRoot` / "from-branch" / branchName =>
        fromBranch(branchName).mapBoth(
          cause =>
            HttpError.InternalServerError(cause = Some(cause.toThrowable())),
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

  private def githubWebhookPost(
      request: Request
  ): ZIO[ServerEnv, KredikError, String] =
    request.getBodyAsString match {
      case Some(body) => {
        val thing: Either[String, WebhookEvent] =
          body
            .fromJson[PullRequestEvent]
            .orElse(body.fromJson[IssueCommentEvent])
            .orElse(
              body.fromJson[LabeledEvent]
            ) // see comment around Webhook event trait
        for {
          webhookEvent <-
            ZIO.fromEither(thing).mapError(KredikError.GeneralError(_))
          _ <- webhookEvent match {
            case pullRequestEvent: WebhookEvent.PullRequestEvent =>
              pullRequestAction(pullRequestEvent)
                .tapError(thrown =>
                  for {
                    _ <- log.error(
                      s"failed to process PR event: ${pullRequestEvent.pullRequest
                        .getBaseFullName()}#${pullRequestEvent.pullRequest.number}: $thrown"
                    )
                    _ <-
                      ZIO
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
                              .getBaseFullName()}#${pullRequestEvent.pullRequest.number} due to: \n $e"
                          )
                        )
                  } yield ()
                )
                .forkDaemon // Forking once we have a valid body
            case issueCommentEvent: WebhookEvent.IssueCommentEvent =>
              commentAction(issueCommentEvent).forkDaemon
            case _ => ??? // TODO: Add label event logic -- do we need it?
          }
        } yield "OK"
      }
      case None =>
        ZIO.fail(KredikError.GeneralError("did not receive a request body"))
    }

  private def fromBranch(
      branchName: String
  ): ZIO[ServerEnv, KredikError, String] = ???

  // TODO: Should come up with a series of commands
  // TODO: Going to need some basic parsing? Would be nice to have `sync <image tag>`
  /*
   * rebuild: just redoes templating and applies
   * restart: deletes namespace and then rebuilds
   * destroy: destroys namespace
   * status: gets status of object? -- could do a status: deployments which would get the status of all deploys in the namespace and comment them
   */
  private def commentAction(
      comment: IssueCommentEvent
  ): ZIO[ServerEnv, KredikError, Unit] = {
    (for {
      _ <- log.info(
        s"rebuilding PR: ${comment.repository.fullName} ${comment.issue.prNumber}"
      )
      pr <-
        ZIO
          .service[GithubApi.Service]
          .flatMap(
            _.getPullRequest(comment.repository, comment.issue.prNumber)
          )
          .tapError(err => log.error(err.toString))
      _ <- openedPullRequest(pr)
    } yield ()).when(comment.getBody() == "rebuild")
  }

  private def pullRequestAction(
      event: PullRequestEvent
  ): ZIO[ServerEnv, KredikError, Unit] = {
    event.action match {
      case ActionVerb.Opened      => openedPullRequest(event.pullRequest)
      case ActionVerb.Synchronize => synchronizedPullRequest(event.pullRequest)
      case ActionVerb.Closed =>
        ZIO
          .service[Kubernetes.Service]
          .flatMap(_.deletePRNamespace(event.pullRequest))
          .mapBoth(k8sError => KredikError.K8sError(k8sError), _ => ())
      case ActionVerb.Unknown(actionType) =>
        log.warn(
          s"got unknown action type: $actionType from repo: ${event.pullRequest.head.repo} pull request number: ${event.pullRequest.number}"
        ) *> ZIO.fail(
          KredikError.GeneralError(
            new Throwable(s"unknown action type: $actionType")
          )
        )
      case ActionVerb.Created =>
        log.warn("got invalid verb {Created} verb for Pull Request")
    }
  }

  private def synchronizedPullRequest(
      pullRequest: PullRequest
  ): ZIO[ServerEnv, KredikError, Unit] = openedPullRequest(pullRequest)

  private def openedPullRequest(
      pullRequest: PullRequest
  ): ZIO[ServerEnv, KredikError, Unit] = {
    // We create two temp directories here because the dependencies do not get put into their own temp directories
    //   The deps get cloned into the first temp dir here and are cleaned up when it gets cleaned up
    //   It would be much better if the deps were put into their own managed temp dir but I have not had time to investigate
    //      using a managed multiple times yet
    Files
      .createTempDirectoryManaged(
        Some(s"pr-${pullRequest.base.repo.name}-"),
        Seq.empty
      )
      .mapError(KredikError.IOError)
      .use { path =>
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .mapError(KredikError.IOError)
          .use { repoDirectory =>
            for {
              _ <-
                ZIO
                  .service[GitCli.Service]
                  .flatMap { git =>
                    git.gitCloneAndMerge(
                      pullRequest,
                      repoDirectory
                    )
                  }
                  .tapError(e =>
                    log.error(e.stdErr.getOrElse(""))
                  ) // TODO: Should use pretty print on kredik error type
              initialRepoConfig <- readConfig(repoDirectory)
                .mapError(KredikError.IOReadError)

              depsWithPaths <-
                ZIO
                  .service[DependencyWalker.Service]
                  .flatMap(
                    _.walkDependencies(
                      initialRepoConfig,
                      repoDirectory,
                      pullRequest.head.sha,
                      path
                    )
                  )
              k8sService <- ZIO.service[Kubernetes.Service]
              namespace <-
                k8sService
                  .createPRNamespace(pullRequest)
                  .mapError(e => KredikError.K8sError(e))
              templateService <- ZIO.service[template.Template.Service]
              _ <- ZIO.foreach_(depsWithPaths) {
                case (repoConfig, (path, imageTag)) =>
                  for {
                    _ <- log.info(s"templating $repoConfig with tag: $imageTag")
                    templatedManifests <- templateService.templateManifests(
                      repoConfig,
                      path,
                      namespace,
                      imageTag
                    )
                    exitCode <-
                      k8sService
                        .applyFile(templatedManifests, namespace)
                  } yield repoConfig -> exitCode
              }
              _ <-
                templateService
                  .injectEnvVarsIntoDeployments(
                    namespace,
                    Map("PR_ENVIRONMENT" -> "TRUE")
                  )
                  .tapError(e => log.error(e.toString))
                  .mapError(KredikError.K8sError)
            } yield ()
          }
      }
  }
  private def readConfig(repoDirectory: ZFPath) =
    for {
      configSource <- ZIO.fromEither(
        YamlConfigSource.fromYamlFile(
          repoDirectory./(".watcher.yaml").toFile
        )
      )
      initialRepoConfig <- ZIO.fromEither(
        read(RepoConfig.repoConfigDescriptor.from(configSource))
      )
    } yield initialRepoConfig

}
