package nhyne.dependencies

import nhyne.template.{Dependency, RepoConfig}
import nhyne.template.RepoConfig.ImageTag
import nhyne.template.Template.TemplateCommand
import zio.{Has, ULayer, ZIO, ZLayer}
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.random.Random

import java.io.File
import scala.collection.immutable.Set

object DependencyConverterSpec {

  object MockDependencyConverter {
    private val testMap: Map[String, (RepoConfig, Path)] = Map(
      "somewhere.test" -> (RepoConfig(
        new File("abc"),
        TemplateCommand.Helm,
        None
      ), Path("abc")),
      "circular" -> (RepoConfig(
        new File("itsacircle"),
        TemplateCommand.Kustomize,
        Some(
          Set(
            Dependency(
              "circular",
              "circular",
              "circular",
              Some(ImageTag("circular"))
            )
          )
        )
      ), Path("abc"))
    )

    val test: ULayer[Has[DependencyConverter.Service]] =
      ZLayer.succeed(new DependencyConverter.Service {
        override def dependencyToRepoConfig(
            dependency: Dependency,
            workingDir: Path
        ): ZIO[Blocking with Random, Throwable, (RepoConfig, Path)] =
          ZIO
            .fromOption(testMap.get(dependency.owner))
            .mapError(_ => new Throwable("could not get item from test map"))
      })

  }
}
