package nhyne
import nhyne.Errors.KredikError
import zio.{ ExitCode, ZIO }
import zio.process.Command

object CommandWrapper {

  // TODO: Can we stream the stdout + stderr to put them into a string in the order they were produced?
  def commandToKredikExitCode(
    command: Command
  ): ZIO[Blocking, KredikError.CliError, ExitCode] =
    for {
      process  <- command.run.mapError(KredikError.CliError(_))
      stdErr   <- process.stderr.string.mapError(KredikError.CliError(_))
      stdOut   <- process.stdout.string.mapError(KredikError.CliError(_))
      exitCode <- process.successfulExitCode.mapError(
                    KredikError.CliError(_, stdOut, stdErr)
                  )
    } yield exitCode

  def commandToKredikString(
    command: Command
  ): ZIO[Blocking, KredikError.CliError, String] =
    for {
      process <- command.run.mapError(KredikError.CliError(_))
      stdErr  <- process.stderr.string.mapError(KredikError.CliError(_))
      stdOut  <- process.stdout.string.mapError(KredikError.CliError(_))
      _       <- process.successfulExitCode.mapError(
                   KredikError.CliError(_, stdOut, stdErr)
                 )
    } yield stdOut
}
