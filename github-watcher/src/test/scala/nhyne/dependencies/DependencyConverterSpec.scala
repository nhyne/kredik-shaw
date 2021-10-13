package nhyne.dependencies

import nhyne.Errors.KredikError
import nhyne.git.GitCliSpec
import nhyne.template.{Dependency, RepoConfig}
import nhyne.template.RepoConfig.ImageTag
import nhyne.template.Template.TemplateCommand
import zio.{Has, ULayer, ZIO, ZLayer}
import zio.blocking.Blocking
import zio.logging.Logging
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.random.Random
import zio.test._
import zio.magic._
import zio.test.Assertion._
import zio.test.environment.TestEnvironment

import java.io.File
import scala.collection.immutable.Set

object DependencyConverterSpec extends DefaultRunnableSpec {

  def spec: ZSpec[TestEnvironment, Any] =
    suite("dependency converter")(
      testM("successful clone and config file") {
        val dependency = Dependency("abc", "abc", "abc", None)
        Files
          .createTempDirectoryManaged(None, Seq())
          .use { path =>
            assertM(
              for {
                stuff <-
                  ZIO
                    .service[DependencyConverter]
                    .flatMap(_.dependencyToRepoConfig(dependency, path))
              } yield stuff._1
            )(
              equalTo(
                RepoConfig(
                  new File(".watcher"),
                  TemplateCommand.Kustomize,
                  Some(
                    Set(
                      Dependency(
                        "nhyne",
                        "watcher-test-dependency",
                        "master",
                        None
                      )
                    )
                  )
                )
              )
            )

          }
          .injectSome(
            DependencyConverter.live,
            Logging.console(),
            GitCliSpec.test
          )
      }
    )

  object MockDependencyConverter {
    private val testMap: Map[String, (RepoConfig, Path)] = Map(
      "somewhere.test" -> (
        (
          RepoConfig(
            new File("abc"),
            TemplateCommand.Helm,
            None
          ),
          Path("abc")
        )
      ),
      "circular" -> (
        (
          RepoConfig(
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
          ),
          Path("abc")
        )
      )
    )

    val test: ULayer[Has[DependencyConverter]] =
      ZLayer.succeed(new DependencyConverter {
        override def dependencyToRepoConfig(
            dependency: Dependency,
            workingDir: Path
        ): ZIO[Blocking with Random, KredikError, (RepoConfig, Path)] =
          ZIO
            .fromOption(testMap.get(dependency.owner))
            .orElseFail {
              KredikError.GeneralError(
                new Throwable("could not get item from test map")
              )
            }
      })

  }
}
