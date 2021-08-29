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
          configFile = repoDir./(".watcher.conf")
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

  private def cloneDependency(dependency: Dependency, path: Path) = Command(
    "git",
    "clone",
    "--depth=2",
    s"--branch=${dependency.imageTag.getOrElse("master")}",
    dependency.repoUrl,
    path.toString()
  )

  // TODO: Provide a real version of this function and use this version (maybe with a map lookup?) as a test implementation
  // TODO: move this to a layer by itself. It will make testing easier and it only _slightly_ relates to the walkDeps function above
  //          realistically the walk deps function relies on this as an R
  private def mockDependencyToRepoConfig(
      dependency: Dependency,
      workingDir: Path
  ): Task[(RepoConfig, Path)] = {
    dependency.imageTag match {
      case Some("circular") =>
        ??? //Task.succeed(RepoConfig(new File("itsacircle"), TemplateCommand.Kustomize, Some(Set(Dependency("circular", Some("circular"))))))
      case Some(_) => ???
      case None =>
        ??? //Task.succeed(RepoConfig(new File("aaa"), TemplateCommand.Helm, None))
    }
  }
}
