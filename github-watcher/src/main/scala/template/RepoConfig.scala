package template

import Template.TemplateCommand
import template.RepoConfig.ImageTag
import zio.config._
import zio.config.derivation.describe
import zio.config.magnolia.DeriveConfigDescriptor

import scala.collection.immutable.Set
import java.io.File

object RepoConfig {

  final case class ImageTag(value: String)

  val repoConfigDescriptor: ConfigDescriptor[RepoConfig] =
    DeriveConfigDescriptor.descriptor[RepoConfig]
}

// TODO: Would be nice if this used refinement types to perform some validations
//    https://zio.github.io/zio-config/docs/refined/refined_index
//    we actually really need a refinement type or we need something else to know what the name of the repo is
@describe("this config is for a repo watcher")
final case class RepoConfig(
    resourceFolder: File,
    templateCommand: TemplateCommand,
    // TODO: Issue here with implicits and this param
    // Believe it has to do with nested configs??
    // TODO: This should really be a thunk
    dependencies: Option[Set[Dependency]]
)

@describe("this config is for a dependency of a repo")
final case class Dependency(
    owner: String,
    name: String,
    branch: String,
    imageTag: Option[ImageTag]
)

object Dependency {
  def repoUrl(dependency: Dependency): String =
    s"https://github.com/${dependency.owner}/${dependency.name}"
}
