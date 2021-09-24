package dev
import com.coralogix.zio.k8s.client.K8sFailure
import zio.{ExitCode, ZIO}
import zio.blocking.Blocking
import zio.config.ReadError
import zio.process.{Command, CommandError}

import java.io.IOException

package object nhyne {
  def commandToKredikString(
                             command: Command
                           ): ZIO[Blocking, KredikError.CliError, String] =
    for {
      process <- command.run.mapError(KredikError.CliError(_))
      stdErr <- process.stderr.string.mapError(KredikError.CliError(_))
      stdOut <- process.stdout.string.mapError(KredikError.CliError(_))
      _ <- process.successfulExitCode.mapError(
        KredikError.CliError(_, stdOut, stdErr)
      )
    } yield stdOut

  // TODO: Move this into its own object
  // TODO: Capture stdout as well for error and put it into the CliError type
  // TODO: Can we stream the stdout + stderr to put them into a string in the order they were produced?
  def commandToKredikExitCode(
                               command: Command
                             ): ZIO[Blocking, KredikError.CliError, ExitCode] =
    for {
      process <- command.run.mapError(KredikError.CliError(_))
      stdErr <- process.stderr.string.mapError(KredikError.CliError(_))
      stdOut <- process.stdout.string.mapError(KredikError.CliError(_))
      exitCode <- process.successfulExitCode.mapError(
        KredikError.CliError(_, stdOut, stdErr)
      )
    } yield exitCode


  // TODO: Refine these error types more
  // TODO: Should all provide a 'pretty print' function for github comments

  sealed trait KredikError {
    def prettyPrint(): String
  }
  object KredikError {

    final case class CliError(
                               cause: CommandError,
                               stdOut: Option[String],
                               stdErr: Option[String]
                             ) extends KredikError {
      def prettyPrint(): String =
        s"""
           |${cause.getMessage}
           |${"=" * 5}
           |${cause.getStackTrace.mkString("\n\t")}
           |${"=" * 5}
           |${stdOut.map(s => s ++ "\n" ++ "=" * 5).getOrElse("")}
           |${stdErr.getOrElse("")}
           |""".stripMargin
    }

    object CliError {
      def apply(cause: CommandError): CliError =
        CliError(cause, None, None)
      def apply(
                 cause: CommandError,
                 stdOut: String,
                 stdErr: String
               ): CliError = CliError(cause, Some(stdOut), Some(stdErr))
    }

    final case class GeneralError(cause: Throwable) extends KredikError {
      def prettyPrint(): String = cause.getStackTrace.mkString("\n")
    }
    final case class K8sError(cause: K8sFailure) extends KredikError {
      def prettyPrint(): String = cause.toString
    }
    final case class IOReadError(cause: ReadError[String]) extends KredikError {
      def prettyPrint(): String = cause.prettyPrint()
    }
    final case class IOError(cause: IOException) extends KredikError {
      def prettyPrint(): String = s"""
                                     |${cause.getMessage}
                                     |${"=" * 5}
                                     |${cause.getStackTrace.mkString("\n\t")}
                                     |""".stripMargin
    }
  }
}
