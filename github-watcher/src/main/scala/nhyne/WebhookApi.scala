package nhyne
import com.coralogix.zio.k8s.client.apps.v1.deployments.Deployments
import zhttp.service._
import zhttp.http._
import zio._
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import nhyne.dependencies.DependencyConverter
import nhyne.dependencies.DependencyWalker
import nhyne.git.GitEvents.{
  ActionVerb,
  Branch,
  DeployableGitState,
  PullRequest,
  Repository,
  WebhookEvent
}
import template.{RepoConfig, Template}
import zio.json._
import zio.logging._
import zio.config._
import nhyne.config.ApplicationConfig
import zio.config.yaml.YamlConfigSource
import nhyne.git.GitEvents.WebhookEvent._
import nhyne.git.{Authentication, GitCli, GithubApi}
import nhyne.git.GithubApi.SBackend
import nhyne.kubernetes.Kubernetes
import zio.nio.file.Files
import zio.nio.core.file.{Path => ZFPath}
import nhyne.Errors._
import nhyne.prometheus.Metrics
import zio.blocking.Blocking

object WebhookApi {

  private type ServerEnv = ZEnv
    with Namespaces
    with Deployments
    with Logging
    with Has[Template]
    with Has[DependencyConverter]
    with Has[Metrics]
    with Has[GitCli]
    with Has[Kubernetes]
    with Has[DependencyWalker]
    with Has[GithubApi]
    with Has[Authentication]
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
      case Method.POST -> `apiRoot` / "from-branch" / organization / repoName / branchName =>
        fromBranch(organization, repoName, branchName).mapBoth(
          cause =>
            HttpError.InternalServerError(cause = Some(cause.toThrowable())),
          _ => Response.text("OK")
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
        val bodyType: Either[String, WebhookEvent] =
          body
            .fromJson[PullRequestEvent]
            .orElse(body.fromJson[IssueCommentEvent])
            .orElse(
              body.fromJson[LabeledEvent]
            ) // see comment around Webhook event trait
        for {
          webhookEvent <-
            ZIO.fromEither(bodyType).mapError(KredikError.GeneralError(_))
          _ <- webhookEvent match {
            case pullRequestEvent: WebhookEvent.PullRequestEvent =>
              pullRequestAction(pullRequestEvent)
                .tapError(thrown =>
                  for {
                    _ <- log.error(
                      s"failed to process PR event: ${pullRequestEvent.pullRequest.getBaseFullName}#${pullRequestEvent.pullRequest.number}: $thrown"
                    )
                    _ <-
                      ZIO
                        .service[GithubApi]
                        .flatMap(
                          _.createComment(
                            thrown.toString,
                            pullRequestEvent.pullRequest
                          )
                        )
                        .tapError(e =>
                          log.error(
                            s"could not post comment on Pull Request: ${pullRequestEvent.pullRequest.getBaseFullName}#${pullRequestEvent.pullRequest.number} due to: \n $e"
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

  // TODO: Need full functionality: delete, sync
  // TODO: This should NOT forkDaemon
  private def fromBranch(
      organization: String,
      repoName: String,
      branchName: String
  ): ZIO[ServerEnv, KredikError, Unit] = {

    val repository = Repository.fromNameAndOwner(repoName, organization)
    for {
      gitBranchSha <-
        ZIO
          .service[GithubApi]
          .flatMap(_.getBranchSha(repository, branchName))
      branch = Branch(branchName, gitBranchSha, repository)
      _ <- createTempFoldersAndProcess(
        branch,
        { case (repoDirectory, git) =>
          git.gitClone(
            repository,
            branch,
            repoDirectory
          )
        }
      )

    } yield ()
  }

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
      _ <- log.debug(
        s"rebuilding PR: ${comment.repository.fullName} ${comment.issue.prNumber}"
      )
      pr <-
        ZIO
          .service[GithubApi]
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
          .service[Kubernetes]
          .flatMap(_.deletePRNamespace(event.pullRequest))
          .mapBoth(k8sError => KredikError.K8sError(k8sError), _ => ())
      case ActionVerb.Unknown(actionType) =>
        log.warn(
          s"got unknown action type: $actionType from repo: ${event.pullRequest.head.repo} pull request number: ${event.pullRequest.number}"
        ) *> ZIO.fail(
          KredikError.GeneralError(s"unknown action type: $actionType")
        )
      case ActionVerb.Created =>
        log.warn("got invalid verb {Created} verb for Pull Request")
    }
  }

  private def synchronizedPullRequest(
      pullRequest: PullRequest
  ): ZIO[ServerEnv, KredikError, Unit] = openedPullRequest(pullRequest)

  private def createTempFoldersAndProcess(
      gitDeployable: DeployableGitState,
      cloneCommand: (
          ZFPath,
          GitCli
      ) => ZIO[Blocking, KredikError.CliError, ExitCode]
  ) = {
    Files
      .createTempDirectoryManaged(
        Some(s"pr-${gitDeployable.getBaseRepoName}-"),
        Seq.empty
      )
      .mapError(KredikError.IOError)
      .use { rootWorkingDir =>
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .mapError(KredikError.IOError)
          .use { repoDirectory =>
            for {
              gitCliService <- ZIO.service[GitCli]
              _ <- cloneCommand(repoDirectory, gitCliService)
                .tapError(e =>
                  log.error(e.stdErr.get).when(e.stdErr.nonEmpty)
                ) // TODO: Should use pretty print on kredik error type
              _ <- walkDepsAndApply(
                repoDirectory,
                rootWorkingDir,
                gitDeployable
              )
            } yield ()

          }
      }
  }

  private def openedPullRequest(
      pullRequest: PullRequest
  ): ZIO[ServerEnv, KredikError, Unit] = {
    createTempFoldersAndProcess(
      pullRequest,
      { case (repoDirectory, git) =>
        git.gitCloneAndMerge(
          pullRequest,
          repoDirectory
        )
      }
    )
  }

  private def walkDepsAndApply(
      repoDirectory: ZFPath,
      workingDirectory: ZFPath,
      gitDeployable: DeployableGitState
  ) =
    for {
      initialRepoConfig <- readConfig(repoDirectory)
        .mapError(KredikError.IOReadError)

      depsWithPaths <-
        ZIO
          .service[DependencyWalker]
          .flatMap(
            _.walkDependencies(
              initialRepoConfig,
              repoDirectory,
              gitDeployable.getSha,
              workingDirectory
            )
          )
      k8sService <- ZIO.service[Kubernetes]
      namespace <-
        k8sService
          .createPRNamespace(gitDeployable)
          .mapError(e => KredikError.K8sError(e))
      templateService <- ZIO.service[template.Template]
      _ <- ZIO.foreach_(depsWithPaths) { case (repoConfig, (path, imageTag)) =>
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
