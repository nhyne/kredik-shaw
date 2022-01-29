package nhyne

import com.coralogix.zio.k8s.client.K8sFailure
import zio.config.ReadError
import zio.process.CommandError

import java.io.IOException

object Errors {

  // TODO: Refine these error types more
  // TODO: Should all provide a 'pretty print' function for github comments

  sealed trait KredikError {
    def toThrowable(): Throwable
  }
  object KredikError       {
    final case class CliError(
      commandError: CommandError,
      stdOut: Option[String],
      stdErr: Option[String]
    )                                               extends KredikError {
      def toThrowable(): Throwable =
        new Throwable(
          Option(commandError.getMessage)
            .flatMap(ce => stdErr.map(ce + "\n" + _))
            .getOrElse(stdErr.getOrElse("Cli error without error message"))
        )
    }
    object CliError {
      def apply(commandError: CommandError): CliError =
        CliError(commandError, None, None)
      def apply(
        commandError: CommandError,
        stdOut: String,
        stdErr: String
      ): CliError                                     = CliError(commandError, Some(stdOut), Some(stdErr))
    }
    final case class GeneralError(cause: Throwable) extends KredikError {
      def toThrowable(): Throwable = cause
    }
    object GeneralError {
      def apply(message: String): GeneralError =
        GeneralError(new Throwable(message))
    }

    final case class K8sError(cause: K8sFailure)           extends KredikError {
      def toThrowable(): Throwable =
        new Throwable(cause.toString)
    }
    final case class IOReadError(cause: ReadError[String]) extends KredikError {
      def toThrowable(): Throwable =
        new Throwable(cause.prettyPrint())
    }
    final case class IOError(cause: IOException)           extends KredikError {
      def toThrowable(): Throwable = cause
    }

    case object InvalidSignature extends KredikError {
      override def toThrowable(): Throwable = new Throwable("InvalidSignature")
    }

  }
}
