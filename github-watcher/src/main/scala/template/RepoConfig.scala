package template

import Template.TemplateCommand
import zio._
import zio.config._
import zio.config.derivation.describe
import zio.config.magnolia.DeriveConfigDescriptor

import scala.collection.immutable.Set
import java.io.File

object RepoConfig {

  object DependencyWalker {
    trait Service {
      def walkDependencies(startingConfig: RepoConfig): Task[Set[RepoConfig]]
      def dependencyToRepo(dep: Dependency): Task[RepoConfig]
    }

    // TODO: Want to pull in the functions in the parent object, just not sure about needing to mock all of them in a test or just the Dependency -> RepoConfig
    //    maybe the Dependency -> RepoConfig function could be its own service?
    val live = ZLayer.succeed(
      new Service {
        // hoping that a starting point doesn't also end up as its own dep?
        override def walkDependencies(startingConfig: RepoConfig): Task[Set[RepoConfig]] = ???

        override def dependencyToRepo(dep: Dependency): Task[RepoConfig] = ???
      }
    )

  }

  def walkDependencies(startingConfig: RepoConfig): Task[Set[RepoConfig]] = walkDependencies(startingConfig.dependencies.getOrElse(Set.empty), Set.empty, Set(startingConfig))

  /* TODO: We want to walk the dependency graph and apply the watcher configs for each one
   * This is probably the most important part
   * In general we just want to walk through each dependency, turn it into a RepoConfig, then add it to a set of seen deps
   *
   * TODO: Make this parallel
   * TODO: Make this tail recursive
   */

  private def walkDependencies(
      unseenDeps: Set[Dependency],
      seenDeps: Set[Dependency],
      configs: Set[RepoConfig]
  ): Task[Set[RepoConfig]] = for {
    newUnseenDepsRef <- Ref.make(Set.empty[Dependency])
    seenDepsRef <- Ref.make(seenDeps)
    processedConfigsRef <- Ref.make(configs)
    _ <- ZIO.foreach(unseenDeps)(dep => {
      for {
        seen <- seenDepsRef.get
        shouldProcess = !seen.contains(dep)
        maybeNewDependenciesToProcess <-
          if (shouldProcess) dependencyToRepoConfig(dep).flatMap { rc =>
            processedConfigsRef.get.flatMap { processedConfigs =>
              processedConfigsRef
                .set(processedConfigs + rc)
                .flatMap(_ => ZIO.succeed(rc.dependencies))
            }
          }
          else ZIO.none
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
    abc <-
      if (newUnseenDeps.isEmpty) ZIO.succeed(deps)
      else walkDependencies(newUnseenDeps, newSeenDeps, deps)
  } yield abc

  // TODO: Provide a real version of this function and use this version (maybe with a map lookup?) as a test implementation
  // TODO: move this and the walk deps function to a layer
  def dependencyToRepoConfig(dependency: Dependency): Task[RepoConfig] = {
    dependency.branch match {
      case Some("circular") => Task.succeed(RepoConfig(new File("itsacircle"), TemplateCommand.Kustomize, Some(Set(Dependency("circular", Some("circular"))))))
      case Some(_) => ???
      case None => Task.succeed(RepoConfig(new File("aaa"), TemplateCommand.Helm, None))
    }
  }

  val repoConfigDescriptor: ConfigDescriptor[RepoConfig] = DeriveConfigDescriptor.descriptor[RepoConfig]
}

@describe("this config is for a repo watcher")
final case class RepoConfig(
    resourceFolder: File,
    templateCommand: TemplateCommand,
    // TODO: Issue here with implicits and this param
    // Believe it has to do with nested configs??
    dependencies: Option[Set[Dependency]]
)

// TODO: Would be nice if this used refinement types to perform some validations
//    https://zio.github.io/zio-config/docs/refined/refined_index
@describe("this config is for a dependency of a repo")
final case class Dependency(
    repoUrl: String,
    branch: Option[String]
)
