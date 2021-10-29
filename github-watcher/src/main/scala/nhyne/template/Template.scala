package nhyne.template

import nhyne.CommandWrapper.commandToKredikString
import com.coralogix.zio.k8s.client.K8sFailure
import nhyne.template.RepoConfig.ImageTag
import zio.process._
import zio._
import zio.blocking.Blocking
import zio.nio.channels.FileChannel
import zio.nio.core.file.Path
import zio.nio.file.Files

import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import com.coralogix.zio.k8s.client.apps.v1.deployments.{ getAll, replace, Deployments }
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.model.apps.v1.Deployment
import com.coralogix.zio.k8s.model.core.v1.EnvVar
import nhyne.Errors.KredikError
import nhyne.config.ApplicationConfig

trait Template  {
  def templateManifests(
    repoConfig: RepoConfig,
    repoFolder: Path,
    namespace: K8sNamespace,
    environmentVars: Map[String, String],
    imageTag: ImageTag
  ): ZIO[Blocking with Has[ApplicationConfig], KredikError, Path]

  def injectEnvVarsIntoDeployments(
    namespace: K8sNamespace,
    envVars: Map[String, String]
  ): ZIO[Deployments, K8sFailure, Unit]
}
object Template {

  sealed trait TemplateCommand

  object TemplateCommand {
    case object Helm          extends TemplateCommand
    case object Kustomize     extends TemplateCommand
    case object KustomizeHelm extends TemplateCommand
  }

  private def kustomizeCommand(path: Path) =
    Command("kustomize", "build", path.toString())

  private def kustomizeHelmCommand(path: Path) =
    Command("kustomize", "build", "--enable-helm", path.toString())

  private def helmCommand(dir: Path) = ???

  def template(
    dir: Path,
    config: RepoConfig,
    envVars: Map[String, String]
  ): ZIO[Blocking, KredikError, String] =
    for {
      path           <- dir
                          ./(config.resourceFolder.getPath)
                          .toAbsolutePath
                          .mapError(e => KredikError.GeneralError(e.getCause))
      templateCommand = config.templateCommand match {
                          case TemplateCommand.Helm          => helmCommand(path)
                          case TemplateCommand.Kustomize     =>
                            kustomizeCommand(path)
                          case TemplateCommand.KustomizeHelm => kustomizeHelmCommand(path)
                        }
      stdOut         <- commandToKredikString(templateCommand.env(envVars))
    } yield stdOut

  val live: ULayer[Has[Template]] =
    ZLayer.succeed {
      new Template {
        override def templateManifests(
          repoConfig: RepoConfig,
          repoFolder: Path,
          namespace: K8sNamespace,
          environmentVars: Map[String, String],
          imageTag: ImageTag
        ): ZIO[Blocking with Has[ApplicationConfig], KredikError, Path] =
          for {
            commentPrefix  <- ZIO.service[ApplicationConfig].map(_.commentPrefix)
            templateOutput <- template(repoFolder, repoConfig, environmentVars)
                                .map(substituteNamespace(_, commentPrefix, namespace))
            tempFilePath   <- Files
                                .createTempFile(
                                  prefix = Some("templatedOutput"),
                                  fileAttributes = Seq(
                                    PosixFilePermissions.asFileAttribute(
                                      PosixFilePermissions.fromString("rw-rw-rw-")
                                    )
                                  )
                                )
                                .mapError(e => KredikError.GeneralError(e.getCause))
            _              <- FileChannel
                                .open(tempFilePath, StandardOpenOption.WRITE)
                                .use { channel =>
                                  channel.writeChunk(Chunk.fromArray(templateOutput.getBytes))
                                }
                                .mapError(e => KredikError.GeneralError(e))
          } yield tempFilePath
        override def injectEnvVarsIntoDeployments(
          namespace: K8sNamespace,
          envVars: Map[String, String]
        ): ZIO[Deployments, K8sFailure, Unit] = {
          val deploys    = getAll(Some(namespace))
          val k8sEnvVars = envVars.map {
            case (key, value) =>
              EnvVar(key, value)
          }
          deploys
            .mapM(updateDeployEnvVars(_, k8sEnvVars.toVector))
            .foreach { updatedDeploy =>
              updatedDeploy.getName.flatMap(name =>
                // TODO: This is throwing an error
                replace(name, updatedDeploy, namespace)
              )
            }
        }

      }
    }

  private def substituteNamespace(manifests: String, commentPrefix: String, prNamespace: K8sNamespace) =
    manifests.replace(s"${commentPrefix.toUpperCase}_NS_NAME", prNamespace.value)

  def updateDeployEnvVars(
    deploy: Deployment,
    envVars: Vector[EnvVar]
  ): IO[K8sFailure, Deployment] =
    for {
      spec             <- deploy.getSpec
      template         <- spec.getTemplate
      templateSpec     <- template.getSpec
      containers       <- templateSpec.getContainers
      updatedContainers = containers.map { container =>
                            val updatedEnv =
                              container.env.map(v => (v ++ envVars).distinct).getOrElse(envVars)
                            container.copy(env = updatedEnv)
                          }
      newTemplateSpec   = templateSpec.copy(containers = updatedContainers)
      newTemplate       = template.copy(spec = newTemplateSpec)
      newSpec           = spec.copy(template = newTemplate)
      finalDeploy       = deploy.copy(spec = newSpec)
    } yield finalDeploy
}
