package nhyne.config

import zio.ZLayer
import zio.config.magnolia.DeriveConfigDescriptor.descriptor

object ApplicationConfig {
  val appConfigDescriptor = descriptor[ApplicationConfig]

  private[nhyne] val test = ZLayer.succeed(
    ApplicationConfig(
      8080,
      9090,
      List("nhyne"),
      SecretsConfig.SecretsEnvConfig("GITHUB_WEBHOOK"),
      ".watcher.yaml",
      "kredik",
      Some(true)
    )
  )
}

// TODO: Type Refinement
final case class ApplicationConfig(
  port: Int,
  prometheusPort: Int,
  organizations: List[String],
  webhookSecrets: SecretsConfig,
  configFileName: String,
  commentPrefix: String,
  devMode: Option[Boolean]
)

sealed trait SecretsConfig {
  def prefix(): String
}

object SecretsConfig {

  final case class SecretsManagerConfig(prefix: String) extends SecretsConfig
  final case class SecretsEnvConfig(prefix: String)     extends SecretsConfig
}
