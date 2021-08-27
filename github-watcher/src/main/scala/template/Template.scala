package template

import zio.process._

import zio._
import zio.blocking.Blocking
import zio.nio.channels.FileChannel
import zio.nio.core.file.Path
import zio.nio.file.Files

import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

object Template {

  sealed trait TemplateCommand

  /* Even with these basic template commands I will still need some way to
   * substitute the image version and the namespace -- namespace should just be an apply -n <namespace>
   *
   * Will eventually need to be able to spawn DB pods and put their values into the config
   */
  object TemplateCommand {
    case object Helm extends TemplateCommand
    case object Kustomize extends TemplateCommand
    case object KustomizeHelm extends TemplateCommand
  }

  // TODO: Would be nicer if this was file/Path based instead of string
  private def kustomizeCommand(dir: Path) = dir.toAbsolutePath.map(path =>
    Command("kustomize", "build", path.toString())
  )
  private def kustomizeCommand(dir: String) = Command("kustomize", "build", dir)
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
            namespaceName: String,
            gitRevision: String
        ): ZIO[Blocking, Throwable, Path] = {
          for {
            templateOutput <- template(repoFolder, repoConfig)
              .map(
                substituteNamespace(_, namespaceName)
              )
              .map(substituteImage(_, gitRevision))
            tempFilePath <- Files.createTempFile(
              prefix = Some("templatedOutput"),
              fileAttributes = Seq(
                PosixFilePermissions.asFileAttribute(
                  PosixFilePermissions.fromString("rw-rw-rw-")
                )
              )
            )
            _ <- FileChannel.open(tempFilePath, StandardOpenOption.WRITE).use {
              // TODO: Look into if this gets cleaned up right away
              channel =>
                channel.writeChunk(Chunk.fromArray(templateOutput.getBytes))
            }
          } yield tempFilePath
        }
      }
    }
  }

  private val NAMESPACE_SUBSTITUTION = "WATCHER_NS_NAME"
  // TODO: This needs to be better
  //    It would be better to provide a case class of fields that are able to be substituted?
  //    Or even just doing it in parallel for all files in the `.watcher` directory?
  private def substituteNamespace(manifests: String, namespaceName: String) =
    manifests.replace(NAMESPACE_SUBSTITUTION, namespaceName)

  private val GIT_REV_SUBSTITUTION = "GIT_HASH"
  private def substituteImage(manifests: String, gitTag: String) =
    manifests.replace(GIT_REV_SUBSTITUTION, "latest")

  trait Service {
    def templateManifests(
        repoConfig: RepoConfig,
        repoFolder: Path,
        namespaceName: String,
        gitRevision: String
    ): ZIO[Blocking, Throwable, Path]
  }

}
