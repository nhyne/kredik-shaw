package nhyne.dependencies

import nhyne.git.GitCli
import nhyne.template.{ Dependency, Deployables }
import zio.config.read
import zio.nio.core.file.Path
import zio.nio.file.Files
import zio._
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
    ZEnv with Has[GitCli] with Has[ApplicationConfig] with Logging,
    KredikError,
    (Deployables, Path)
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
      _             = println(s"CONFIG SOURCE: ${configSource.names}")
      config       <- ZIO.fromEither(
                        read(Deployables.deployablesDescriptor.from(configSource))
                      )
    } yield config

  val live = ZLayer.succeed(
    new DependencyConverter {
      override def dependencyToRepoConfig(
        dependency: Dependency,
        workingDir: Path
      ): ZIO[
        ZEnv with Has[GitCli] with Has[ApplicationConfig] with Logging,
        KredikError,
        (Deployables, Path)
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
          _           = println(s"============== folder: ${repoDir.toString()} repo: $repo, dependency: $dependency")
          config     <- readConfig(repoDir, dependency).mapError(KredikError.IOReadError)
        } yield (config, repoDir)
    }
  )
}
