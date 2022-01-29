package nhyne

import com.coralogix.zio.k8s.client.apps.v1.deployments.Deployments
import zhttp.service._
import zhttp.http._
import zio._
import com.coralogix.zio.k8s.client.v1.namespaces.Namespaces
import io.github.vigoo.zioaws.secretsmanager.SecretsManager
import nhyne.dependencies.DependencyConverter
import nhyne.dependencies.DependencyWalker
import nhyne.git.GitEvents.{ Branch, DeployableGitState, PullRequest, Repository, WebhookEvent }
import template.{ RepoConfig, Template }
import zio.json._
import zio.logging._
import zio.config._
import nhyne.config.ApplicationConfig
import zio.config.yaml.YamlConfigSource
import nhyne.git.GitEvents.WebhookEvent._
import nhyne.git.{ Authentication, GitCli, GithubApi }
import nhyne.git.GithubApi.SBackend
import nhyne.kubernetes.Kubernetes
import zio.nio.file.Files
import zio.nio.core.file.{ Path => ZFPath }
import nhyne.Errors._
import nhyne.prometheus.Metrics
import nhyne.secrets.Secrets
import nhyne.template.RepoConfig.ImageTag
import zio.blocking.Blocking

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
    with Has[Secrets]
    with Has[SBackend]
    with Has[ApplicationConfig]
    with SecretsManager

  private val apiRoot = Root / "api" / "webhook"

  // TODO: health and ready endpoints
  private val apiServer: HttpApp[ServerEnv, HttpError] =
    HttpApp.collectM {
      case req @ Method.POST -> `apiRoot` / "github"                                       =>
        githubWebhookPost(req).mapBoth(
          {
            case cause @ KredikError.InvalidSignature => HttpError.Unauthorized(cause.toString)
            case cause                                =>
              HttpError
                .InternalServerError(cause = Some(cause.toThrowable()))
          },
          body => Response.text(body)
        )
      case Method.POST -> `apiRoot` / "from-branch" / organization / repoName / branchName =>
        fromBranch(organization, repoName, branchName).mapBoth(
          cause => HttpError.InternalServerError(cause = Some(cause.toThrowable())),
          _ => Response.text("OK")
        )
      case Method.GET -> Root / "health"                                                   => ZIO.succeed(Response.text("OK"))
      case Method.GET -> Root / "live"                                                     => ZIO.succeed(Response.text("OK"))
    }

  def server(): ZIO[Has[ApplicationConfig], Nothing, Server[ServerEnv, HttpError]] =
    ZIO.service[ApplicationConfig].map(_.port).flatMap { port =>
      ZIO.succeed(
        Server.port(port) ++ Server.app(apiServer) ++ Server.maxRequestSize(
          // TODO: This is currently arbitrary. Would like to switch to streams/chunks
          100 * 1024
        )
      )
    }

  // TODO: Clean this method up
  private def githubWebhookPost(
    request: Request
  ): ZIO[ServerEnv, KredikError, String] = {
    def validateSecretHeader(
      repository: Repository
    ): ZIO[ServerEnv, KredikError, Unit] =
      for {
        secretString <- ZIO.service[Secrets].flatMap(_.readSecret(repository))
        correctSha   <- ZIO
                          .fromOption(
                            request
                              .getHeader("X-Hub-Signature-256")
                              .map { h =>
                                val secret                  =
                                  new SecretKeySpec(secretString.getBytes("UTF-8"), "SHA256")
                                val mac                     = Mac.getInstance("HMACSHA256")
                                mac.init(secret)
                                val hashString: Array[Byte] =
                                  mac.doFinal(request.getBodyAsString.get.getBytes("UTF-8"))
                                val macOne                  = hashString.map("%02x".format(_)).mkString

                                // TODO: This is a security issue. A timing attack is possible, should switch to a constant time equal check
                                val correctSha = s"sha256=$macOne" == h.value

                                ZIO.fail(KredikError.InvalidSignature).unless(correctSha)

                              }
                          )
                          .orElseFail(KredikError.GeneralError("missing sha256 header"))
        _            <- correctSha
      } yield ()

    request.getBodyAsString match {
      case Some(body) =>
        val bodyType: Either[String, WebhookEvent] =
          body
            .fromJson[PullRequestEvent]
            .orElse(body.fromJson[IssueCommentEvent]) // see comment around Webhook event trait
        for {

          webhookEvent <- ZIO.fromEither(bodyType).mapError(KredikError.GeneralError(_))
          _            <- validateSecretHeader(webhookEvent.baseRepo()).tapError(e => log.error(s"invalid secret sha256: $e"))
          _            <- webhookEvent match {
                            case _: WebhookEvent.PullRequestEvent                  => ZIO.unit
                            case issueCommentEvent: WebhookEvent.IssueCommentEvent =>
                              commentAction(issueCommentEvent)
                                .tapError(thrown =>
                                  for {

                                    ghApi <- ZIO.service[GithubApi]
                                    _     <-
                                      log.error(
                                        s"failed to process comment on ${issueCommentEvent.repository.fullName}: `${issueCommentEvent
                                          .getBody()}`: $thrown"
                                      )
                                    pr    <- ghApi
                                               .getPullRequest(issueCommentEvent.repository, issueCommentEvent.issue.prNumber)
                                               .tapError(err => log.error(err.toString))
                                    _     <- ghApi
                                               .createComment(thrown.toThrowable().toString, pr)
                                               .tapError(e => log.error(s"could not comment on pull request: $e"))

                                  } yield ()
                                // TODO: Need to comment the error back
                                )
                                .forkDaemon
                          }
        } yield "OK"

      case None       =>
        ZIO.fail(
          KredikError.GeneralError("did not receive a request body")
        )
    }
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
      gitBranchSha <- ZIO
                        .service[GithubApi]
                        .flatMap(_.getBranchSha(repository, branchName))
      branch        = Branch(branchName, gitBranchSha, repository)
      _            <- createTempFoldersAndProcess(
                        branch,
                        {
                          case (repoDirectory, git) =>
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
  ): ZIO[ServerEnv, KredikError, Unit] =
    for {
      commentPrefix <- ZIO.service[ApplicationConfig].map(_.commentPrefix)
      pr            <- ZIO
                         .service[GithubApi]
                         .flatMap(
                           _.getPullRequest(comment.repository, comment.issue.prNumber)
                         )
                         .tapError(err => log.error(err.toString))
      commentAction  = CommentAction(comment.getBody(), commentPrefix).map {
                         case CommentAction.Rebuild              => synchronizedPullRequest(pr)
                         case CommentAction.Build(imageTag)      =>
                           ZIO.fail(
                             KredikError.GeneralError(
                               s"unimplemented comment command: build $imageTag"
                             )
                           )
                         case CommentAction.Destroy              =>
                           ZIO
                             .service[Kubernetes]
                             .flatMap(_.deletePRNamespace(pr))
                             .mapError(KredikError.K8sError)
                         case CommentAction.Unknown(commentBody) =>
                           ZIO.fail(
                             KredikError.GeneralError(s"invalid comment action: $commentBody")
                           )
                       }
                         .getOrElse(ZIO.unit)
      _             <- commentAction
    } yield ()

  sealed trait CommentAction

  object CommentAction {
    case object Rebuild extends CommentAction

    final case class Build(imageTag: ImageTag) extends CommentAction

    case object Destroy extends CommentAction

    final case class Unknown(commentBody: String) extends CommentAction

    def apply(commentBody: String, prefix: String): Option[CommentAction] =
      if (commentBody.startsWith(prefix))
        commentBody.drop(prefix.length + 1).split(" ").toList match {
          case Nil                           => None
          case "build" :: imageTag :: _      => Some(Build(ImageTag(imageTag)))
          case "rebuild" :: _ | "build" :: _ => Some(Rebuild)
          case "destroy" :: _                => Some(Destroy)
          case _                             => Some(Unknown(commentBody))
        }
      else None

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
  ) =
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
              _             <- cloneCommand(repoDirectory, gitCliService)
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

  private def openedPullRequest(
    pullRequest: PullRequest
  ): ZIO[ServerEnv, KredikError, Unit] =
    createTempFoldersAndProcess(
      pullRequest,
      {
        case (repoDirectory, git) =>
          git.gitCloneAndMerge(
            pullRequest,
            repoDirectory
          )
      }
    )

  private def walkDepsAndApply(
    repoDirectory: ZFPath,
    workingDirectory: ZFPath,
    gitDeployable: DeployableGitState
  ) =
    for {
      initialRepoConfig <- readConfig(repoDirectory)
                             .mapError(KredikError.IOReadError)
      commentPrefix     <- ZIO.service[ApplicationConfig].map(_.commentPrefix).map(_.toUpperCase)
      depsWithPaths     <- ZIO
                             .service[DependencyWalker]
                             .flatMap(
                               _.walkDependencies(
                                 initialRepoConfig,
                                 repoDirectory,
                                 gitDeployable.getSha,
                                 workingDirectory
                               )
                             )
      k8sService        <- ZIO.service[Kubernetes]
      namespace         <- k8sService
                             .createPRNamespace(gitDeployable)
      envVars            = Map(
                             s"${commentPrefix}_ENVIRONMENT" -> "TRUE",
                             s"${commentPrefix}_IMAGE_TAG"   -> gitDeployable.getSha,
                             s"${commentPrefix}_NAMESPACE"   -> namespace.value
                           )
      templateService   <- ZIO.service[template.Template]
      _                 <- ZIO.foreach_(depsWithPaths) {
                             case (repoConfig, (path, imageTag)) =>
                               for {
                                 _                  <- log.info(s"templating $repoConfig with tag: $imageTag")
                                 templatedManifests <- templateService.templateManifests(
                                                         repoConfig,
                                                         path,
                                                         namespace,
                                                         envVars,
                                                         imageTag
                                                       )
                                 exitCode           <- k8sService
                                                         .applyFile(templatedManifests, namespace)
                               } yield repoConfig -> exitCode
                           }
      _                 <- templateService
                             .injectEnvVarsIntoDeployments(
                               namespace,
                               envVars
                             )
                             .tapError(e => log.error(e.toString))
                             .mapError(KredikError.K8sError)
    } yield ()

  private def readConfig(repoDirectory: ZFPath) =
    for {
      configFileName    <- ZIO.service[ApplicationConfig].map(_.configFileName)
      configSource      <- ZIO.fromEither(
                             YamlConfigSource.fromYamlFile(
                               repoDirectory./(configFileName).toFile
                             )
                           )
      initialRepoConfig <- ZIO.fromEither(
                             read(RepoConfig.repoConfigDescriptor.from(configSource))
                           )
    } yield initialRepoConfig

}
