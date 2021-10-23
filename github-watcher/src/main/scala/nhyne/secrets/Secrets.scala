package nhyne.secrets

import nhyne.git.GitEvents.Repository
import zio.{ Has, ULayer, ZIO, ZLayer }
import io.github.vigoo.zioaws.secretsmanager.SecretsManager
import io.github.vigoo.zioaws.secretsmanager.model.GetSecretValueRequest
import nhyne.Errors.KredikError
import nhyne.config.ApplicationConfig

trait Secrets {
  def generateAndSaveSecret(
    repository: Repository
  ): ZIO[SecretsManager with Has[ApplicationConfig], KredikError, Unit]
  def readSecret(
    repository: Repository
  ): ZIO[SecretsManager with Has[ApplicationConfig], KredikError, String]

}

object Secrets {
  val live: ULayer[Has[Secrets]] = ZLayer.succeed(new Secrets {
    override def generateAndSaveSecret(
      repository: Repository
    ): ZIO[SecretsManager with Has[ApplicationConfig], KredikError, Unit] = ???

    override def readSecret(
      repository: Repository
    ): ZIO[SecretsManager with Has[ApplicationConfig], KredikError, String] =
      (for {
        secretPrefix <- ZIO
                          .service[ApplicationConfig]
                          .map(_.webhookSecrets.prefix())
        sm           <- ZIO.service[SecretsManager.Service]
        readSecret   <- sm.getSecretValue(
                          GetSecretValueRequest(s"$secretPrefix/${repository.fullName}")
                        )
        secret       <- readSecret.secretString
      } yield secret).mapError(e => KredikError.GeneralError(e.toThrowable))

  })
}
