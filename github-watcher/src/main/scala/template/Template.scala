package template

import zio.process._

import java.io.File
import zio._
import zio.blocking.{Blocking, effectBlocking}
import zio.config._
import zio.nio.channels.FileChannel
import zio.nio.file.Files

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

  private def kustomizeCommand(dir: File) = Command("kustomize", "build", dir.getAbsolutePath)
  private def template(dir: File, command: TemplateCommand) = command match {
    case TemplateCommand.Helm => ???
    case TemplateCommand.Kustomize => kustomizeCommand(dir).string
    case TemplateCommand.KustomizeHelm => ???
  }

  type TemplateService = Has[Service]

  val live: ZLayer[Any, Throwable, TemplateService] = {
    ZLayer.succeed {
      new Service {
        override def templateManifests(repoFolder: File): ZIO[Has[RepoConfig] with Blocking, Throwable, File] = {


          for {
            config <- ZIO.service[RepoConfig]
            templateOutput <- template(repoFolder, config.templateCommand)
            tempFile <- Files.createTempFile(prefix = Some("templatedOutput"), fileAttributes = Seq())
            _ <- FileChannel.open(tempFile).use { channel =>
              channel.writeChunk(Chunk.fromArray(templateOutput.getBytes))
            }
          } yield new File("abd")
        }
      }
    }
  }

  trait Service {
    def templateManifests(dir: File): ZIO[Has[RepoConfig] with Blocking, Throwable, File]
  }


}

