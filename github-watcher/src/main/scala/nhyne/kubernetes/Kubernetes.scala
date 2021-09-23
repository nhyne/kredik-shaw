package nhyne.kubernetes

import com.coralogix.zio.k8s.client.model.{K8sNamespace, PropagationPolicy}
import com.coralogix.zio.k8s.client.{K8sFailure, NotFound}
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
  Status
}
import nhyne.git.GitEvents.PullRequest
import nhyne.prometheus.Metrics
import nhyne.prometheus.Metrics.MetricsService
import zio.nio.core.file.Path
import zio.{ExitCode, Has, ZIO, ZLayer}
import zio.blocking.Blocking
import zio.logging.{Logging, log}
import zio.process.{Command, CommandError}
import nhyne.WebhookApi.{KredikError, commandToKredikExitCode}

object Kubernetes {

  type KubernetesService = Has[Service]
  trait Service {
    def applyFile(
        directory: Path,
        namespace: K8sNamespace
    ): ZIO[Blocking, KredikError, ExitCode]
    def createPRNamespace(
        pullRequest: PullRequest
    ): ZIO[
      Namespaces with MetricsService with Logging,
      K8sFailure,
      K8sNamespace
    ]
    def deletePRNamespace(
        pullRequest: PullRequest
    ): ZIO[Logging with Namespaces, K8sFailure, Status]
  }

  val live = ZLayer.succeed(new Service {
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
        pullRequest: PullRequest
    ): ZIO[
      Namespaces with MetricsService with Logging,
      K8sFailure,
      K8sNamespace
    ] = {
      val (nsName, prNamespace) = namespaceObject(pullRequest)
      for {
        namespace <- get(nsName)
          .foldM(
            {
              case NotFound =>
                create(prNamespace).flatMap(_ =>
                  ZIO
                    .service[Metrics.Service]
                    .flatMap(_.namespaceCreated(pullRequest.getBaseFullName()))
                    .catchAll { e =>
                      log.error(e.toString) *> ZIO.unit
                    }
                )
              case e => ZIO.fail(e)
            },
            success => ZIO.succeed(success)
          )
          .map(_ => K8sNamespace(nsName))
      } yield namespace
    }

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
          case NotFound =>
            log.warn(
              s"attempting to delete namespace: $nsName that does not exist"
            ) *> ZIO.succeed(
              Status(code = 200, message = s"namespace $nsName did not exist")
            )
          case f =>
            log.warn(s"failed to delete ns: $nsName with error: $f") *> ZIO
              .fail(
                f
              )
        },
        status => ZIO.succeed(status)
      )
    }
  })

  def namespaceName(pullRequest: PullRequest): String = {
    // TODO: This format will be bad for long repo names or those that start similarly
    // TODO: Should start with `pr-` or something standard for RBAC permissions
    s"${pullRequest.head.repo.name}-pr-${pullRequest.number}".trim
      .take(63) // max length of namespace
  }
  private def namespaceObject(pullRequest: PullRequest): (String, Namespace) = {
    val nsName = namespaceName(pullRequest)
    (nsName, Namespace(metadata = ObjectMeta(name = Some(nsName))))
  }

}
