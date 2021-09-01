package dependencies

import dependencies.DependencyConverter.DependencyConverterService
import git.GitCli.GitCliService
import template.{Dependency, RepoConfig}
import template.RepoConfig.ImageTag
import zio.blocking.Blocking
import zio.logging.Logging
import zio.{Has, Ref, ZIO, ZLayer}
import zio.nio.core.file.Path
import zio.random.Random

import scala.collection.immutable.Set

object DependencyWalker {
  private type Env = Blocking
    with Random
    with DependencyConverterService
    with GitCliService
    with Logging

  type DependencyWalkerService = Has[Service]

  trait Service {
    def walkDependencies(
        startingConfig: RepoConfig,
        startingRepoPath: Path,
        startingSha: String, // TODO: Make this something besides a string
        workingDir: Path
    ): ZIO[Env, Throwable, Map[RepoConfig, (Path, ImageTag)]]
  }

  // TODO: Want to pull in the functions in the parent object, just not sure about needing to mock all of them in a test or just the Dependency -> RepoConfig
  //    maybe the Dependency -> RepoConfig function could be its own service?
  val live = ZLayer.succeed(
    new Service {
      // hoping that a starting point doesn't also end up as its own dep?
      override def walkDependencies(
          startingConfig: RepoConfig,
          startingRepoPath: Path,
          startingSha: String,
          workingDir: Path
      ): ZIO[Env, Throwable, Map[RepoConfig, (Path, ImageTag)]] =
        walkDeps(
          workingDir,
          startingConfig.dependencies.getOrElse(Set.empty),
          Set.empty,
          Map(startingConfig -> (startingRepoPath, ImageTag(startingSha)))
        )

    }
  )

  /* TODO: We want to walk the dependency graph and apply the watcher configs for each one
   * This is probably the most important part
   * In general we just want to walk through each dependency, turn it into a RepoConfig, then add it to a set of seen deps
   *
   * TODO: Make this parallel
   * TODO: Make this tail recursive
   * TODO: Make this apply the configs too?
   *    Could change configs: Set[RepoConfig] into Map[RepoConfig] => File??
   */
  private def walkDeps(
      workingDir: Path,
      unseenDeps: Set[Dependency],
      seenDeps: Set[Dependency],
      configs: Map[RepoConfig, (Path, ImageTag)]
  ): ZIO[
    Env,
    Throwable,
    Map[
      RepoConfig,
      (Path, ImageTag)
    ]
  ] =
    for {
      newUnseenDepsRef <- Ref.make(Set.empty[Dependency])
      seenDepsRef <- Ref.make(seenDeps)
      processedConfigsRef <- Ref.make(configs)
      _ <- ZIO.foreach(unseenDeps)(dep => {
        for {
          seen <- seenDepsRef.get
          shouldProcess = !seen.contains(dep)
          maybeNewDependenciesToProcess <- if (shouldProcess)
            ZIO.service[dependencies.DependencyConverter.Service].flatMap {
              depService =>
                depService.dependencyToRepoConfig(dep, workingDir).flatMap {
                  case (rc, path) =>
                    processedConfigsRef.get.flatMap { processedConfigs =>
                      processedConfigsRef
                        .set(
                          processedConfigs + (rc -> (path, dep.imageTag
                            .getOrElse(ImageTag("latest"))))
                        )
                        .flatMap(_ => ZIO.succeed(rc.dependencies))
                    }
                }
            } else ZIO.none
          nextSeen = seen + dep
          _ <- seenDepsRef.set(nextSeen)
          depsToProcess = maybeNewDependenciesToProcess
            .getOrElse(Set.empty)
            .diff(nextSeen)
          newUnseenDeps <- newUnseenDepsRef.get
          _ <- newUnseenDepsRef.set(newUnseenDeps ++ depsToProcess)
        } yield ()
      })
      newUnseenDeps <- newUnseenDepsRef.get
      newSeenDeps <- seenDepsRef.get
      deps <- processedConfigsRef.get
      ret <- if (newUnseenDeps.isEmpty) ZIO.succeed(deps)
      else walkDeps(workingDir, newUnseenDeps, newSeenDeps, deps)
    } yield ret
}
