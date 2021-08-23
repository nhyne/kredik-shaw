package template

import Template.TemplateCommand
import sttp.model.Uri
import zio.config.magnolia.DeriveConfigDescriptor.descriptor

import java.io.File

object RepoConfig {
  val repoConfigDescriptor = descriptor[RepoConfig]
//  val dependencyConfigDescriptor = descriptor[Dependency]

  // TODO: We want to walk the dependency graph and apply the watcher configs for each one
  def walkDependencies(dependencies: Set[Dependency]): Set[RepoConfig] = ???

}

final case class RepoConfig(
    resourceFolder: File,
    templateCommand: TemplateCommand
    // TODO: Issue here with implicits and this param
    // Believe it has to do with nested configs??
//    dependencies: Option[Set[Dependency]]
)

// This is a graph of deps
final case class Dependency(
    repoUrl: Uri
)
