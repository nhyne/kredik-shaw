package template

import Template.TemplateCommand
import zio.config.magnolia.DeriveConfigDescriptor.descriptor

import java.io.File

object RepoConfig {
  val configDescriptor = descriptor[RepoConfig]
}

final case class RepoConfig(
                             resourceFolder: File,
                             templateCommand: TemplateCommand
                           )
