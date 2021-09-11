package nhyne.template

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
import com.coralogix.zio.k8s.model.core.v1.{Container, EnvVar}

object Template {

  sealed trait TemplateCommand

  /* Even with these basic template commands I will still need some way to
   * substitute the image version and the namespace -- namespace should just be an apply -n <namespace>
   *
   * Will eventually need to be able to spawn DB pods and put their values into the config
   *
   * Kustomize offers some options for templating things in via their plugins
   *    https://kubectl.docs.kubernetes.io/guides/extending_kustomize/builtins/
   * These would all work but they require us to add `transformers` and/or `generators` sections in the kustomization.yaml file
   *    We would either have to have defaults that get overwritten or the base would not be buildable outside kredik-shaw
   *
   * Easiest way to do env vars would be to just create a config map with keys/values and let other things pull them in? -- can this work for an image?
   */
  object TemplateCommand {
    case object Helm extends TemplateCommand
    case object Kustomize extends TemplateCommand
    case object KustomizeHelm extends TemplateCommand
    // case object None extends TemplateCommand
  }

  private def kustomizeCommand(dir: Path) =
    dir.toAbsolutePath.map(path =>
      Command("kustomize", "build", path.toString())
    )

  private def template(dir: Path, config: RepoConfig) =
    config.templateCommand match {
      case TemplateCommand.Helm => ???
      case TemplateCommand.Kustomize =>
        kustomizeCommand(dir./(config.resourceFolder.getName)).flatMap(
          command => command.string
        )
      case TemplateCommand.KustomizeHelm => ???
    }

  type TemplateService = Has[Service]

  val live: ZLayer[Any, Throwable, TemplateService] = {
    ZLayer.succeed {
      new Service {
        override def templateManifests(
            repoConfig: RepoConfig,
            repoFolder: Path,
            namespace: K8sNamespace,
            imageTag: ImageTag
        ): ZIO[Blocking, Throwable, Path] = {
          for {
            templateOutput <- template(repoFolder, repoConfig)
              .map(
                substituteNamespace(_, namespace.value)
              )
              .map(substituteImage(_, imageTag))
            tempFilePath <- Files.createTempFile(
              prefix = Some("templatedOutput"),
              fileAttributes = Seq(
                PosixFilePermissions.asFileAttribute(
                  PosixFilePermissions.fromString("rw-rw-rw-")
                )
              )
            )
            _ <- FileChannel.open(tempFilePath, StandardOpenOption.WRITE).use {
              channel =>
                channel.writeChunk(Chunk.fromArray(templateOutput.getBytes))
            }
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
            .mapMParUnordered(20)(updateDeployEnvVars(_, k8sEnvVars.toVector))
            .foreach { updatedDeploy =>
              updatedDeploy.getName.flatMap(name =>
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
    ): ZIO[Blocking, Throwable, Path]

    def injectEnvVarsIntoDeployments(
        namespace: K8sNamespace,
        envVars: Map[String, String]
    ): ZIO[Deployments, K8sFailure, Unit]
  }

  // Checks to see if we have injected the "AHAB_ENVIRONMENT" environment variable
  //   We're assuming that if this one exists that all the others do too
  //   Note if we add and env var we will not inject it into already templated namespaces (unless we rebuild) -- TODO: need to create rebuild command
  private def containerHasAhabEnvVars(container: Container) = {
    container.env
      .map(_.contains(EnvVar("PR_ENVIRONMENT", "TRUE")))
      .getOrElse(false)
  }

  private def updateDeployEnvVars(
      deploy: Deployment,
      envVars: Vector[EnvVar]
  ): IO[K8sFailure, Deployment] = {

    for {
      spec <- deploy.getSpec
      template <- spec.getTemplate
      templateSpec <- template.getSpec
      containers <- templateSpec.getContainers
      updatedContainers = containers.map { container =>
        if (!containerHasAhabEnvVars(container)) {
          val updatedEnv =
            container.env.map(v => v ++ envVars).getOrElse(envVars)
          container.copy(env = updatedEnv)
        } else container
      }
      newTemplateSpec = templateSpec.copy(containers = updatedContainers)
      newTemplate = template.copy(spec = newTemplateSpec)
      newSpec = spec.copy(template = newTemplate)
      finalDeploy = deploy.copy(spec = newSpec)
    } yield finalDeploy
  }

}
