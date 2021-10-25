package nhyne.secrets

import nhyne.git.GitEvents.Repository
import zio.{ Has, URLayer, ZIO }
import io.github.vigoo.zioaws.secretsmanager.{ SecretsManager => AwsSecretsManager }
import io.github.vigoo.zioaws.secretsmanager.model.GetSecretValueRequest
import nhyne.Errors.KredikError
import nhyne.config.{ ApplicationConfig, SecretsConfig }
import zio.system.{ env, System }

trait Secrets {
  def readSecret(
    repository: Repository
  ): ZIO[AwsSecretsManager with System, KredikError, String]

}

object Secrets {
  def live: URLayer[Has[ApplicationConfig], Has[Secrets]] =
    ZIO
      .service[ApplicationConfig]
      .map(_.webhookSecrets)
      .map {
        case SecretsConfig.SecretsManagerConfig(prefix) => AWSSecrets(prefix)
        case SecretsConfig.SecretsEnvConfig(prefix)     => EnvironmentSecrets(prefix)
      }
      .toLayer

  final case class AWSSecrets(prefix: String) extends Secrets {
    override def readSecret(
      repository: Repository
    ): ZIO[AwsSecretsManager, KredikError, String] =
      (for {
        sm         <- ZIO.service[AwsSecretsManager.Service]
        readSecret <- sm.getSecretValue(
                        GetSecretValueRequest(s"$prefix/${repository.fullName}")
                      )
        secret     <- readSecret.secretString
      } yield secret).mapError(e => KredikError.GeneralError(e.toThrowable))
  }

  final case class EnvironmentSecrets(prefix: String) extends Secrets {
    override def readSecret(repository: Repository): ZIO[System, KredikError, String] = {
      val secretName = s"${prefix}_${repository.fullName}"
        .toUpperCase()
        .replace('/', '_')
        .replace('-', '_')
      (for {
        maybeSecret <- env(secretName)
        secret      <- ZIO
                         .fromOption(maybeSecret)
      } yield secret).orElseFail(KredikError.GeneralError(s"missing environment variable secret for $secretName"))
    }
  }
}
