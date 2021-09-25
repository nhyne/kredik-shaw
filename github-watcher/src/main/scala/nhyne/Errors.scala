package nhyne

import com.coralogix.zio.k8s.client.K8sFailure
import zio.config.ReadError
import zio.process.CommandError

import java.io.IOException

object Errors {

  // TODO: Refine these error types more
  // TODO: Should all provide a 'pretty print' function for github comments

  sealed trait KredikError
  object KredikError {
    final case class CliError(
        commandError: CommandError,
        stdOut: Option[String],
        stdErr: Option[String]
    ) extends KredikError
    object CliError {
      def apply(commandError: CommandError): CliError =
        CliError(commandError, None, None)
      def apply(
          commandError: CommandError,
          stdOut: String,
          stdErr: String
      ): CliError = CliError(commandError, Some(stdOut), Some(stdErr))
    }
    final case class GeneralError(cause: Throwable) extends KredikError
    final case class K8sError(cause: K8sFailure) extends KredikError
    final case class IOReadError(cause: ReadError[String]) extends KredikError
    final case class IOError(cause: IOException) extends KredikError
  }
}
