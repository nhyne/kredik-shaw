package nhyne.dependencies

import nhyne.git.GitCli
import nhyne.template.{Dependency, RepoConfig}
import zio.config.read
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio._
import zio.config.yaml.YamlConfigSource
import nhyne.git.GitEvents.{Branch, Repository}
import zio.logging.{Logging, log}
import nhyne.Errors.KredikError

trait DependencyConverter {

  // TODO: This should return a managed Path
  def dependencyToRepoConfig(
      dependency: Dependency,
      workingDir: Path
  ): ZIO[
    ZEnv with Has[GitCli] with Logging,
    KredikError,
    (RepoConfig, Path)
  ]
}

object DependencyConverter {
  private val watcherConfFile = ".watcher.yaml"
  private val watcherConfFileShort = ".watcher.yml"
  private def readConfig(repoDir: Path, dependency: Dependency) =
    for {

      configSource <-
        ZIO
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
    } yield config

  val live = ZLayer.succeed(
    new DependencyConverter {
      override def dependencyToRepoConfig(
          dependency: Dependency,
          workingDir: Path
      ): ZIO[
        ZEnv with Has[GitCli] with Logging,
        KredikError,
        (RepoConfig, Path)
      ] = {
        for {
          folderName <- random.nextUUID
          repoDir = workingDir./(folderName.toString)
          _ <- Files.createDirectory(repoDir).mapError(KredikError.IOError)
          repo = Repository.fromNameAndOwner(dependency.name, dependency.owner)
          _ <-
            ZIO
              .service[GitCli]
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
          config <-
            readConfig(repoDir, dependency).mapError(KredikError.IOReadError)
        } yield (config, repoDir)
      }
    }
  )
}
