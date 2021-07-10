import zio._
import zio.console.putStrLn
object Main extends App {

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    putStrLn("hello world").exitCode
  }
}
