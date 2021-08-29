package template

import template.RepoConfig.walkDependencies
import template.Template.TemplateCommand
import zio.test._
import zio.test.Assertion.equalTo
import Assertion.{hasSameElementsDistinct, isNull}
import dependencies.DependencyConverter
import zio.{Has, URLayer}
import zio.test.environment.TestEnvironment
import zio.test.mock._
import zio._
import zio.blocking.Blocking
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.random.Random

import java.io.File
import scala.collection.immutable.Set

object RepoConfigSpec extends DefaultRunnableSpec {
  def spec: ZSpec[TestEnvironment, Any] =
    suite("repo config")(
      testM("repo has no dependencies") {
        val repoConfig = RepoConfig(new File("ccc"), TemplateCommand.Helm, None)
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .use { path =>
            assertM(walkDependencies(repoConfig, path, "somesha", path))(
              equalTo(Map(repoConfig -> (path, "somesha")))
            )
          }
          .provideCustomLayer(MockDependencyConverter.test)
      },
      testM("repo has one dependency") {
        val repoConfig = RepoConfig(
          new File("abc"),
          TemplateCommand.Helm,
          Some(Set(Dependency("somewhere.test", None)))
        )
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .use { path =>
            assertM(walkDependencies(repoConfig, path, "somesha", path))(
              equalTo(
                Map(
                  repoConfig -> (path, "somesha"),
                  RepoConfig(
                    new File("abc"),
                    TemplateCommand.Helm,
                    None
                  ) -> (Path("abc"), "latest")
                )
              )
            )
          }
          .provideCustomLayer(MockDependencyConverter.test)
      },
      testM("circular dependency terminates") {
        val repoConfig = RepoConfig(
          new File("itsacircle"),
          TemplateCommand.Kustomize,
          Some(Set(Dependency("circular", Some("circular"))))
        )
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .use { path =>
            assertM(walkDependencies(repoConfig, path, "somesha", path))(
              equalTo(Map(repoConfig -> (Path("abc"), "circular")))
            )
          }
          .provideCustomLayer(MockDependencyConverter.test)
      }
    )
}

object MockDependencyConverter {
  private val testMap = Map(
    "somewhere.test" -> (RepoConfig(
      new File("abc"),
      TemplateCommand.Helm,
      None
    ), Path("abc")),
    "circular" -> (RepoConfig(
      new File("itsacircle"),
      TemplateCommand.Kustomize,
      Some(Set(Dependency("circular", Some("circular"))))
    ), Path("abc"))
  )

  val test = ZLayer.succeed(new DependencyConverter.Service {
    override def dependencyToRepoConfig(
        dependency: Dependency,
        workingDir: Path
    ): ZIO[Blocking with Random, Throwable, (RepoConfig, Path)] = ZIO
      .fromOption(testMap.get(dependency.repoUrl))
      .mapError(_ => new Throwable("could not get item from test map"))
  })

}
