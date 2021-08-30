package dependencies

import git.Git
import template.{Dependency, RepoConfig}
import zio.blocking.Blocking
import zio.config.read
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio.process.Command
import zio._
import zio.random.Random
import zio.config.yaml.YamlConfigSource
import git.Git.{Branch, GitCliService, Repository}
import template.RepoConfig.ImageTag

object DependencyConverter {
  private val watcherConfFile = ".watcher.yaml"
  private val defaultImageTag = ImageTag("latest")

  type DependencyConverterService = Has[Service]
  trait Service {
    def dependencyToRepoConfig(
        dependency: Dependency,
        workingDir: Path
    ): ZIO[
      Blocking with Random with GitCliService,
      Throwable,
      (RepoConfig, Path)
    ]
  }

  val live = ZLayer.succeed(
    new Service {
      override def dependencyToRepoConfig(
          dependency: Dependency,
          workingDir: Path
      ): ZIO[
        Blocking with Random with GitCliService,
        Throwable,
        (RepoConfig, Path)
      ] = {
        for {
          folderName <- random.nextUUID
          repoDir = workingDir./(folderName.toString)
          _ <- Files.createDirectory(repoDir)
          repo = Repository.fromNameAndOwner(dependency.name, dependency.owner)
          _ <- ZIO
            .service[Git.Service]
            .flatMap(git =>
              git.gitCloneDepth(
                repo,
                Branch.fromString(
                  dependency.imageTag.getOrElse(defaultImageTag).value,
                  repo
                ),
                2,
                repoDir
              )
            )
          configFile = repoDir./(
            watcherConfFile
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
}
