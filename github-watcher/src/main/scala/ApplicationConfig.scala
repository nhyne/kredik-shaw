import zio.config._
import zio.config.magnolia.DeriveConfigDescriptor.descriptor

object ApplicationConfig {
  val configDescriptor = descriptor[ApplicationConfig]

}

final case class ApplicationConfig(
                              port: Int
                              )
