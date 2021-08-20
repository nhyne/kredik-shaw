package actions

import zio.{Has, ZIO, ZLayer}
import zio.console.Console

object PullRequestEnvironment {
  type ActionService = Has[Service]

  val live: ZLayer[Console, Throwable, ActionService] = ZLayer.succeed(
    new Service {
      override def createPREnvironment(
          config: PREnvConfig
      ): ZIO[Console, Throwable, Unit] = ???

      override def updatePREnvironment(
          config: PREnvConfig
      ): ZIO[Console, Throwable, Unit] = ???

      override def destroyPREnvironment(
          config: PREnvConfig
      ): ZIO[Console, Throwable, Unit] = ???
    }
  )

  trait Service {
    def createPREnvironment(config: PREnvConfig): ZIO[Console, Throwable, Unit]
    def updatePREnvironment(config: PREnvConfig): ZIO[Console, Throwable, Unit]
    def destroyPREnvironment(config: PREnvConfig): ZIO[Console, Throwable, Unit]
  }

  // TODO: Should this use a github repo type?
  final case class PREnvConfig(
      prNumber: Int,
      repo: String,
      organization: String
  )
}
