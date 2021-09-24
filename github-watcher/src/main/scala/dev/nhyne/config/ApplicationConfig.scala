package dev.nhyne.config

import zio.config.magnolia.DeriveConfigDescriptor.descriptor
import zio.config.magnolia.describe

object ApplicationConfig {
  val appConfigDescriptor = descriptor[ApplicationConfig]
}

// TODO: Type Refinement
final case class ApplicationConfig(
    port: Int,
    prometheusPort: Int,
    organizations: List[String],
    @describe(
      "Image whose tag will be substituted for the git sha of the pull request"
    ) imageSubstitutions: List[String] // not sure about this?
)
