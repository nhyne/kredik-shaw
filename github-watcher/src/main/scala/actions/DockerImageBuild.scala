package actions

import zio.{Has, ZLayer}

import java.io.File

object DockerImageBuild {

  type DockerBuildService = Has[Service]

  val live: ZLayer[Any, Nothing, DockerBuildService] = ZLayer.succeed(
    new Service {
      override def buildDockerImage(file: File, workingDir: File): Unit = ???

      override def buildDockerImage(command: Seq[String], env: Map[String, String]): Unit = ???
    }
  )

  trait Service {
    def buildDockerImage(file: File, workingDir: File): Unit
    def buildDockerImage(command: Seq[String], env: Map[String, String]): Unit
  }
}
