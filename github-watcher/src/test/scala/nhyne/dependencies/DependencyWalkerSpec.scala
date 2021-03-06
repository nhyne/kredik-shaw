package nhyne.dependencies

import nhyne.config.ApplicationConfig
import nhyne.template.RepoConfig.ImageTag
import nhyne.template.Template.TemplateCommand
import zio.test._
import zio.test.Assertion.equalTo
import nhyne.git.GitCliSpec
import nhyne.template.{ Dependency, Deployables, RepoConfig }
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
      testM("repo has no dependencies") {
        val deployable = Deployables(Set(RepoConfig(new File("ccc"), TemplateCommand.Helm)), None)
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .use { path =>
            assertM(
              ZIO
                .service[DependencyWalker]
                .flatMap(_.walkDependencies(deployable, path, "somesha", path))
            )(
              equalTo(Map(deployable -> ((path, ImageTag("somesha")))))
            )
          }
      },
      testM("repo has one dependency") {
        val deployable = Deployables(
          Set(RepoConfig(new File("abc"), TemplateCommand.Helm)),
          Some(Set(Dependency("somewhere.test", "", "", None)))
        )
        Files
          .createTempDirectoryManaged(None, Seq.empty)
          .use { path =>
            assertM(
              ZIO
                .service[DependencyWalker]
                .flatMap(
                  _.walkDependencies(deployable, path, "somesha", path)
                )
            )(
              equalTo(
                Map(
                  deployable -> ((path, ImageTag("somesha"))),
                  Deployables(
                    Set(
                      RepoConfig(
                        new File("abc"),
                        TemplateCommand.Helm
                      )
                    ),
                    None
                  )          -> ((Path("abc"), ImageTag("latest")))
                )
              )
            )
          }
      },
      testM("circular dependency terminates") {
        val repoConfig = Deployables(
          Set(RepoConfig(new File("itsacircle"), TemplateCommand.Kustomize)),
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
                .service[DependencyWalker]
                .flatMap(_.walkDependencies(repoConfig, path, "somesha", path))
            )(
              equalTo(Map(repoConfig -> ((Path("abc"), ImageTag("circular")))))
            )
          }
      }
    ).injectSome(
      DependencyConverterSpec.MockDependencyConverter.test,
      Logging.console(),
      DependencyWalker.live,
      GitCliSpec.test,
      ApplicationConfig.test
    )
}
