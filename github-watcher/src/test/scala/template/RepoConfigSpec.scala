package template

import template.RepoConfig.{DependencyConverter, walkDependencies}
import template.Template.TemplateCommand
import zio.test._
import zio.test.Assertion.equalTo
import Assertion.{hasSameElementsDistinct, isNull}
import zio.{Has, URLayer}
import zio.test.environment.TestEnvironment
import zio.test.mock._
import zio._

import java.io.File
import scala.collection.immutable.Set

object RepoConfigSpec extends DefaultRunnableSpec {
  def spec: ZSpec[TestEnvironment, Any] =
    suite("repo config")(
      testM("repo has no dependencies") {
        val repoConfig = RepoConfig(new File("ccc"), TemplateCommand.Helm, None)
        assertM(walkDependencies(repoConfig))(equalTo(Set(repoConfig)))
      },
      testM("repo has one dependency") {
        val repoConfig = RepoConfig(new File("abc"), TemplateCommand.Helm, Some(Set(Dependency("somewhere.test", None))))
        assertM(walkDependencies(repoConfig))(equalTo(Set(repoConfig, RepoConfig(new File("aaa"), TemplateCommand.Helm, None))))
      },
      testM("circular dependency terminates") {
        val repoConfig = RepoConfig(new File("itsacircle"), TemplateCommand.Kustomize, Some(Set(Dependency("circular", Some("circular")))))
        assertM(walkDependencies(repoConfig))(equalTo(Set(repoConfig)))
      }
    )
}


object DependencyConverterMock extends Mock[RepoConfig.DependencyConverter] {
  object DependencyToRepoConfig extends Effect[Dependency, Throwable, RepoConfig]

  val compose: URLayer[Has[Proxy], DependencyConverter] = ZIO.service[Proxy].map { proxy =>
    new DependencyConverter.Service {
      override def dependencyToRepoConfig(dependency: Dependency): Task[RepoConfig] = proxy(DependencyToRepoConfig, dependency)
    }
  }.toLayer
}
