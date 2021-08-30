package template

import template.RepoConfig.{ImageTag, walkDependencies}
import template.Template.TemplateCommand
import zio.test._
import zio.test.Assertion.equalTo
import Assertion.{hasSameElementsDistinct, isNull}
import dependencies.DependencyConverter
import git.GitSpec
import zio.{Has, URLayer}
import zio.test.environment.{TestConsole, TestEnvironment}
import zio._
import zio.blocking.Blocking
import zio.logging.Logging
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.random.Random
import zio.magic._

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
              equalTo(Map(repoConfig -> (path, ImageTag("somesha"))))
            )
          }
//          .injectSome(MockDependencyConverter.test,  GitSpec.test, Logging.console())
      },
      testM("repo has one dependency") {
        val repoConfig = RepoConfig(
          new File("abc"),
          TemplateCommand.Helm,
          Some(Set(Dependency("somewhere.test", "", "", None)))
        )
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .use { path =>
            assertM(walkDependencies(repoConfig, path, "somesha", path))(
              equalTo(
                Map(
                  repoConfig -> (path, ImageTag("somesha")),
                  RepoConfig(
                    new File("abc"),
                    TemplateCommand.Helm,
                    None
                  ) -> (Path("abc"), ImageTag("latest"))
                )
              )
            )
          }
      },
      testM("circular dependency terminates") {
        val repoConfig = RepoConfig(
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
        )
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .use { path =>
            assertM(walkDependencies(repoConfig, path, "somesha", path))(
              equalTo(Map(repoConfig -> (Path("abc"), ImageTag("circular"))))
            )
          }
      }
    ).injectSome(MockDependencyConverter.test, GitSpec.test, Logging.console())
}

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
