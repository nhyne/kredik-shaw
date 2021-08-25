package template

import Template.TemplateCommand
import zio._
import zio.config._
import zio.config.derivation.describe
import zio.config.magnolia.DeriveConfigDescriptor

import scala.collection.immutable.Set
import scala.annotation.tailrec
import java.io.File

object RepoConfig {

  // hoping that a starting point doesn't also end up as its own dep?
  def walkDependencies(startingConfig: RepoConfig): Task[Set[RepoConfig]] = walkDependencies(startingConfig.dependencies.getOrElse(Set.empty), Set.empty, Set(startingConfig))

//  val dependencyConfigDescriptor = descriptor[Dependency]

  /* TODO: We want to walk the dependency graph and apply the watcher configs for each one
   * This is probably the most important part
   * In general we just want to walk through each dependency, turn it into a RepoConfig, then add it to a set of seen deps
   *
   * TODO: Make this parallel
   * TODO: Make this tail recursive
   */

//  @tailrec
  def walkDependencies(
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

  def dependencyToRepoConfig(dependency: Dependency): Task[RepoConfig] = {
    dependency.branch match {
      case Some(_) => Task.succeed(RepoConfig(new File("bbb"), TemplateCommand.Kustomize, Some(Set(Dependency("nhyne.dev", None)))))
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
