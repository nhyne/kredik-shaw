package nhyne.template

import nhyne.template.Template.TemplateCommand
import nhyne.template.RepoConfig.ImageTag
import zio.config.ConfigDescriptor
import zio.config.derivation.describe
import zio.config.magnolia.DeriveConfigDescriptor.descriptor

import scala.collection.immutable.Set
import java.io.File

object RepoConfig {

  final case class ImageTag(value: String)
//  implicit val imageTagConfig: ConfigDescriptor[ImageTag] = descriptor[ImageTag]
//
//  implicit val repoConfigDescriptor: ConfigDescriptor[RepoConfig] =
//    descriptor[RepoConfig]
}

object Deployables {

  val deployablesDescriptor: ConfigDescriptor[Deployables] =
    descriptor[Deployables]
}

final case class Deployables(deployables: Set[RepoConfig], dependencies: Option[Set[Dependency]])

@describe("this config is for a repo watcher")
final case class RepoConfig(
  resourceFolder: File,
  templateCommand: TemplateCommand
)

object Dependency {
//  implicit val dependencyDescriptor: ConfigDescriptor[Dependency] =
//    descriptor[Dependency]
}

// TODO: Would be nice if this used refinement types to perform some validations
//    https://zio.github.io/zio-config/docs/refined/refined_index
//    we actually really need a refinement type or we need something else to know what the name of the repo is
@describe("this config is for a dependency of a repo")
final case class Dependency(
  owner: String,
  name: String,
  branch: String,
  imageTag: Option[ImageTag]
) { self =>
  def repoUrl(): String =
    s"https://github.com/$owner/$name"
}
