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
import com.coralogix.zio.k8s.client.apps.v1.deployments.{
  Deployments,
  getAll,
  replace
}
import com.coralogix.zio.k8s.client.model.K8sNamespace
import com.coralogix.zio.k8s.model.apps.v1.Deployment
import com.coralogix.zio.k8s.model.core.v1.EnvVar
import nhyne.Errors.KredikError

object Template {

  sealed trait TemplateCommand

  object TemplateCommand {
    case object Helm extends TemplateCommand
    case object Kustomize extends TemplateCommand
    case object KustomizeHelm extends TemplateCommand
    // case object None extends TemplateCommand -- this would essentially just read all the files into a string? -- would have to do a `sed`
  }

  private def kustomizeCommand(path: Path) =
    Command("kustomize", "build", path.toString())

  private def kustomizeHelmCommand(path: Path) =
    Command("kustomize", "build", "--enable-helm", path.toString())

  private def helmCommand(dir: Path) = ???

  def template(
      dir: Path,
      config: RepoConfig
  ): ZIO[Blocking, KredikError, String] = {
    for {
      path <-
        dir
          ./(config.resourceFolder.getName)
          .toAbsolutePath
          .mapError(e => KredikError.GeneralError(e.getCause))
      templateCommand = config.templateCommand match {
        case TemplateCommand.Helm => helmCommand(path)
        case TemplateCommand.Kustomize =>
          kustomizeCommand(path)
        case TemplateCommand.KustomizeHelm => kustomizeHelmCommand(path)
      }
      stdOut <- commandToKredikString(templateCommand)
    } yield stdOut
  }

  type TemplateService = Has[Service]

  val live: ULayer[TemplateService] = {
    ZLayer.succeed {
      new Service {
        override def templateManifests(
            repoConfig: RepoConfig,
            repoFolder: Path,
            namespace: K8sNamespace,
            imageTag: ImageTag
        ): ZIO[Blocking, KredikError, Path] = {
          for {
            templateOutput <- template(repoFolder, repoConfig)
              .map(
                substituteNamespace(_, namespace.value)
              )
              .map(substituteImage(_, imageTag))
            tempFilePath <-
              Files
                .createTempFile(
                  prefix = Some("templatedOutput"),
                  fileAttributes = Seq(
                    PosixFilePermissions.asFileAttribute(
                      PosixFilePermissions.fromString("rw-rw-rw-")
                    )
                  )
                )
                .mapError(e => KredikError.GeneralError(e.getCause))
            _ <-
              FileChannel
                .open(tempFilePath, StandardOpenOption.WRITE)
                .use { channel =>
                  channel.writeChunk(Chunk.fromArray(templateOutput.getBytes))
                }
                .mapError(e => KredikError.GeneralError(e))
          } yield tempFilePath
        }
        override def injectEnvVarsIntoDeployments(
            namespace: K8sNamespace,
            envVars: Map[String, String]
        ): ZIO[Deployments, K8sFailure, Unit] = {
          val deploys = getAll(Some(namespace))
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
  }

  private val NAMESPACE_SUBSTITUTION = "WATCHER_NS_NAME"
  /*
   * TODO: This needs to be better
   *    It would be better to provide a case class of fields that are able to be substituted?
   *    Or even just doing it in parallel for all files in the `.watcher` directory?
   *
   * Would be nice to just use some kustomize vars for this?
   *    - Each repo can specify where they need it substituted
   *    - Write one file out and have kustomize pull it in as a resource and some vars from it?
   */

  private def substituteNamespace(manifests: String, namespaceName: String) =
    manifests.replace(NAMESPACE_SUBSTITUTION, namespaceName)

  private val GIT_REV_SUBSTITUTION = "GIT_HASH"
  private def substituteImage(manifests: String, imageTag: ImageTag) =
    manifests.replace(GIT_REV_SUBSTITUTION, imageTag.value)

  trait Service {
    def templateManifests(
        repoConfig: RepoConfig,
        repoFolder: Path,
        namespace: K8sNamespace,
        imageTag: ImageTag
    ): ZIO[Blocking, KredikError, Path]

    def injectEnvVarsIntoDeployments(
        namespace: K8sNamespace,
        envVars: Map[String, String]
    ): ZIO[Deployments, K8sFailure, Unit]
  }

  def updateDeployEnvVars(
      deploy: Deployment,
      envVars: Vector[EnvVar]
  ): IO[K8sFailure, Deployment] =
    for {
      spec <- deploy.getSpec
      template <- spec.getTemplate
      templateSpec <- template.getSpec
      containers <- templateSpec.getContainers
      updatedContainers = containers.map { container =>
        val updatedEnv =
          container.env.map(v => (v ++ envVars).distinct).getOrElse(envVars)
        container.copy(env = updatedEnv)
      }
      newTemplateSpec = templateSpec.copy(containers = updatedContainers)
      newTemplate = template.copy(spec = newTemplateSpec)
      newSpec = spec.copy(template = newTemplate)
      finalDeploy = deploy.copy(spec = newSpec)
    } yield finalDeploy
}
