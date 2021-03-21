import zio.{App, ExitCode, URIO, ZIO}

import sttp.client3._
import sttp.client3.httpclient.zio.HttpClientZioBackend

object DhallBuilder extends App {

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    ZIO.succeed("cool").exitCode

}

object SttpClient {
  val httpClientBackend = HttpClientZioBackend.managed().toLayer

  def get

}
