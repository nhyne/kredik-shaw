package template

import Template.TemplateCommand
import sttp.model.Uri
import zio._
import zio.config.ConfigDescriptor
import zio.config.magnolia.DeriveConfigDescriptor.descriptor
import zio.config.magnolia.Descriptor
import scala.collection.immutable.Set
import scala.annotation.tailrec

import java.io.File

object RepoConfig {
  implicit val repoDescriptor: Descriptor[RepoConfig] = Descriptor(
    descriptor[RepoConfig]
  )
  implicit val dependencyDescriptor: Descriptor[Dependency] = Descriptor(
    descriptor[Dependency]
  )

  val repoConfigDescriptor: ConfigDescriptor[RepoConfig] =
    descriptor[RepoConfig]
  val dependencyConfigDescriptor: ConfigDescriptor[Dependency] =
    descriptor[Dependency]

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
    // clone repo into temp Dir
    // read in its dependencies
    // clone those deps as well
    // continue process until all deps are downloaded
    ???
  }
}

final case class RepoConfig(
    resourceFolder: File,
    templateCommand: TemplateCommand,
    // TODO: Issue here with implicits and this param
    // Believe it has to do with nested configs??
    dependencies: Option[Set[Dependency]]
)

// This is a graph of deps
final case class Dependency(
    repoUrl: Uri,
    branch: Option[String]
)
