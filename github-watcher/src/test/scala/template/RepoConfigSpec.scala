package template

import template.RepoConfig.walkDependencies
import template.Template.TemplateCommand
import zio.test._
import zio.test.Assertion.equalTo
import Assertion.{hasSameElementsDistinct, isNull}
import zio.test.environment.TestEnvironment

import java.io.File

object RepoConfigSpec extends DefaultRunnableSpec {
  def spec: ZSpec[TestEnvironment, Any] =
    suite("repo config")(
      testM("repo has no dependencies") {
        val repoConfig = RepoConfig(new File("ccc"), TemplateCommand.Helm, None)
        val repoConfigs = walkDependencies(repoConfig)
        assertM(repoConfigs)(equalTo(Set(repoConfig)))
      }
    )
}
