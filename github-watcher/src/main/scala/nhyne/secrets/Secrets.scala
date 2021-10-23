package nhyne.secrets

import nhyne.git.GitEvents.Repository
import zio.{ Has, URLayer, ZIO, ZLayer }
import io.github.vigoo.zioaws.secretsmanager.SecretsManager
import io.github.vigoo.zioaws.secretsmanager.model.GetSecretValueRequest
import nhyne.Errors.KredikError
import nhyne.config.{ ApplicationConfig, SecretsConfig }

trait Secrets {
  def readSecret(
    repository: Repository
  ): ZIO[SecretsManager with Has[ApplicationConfig], KredikError, String]

}

object Secrets {
  def live: URLayer[Has[ApplicationConfig], Has[Secrets]] =
    ZIO.service[ApplicationConfig].map(_.webhookSecrets).map {
      case SecretsConfig.SecretsManagerConfig(prefix) => secretsManager(prefix)
      case SecretsConfig.SecretsEnvConfig(prefix)     => ???
    }

  def secretsManager(prefix: String) =
    ZLayer.succeed(new Secrets {

      private val secretPrefix = prefix

      // TODO: Reading this should depend on what kind of secret config we have
      //    Or should we return different versions of this for the different secret config types we have?
      override def readSecret(
        repository: Repository
      ): ZIO[SecretsManager with Has[ApplicationConfig], KredikError, String] =
        (for {
          sm         <- ZIO.service[SecretsManager.Service]
          readSecret <- sm.getSecretValue(
                          GetSecretValueRequest(s"$secretPrefix/${repository.fullName}")
                        )
          secret     <- readSecret.secretString
        } yield secret).mapError(e => KredikError.GeneralError(e.toThrowable))
    })

  def environmentSecrets(prefix: String) = ???
}
