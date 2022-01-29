package nhyne.kubernetes

import com.coralogix.zio.k8s.client.model.{ K8sNamespace, PropagationPolicy }
import com.coralogix.zio.k8s.client.{ DeserializationFailure, K8sFailure, NotFound }
import com.coralogix.zio.k8s.client.v1.namespaces.{ create, delete, get, replace, Namespaces }
import com.coralogix.zio.k8s.model.core.v1.Namespace
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.{ DeleteOptions, ObjectMeta, Status }
import nhyne.git.GitEvents.{ Branch, DeployableGitState, PullRequest }
import nhyne.prometheus.Metrics
import zio.nio.file.Path
import zio.clock._
import zio.{ ExitCode, Has, ZIO, ZLayer }
import zio.blocking.Blocking
import zio.logging.{ log, Logging }
import zio.process.Command
import nhyne.Errors.KredikError
import nhyne.CommandWrapper.commandToKredikExitCode
import nhyne.config.ApplicationConfig

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

trait Kubernetes  {
  def applyFile(
    directory: Path,
    namespace: K8sNamespace
  ): ZIO[Blocking, KredikError, ExitCode]
  def createPRNamespace(
    pullRequest: DeployableGitState
  ): ZIO[
    Namespaces with Metrics with ApplicationConfig with Logging with Clock,
    KredikError,
    K8sNamespace
  ]
  def deletePRNamespace(
    pullRequest: PullRequest
  ): ZIO[Logging with Namespaces, K8sFailure, Status]
}
object Kubernetes {

  val live = ZLayer.succeed(new Kubernetes {
    override def applyFile(
      directory: Path,
      namespace: K8sNamespace
      // TODO: This TemplateError should be moved to a more common location
    ): ZIO[Blocking, KredikError, ExitCode] =
      commandToKredikExitCode(
        Command(
          "kubectl",
          "apply",
          "-n",
          namespace.value,
          "-f",
          directory.toString()
        )
      )

    override def createPRNamespace(
      pullRequest: DeployableGitState
    ): ZIO[
      Namespaces with Metrics with ApplicationConfig with Logging with Clock,
      KredikError,
      K8sNamespace
    ] =
      for {
        commentPrefix        <- ZIO.service[ApplicationConfig].map(_.commentPrefix)
        time                 <- currentDateTime.mapError(KredikError.GeneralError(_))
        (nsName, prNamespace) = namespaceObject(pullRequest, commentPrefix, time)
        namespace            <- get(nsName)
                                  .foldM(
                                    {
                                      case NotFound =>
                                        create(prNamespace) *> {
                                          ZIO
                                            .service[Metrics]
                                            .flatMap(_.namespaceCreated(pullRequest.getBaseFullName))
                                            .catchAll { e =>
                                              log.error(e.toString).unit
                                            }
                                        }
                                      case e        => ZIO.fail(e)
                                    },
                                    success => ZIO.succeed(success)
                                  )
                                  .mapBoth(KredikError.K8sError, _ => K8sNamespace(nsName))
        _                    <- replace(nsName, prNamespace).mapError(KredikError.K8sError)

      } yield namespace

    // TODO: Have an error with deleting namespaces (Deserialization error)
    //  The actual namespace does get deleted but we're logging an error + returning 500
    override def deletePRNamespace(
      pullRequest: PullRequest
    ): ZIO[Logging with Namespaces, K8sFailure, Status] = {
      val nsName = namespaceName(pullRequest)
      delete(
        nsName,
        DeleteOptions(propagationPolicy = "Background"),
        propagationPolicy = Some(PropagationPolicy.Background)
      ).foldM(
        {
          case NotFound                          =>
            log
              .warn(
                s"attempting to delete namespace: $nsName that does not exist"
              )
              .as(
                Status(code = 200, message = s"namespace $nsName did not exist")
              )
          case dsFailure: DeserializationFailure =>
            log
              .warn(s"deserialization error from k8s master on: $nsName ns deletion ${dsFailure.error}")
              .as(
                Status(code = 200, message = s"namespace $nsName deleted")
              )
          case f                                 =>
            log.error(s"failed to delete ns: $nsName with error: $f") *> ZIO
              .fail(
                f
              )
        },
        status => ZIO.succeed(status)
      )
    }
  })

  def namespaceName(gitState: DeployableGitState): String = {
    val namespace = gitState match {
      case pr: PullRequest => s"pr-${pr.head.repo.name}-${pr.number}"
      case branch: Branch  =>
        s"branch-${branch.repo.name}-${branch.ref}" // TODO: Should this have unique ID? -- Name of user?
    }
    namespace.trim.take(63) // max length of namespace
  }
  private def namespaceObject(
    gitState: DeployableGitState,
    prefix: String,
    updatedTimestamp: OffsetDateTime
  ): (String, Namespace) = {
    val formatter = DateTimeFormatter.ISO_DATE_TIME
    val timestamp = formatter.format(updatedTimestamp).replace(':', '_') // K8s labels cannot have `:`s
    val nsName    = namespaceName(gitState)
    (
      nsName,
      Namespace(metadata =
        ObjectMeta(
          name = Some(nsName),
          labels = Map(prefix -> "true", s"$prefix-last-updated" -> timestamp)
        )
      )
    )
  }

}
