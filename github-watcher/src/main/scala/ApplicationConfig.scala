import zio.config.magnolia.DeriveConfigDescriptor.descriptor

object ApplicationConfig {
  val appConfigDescriptor = descriptor[ApplicationConfig]
}

// TODO: Type Refinement
final case class ApplicationConfig(
    port: Int,
    prometheusPort: Int,
    organizations: List[String]
)
