package nhyne.dependencies

import nhyne.template.RepoConfig.ImageTag
import nhyne.template.Template.TemplateCommand
import zio.test._
import zio.test.Assertion.equalTo
import nhyne.git.GitSpec
import nhyne.template.{Dependency, RepoConfig}
import zio.test.environment.TestEnvironment
import zio._
import zio.logging.Logging
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.magic._

import java.io.File
import scala.collection.immutable.Set

object DependencyWalkerSpec extends DefaultRunnableSpec {
  def spec: ZSpec[TestEnvironment, Any] =
    suite("repo config")(
      testM("repo has no nhyne.dependencies") {
        val repoConfig = RepoConfig(new File("ccc"), TemplateCommand.Helm, None)
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .use { path =>
            assertM(
              ZIO
                .service[DependencyWalker.Service]
                .flatMap(_.walkDependencies(repoConfig, path, "somesha", path))
            )(
              equalTo(Map(repoConfig -> (path, ImageTag("somesha"))))
            )
          }
      },
      testM("repo has one dependency") {
        val repoConfig = RepoConfig(
          new File("abc"),
          TemplateCommand.Helm,
          Some(Set(Dependency("somewhere.test", "", "", None)))
        )
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .use {
            path =>
              assertM(
                ZIO
                  .service[DependencyWalker.Service]
                  .flatMap(
                    _.walkDependencies(repoConfig, path, "somesha", path)
                  )
              )(
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
            assertM(
              ZIO
                .service[DependencyWalker.Service]
                .flatMap(_.walkDependencies(repoConfig, path, "somesha", path))
            )(
              equalTo(Map(repoConfig -> (Path("abc"), ImageTag("circular"))))
            )
          }
      }
    ).injectSome(
      DependencyConverterSpec.MockDependencyConverter.test,
      GitSpec.test,
      Logging.console(),
      DependencyWalker.live
    )
}
