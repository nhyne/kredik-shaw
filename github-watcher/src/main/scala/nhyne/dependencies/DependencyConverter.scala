package nhyne.dependencies

import nhyne.git.GitCli
import nhyne.template.{ Dependency, RepoConfig }
import zio.config.read
import zio.nio.file.Path
import zio.nio.file.Files
import zio._
import zio.logging._
import zio.config.yaml.YamlConfigSource
import nhyne.git.GitEvents.{ Branch, Repository }
import zio.logging.{ log, Logging }
import nhyne.Errors.KredikError
import nhyne.config.ApplicationConfig

trait DependencyConverter {

  // TODO: This should return a managed Path
  def dependencyToRepoConfig(
    dependency: Dependency,
    workingDir: Path
  ): ZIO[
    ZEnv with GitCli with ApplicationConfig with Logging,
    KredikError,
    (RepoConfig, Path)
  ]
}

object DependencyConverter {
  private def readConfig(repoDir: Path, dependency: Dependency) =
    for {
      configFile   <- ZIO.service[ApplicationConfig].map(_.configFileName)
      configSource <- ZIO
                        .fromEither(
                          YamlConfigSource
                            .fromYamlFile(
                              repoDir
                                ./(
                                  configFile
                                )
                                .toFile
                            )
                        )
                        .tapError(_ =>
                          log.error(
                            s"Could not read config file for dependency: $dependency"
                          )
                        )
      config       <- ZIO.fromEither(
                        read(RepoConfig.repoConfigDescriptor.from(configSource))
                      )
    } yield config

  val live = ZLayer.succeed(
    new DependencyConverter {
      override def dependencyToRepoConfig(
        dependency: Dependency,
        workingDir: Path
      ): ZIO[
        ZEnv with GitCli with ApplicationConfig with Logging,
        KredikError,
        (RepoConfig, Path)
      ] =
        for {
          folderName <- random.nextUUID
          repoDir     = workingDir./(folderName.toString)
          _          <- Files.createDirectory(repoDir).mapError(KredikError.IOError)
          repo        = Repository.fromNameAndOwner(dependency.name, dependency.owner)
          _          <- ZIO
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
          config     <- readConfig(repoDir, dependency).mapError(KredikError.IOReadError)
        } yield (config, repoDir)
    }
  )
}
