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
import zio.logging.{Logging, log}

object DependencyConverter {
  private val watcherConfFile = ".watcher.yaml"
  private val watcherConfFileShort = ".watcher.yml"

  private type Env = Blocking with Random with GitCliService with Logging

  type DependencyConverterService = Has[Service]
  trait Service {
    def dependencyToRepoConfig(
        dependency: Dependency,
        workingDir: Path
    ): ZIO[
      Env,
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
        Env,
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
                  dependency.branch,
                  repo
                ),
                2,
                repoDir
              )
            )
          configSource <- ZIO
            .fromEither(
              YamlConfigSource
                .fromYamlFile(
                  repoDir
                    ./(
                      watcherConfFile
                    )
                    .toFile
                )
                .orElse(
                  YamlConfigSource
                    .fromYamlFile(repoDir./(watcherConfFileShort).toFile)
                )
            )
            .tapError(_ =>
              log.error(
                s"Could not read config file for dependency: $dependency"
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
