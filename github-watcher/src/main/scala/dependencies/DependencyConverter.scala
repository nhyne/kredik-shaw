package dependencies

import template.{Dependency, RepoConfig}
import zio.blocking.Blocking
import zio.config.read
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.process.Command
import zio._
import zio.random.Random
import zio.config.yaml.YamlConfigSource

object DependencyConverter {
  type DependencyConverterService = Has[Service]
  trait Service {
    def dependencyToRepoConfig(
        dependency: Dependency,
        workingDir: Path
    ): ZIO[Blocking with Random, Throwable, (RepoConfig, Path)]
  }

  val live = ZLayer.succeed(
    new Service {
      override def dependencyToRepoConfig(
          dependency: Dependency,
          workingDir: Path
      ): ZIO[Blocking with Random, Throwable, (RepoConfig, Path)] = {
        for {
          folderName <- random.nextUUID
          repoDir = workingDir./(folderName.toString)
          _ <- Files.createDirectory(repoDir)
          _ <- cloneDependency(dependency, repoDir).exitCode
          configFile =
            repoDir./(
              ".watcher.conf"
            ) // TODO: if a repo does not specify this we should suggest adding it instead of erroring
          configSource <- ZIO.fromEither(
            YamlConfigSource.fromYamlFile(
              configFile.toFile
            )
          )
          config <- ZIO.fromEither(
            read(RepoConfig.repoConfigDescriptor.from(configSource))
          )
        } yield (config, repoDir)
      }
    }
  )

  private def cloneDependency(dependency: Dependency, path: Path) =
    Command(
      "git",
      "clone",
      "--depth=2",
      s"--branch=${dependency.imageTag.getOrElse("master")}",
      dependency.repoUrl,
      path.toString()
    )
}
