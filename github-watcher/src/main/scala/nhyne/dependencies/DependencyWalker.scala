package nhyne.dependencies

import nhyne.git.GitCli
import nhyne.template.{ Dependency, RepoConfig }
import nhyne.template.RepoConfig.ImageTag
import zio.logging.Logging
import zio.{ Ref, ZEnv, ZIO, ZLayer }
import zio.nio.core.file.Path
import nhyne.Errors.KredikError
import nhyne.config.ApplicationConfig

import scala.collection.immutable.Set

trait DependencyWalker {
  def walkDependencies(
    startingConfig: RepoConfig,
    startingRepoPath: Path,
    startingSha: String, // TODO: Make this something besides a string
    workingDir: Path
  ): ZIO[ZEnv with DependencyConverter with ApplicationConfig with GitCli with Logging, KredikError, Map[
    RepoConfig,
    (Path, ImageTag)
  ]]
}

object DependencyWalker {

  // TODO: Want to pull in the functions in the parent object, just not sure about needing to mock all of them in a test or just the Dependency -> RepoConfig
  //    maybe the Dependency -> RepoConfig function could be its own service?
  val live = ZLayer.succeed(
    new DependencyWalker {
      override def walkDependencies(
        startingConfig: RepoConfig,
        startingRepoPath: Path,
        startingSha: String,
        workingDir: Path
      ): ZIO[
        ZEnv with DependencyConverter with ApplicationConfig with GitCli with Logging,
        KredikError,
        Map[RepoConfig, (Path, ImageTag)]
      ] =
        walkDeps(
          workingDir,
          startingConfig.dependencies.getOrElse(Set.empty),
          Set.empty,
          Map(startingConfig -> ((startingRepoPath, ImageTag(startingSha))))
        )

    }
  )

  /*
   * TODO: Make this parallel
   * TODO: Make this tail recursive
   */
  private def walkDeps(
    workingDir: Path,
    unseenDeps: Set[Dependency],
    seenDeps: Set[Dependency],
    configs: Map[RepoConfig, (Path, ImageTag)]
  ): ZIO[
    ZEnv with DependencyConverter with GitCli with ApplicationConfig with Logging,
    KredikError,
    Map[
      RepoConfig,
      (Path, ImageTag)
    ]
  ] =
    for {
      newUnseenDepsRef    <- Ref.make(Set.empty[Dependency])
      seenDepsRef         <- Ref.make(seenDeps)
      processedConfigsRef <- Ref.make(configs)
      _                   <- ZIO.foreach_(unseenDeps) { dep =>
                               for {
                                 seen                          <- seenDepsRef.get
                                 shouldProcess                  = !seen.contains(dep)
                                 maybeNewDependenciesToProcess <- if (shouldProcess)
                                                                    ZIO.service[DependencyConverter].flatMap { depService =>
                                                                      depService.dependencyToRepoConfig(dep, workingDir).flatMap {
                                                                        case (rc, path) =>
                                                                          processedConfigsRef.get.flatMap { processedConfigs =>
                                                                            processedConfigsRef
                                                                              .set(
                                                                                processedConfigs + (rc -> (
                                                                                  (
                                                                                    path,
                                                                                    dep.imageTag
                                                                                      .getOrElse(ImageTag("latest"))
                                                                                  )
                                                                                ))
                                                                              )
                                                                              .as(rc.dependencies)
                                                                          }
                                                                      }
                                                                    }
                                                                  else ZIO.none
                                 nextSeen                       = seen + dep
                                 _                             <- seenDepsRef.set(nextSeen)
                                 depsToProcess                  = maybeNewDependenciesToProcess
                                                                    .getOrElse(Set.empty)
                                                                    .diff(nextSeen)
                                 newUnseenDeps                 <- newUnseenDepsRef.get
                                 _                             <- newUnseenDepsRef.set(newUnseenDeps ++ depsToProcess)
                               } yield ()
                             }
      newUnseenDeps       <- newUnseenDepsRef.get
      newSeenDeps         <- seenDepsRef.get
      deps                <- processedConfigsRef.get
      ret                 <- if (newUnseenDeps.isEmpty) ZIO.succeed(deps)
                             else walkDeps(workingDir, newUnseenDeps, newSeenDeps, deps)
    } yield ret
}
