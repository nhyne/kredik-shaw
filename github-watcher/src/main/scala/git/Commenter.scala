package git

import zio.{Has, ZIO, ZLayer}

object Commenter {

  type CommenterService = Has[Service]
  trait Service {
    def comment(message: String): ZIO[Any, String, String]
  }

  val live = ZLayer.succeed(new Service {
    override def comment(message: String): ZIO[Any, String, String] = ???
  })
}
