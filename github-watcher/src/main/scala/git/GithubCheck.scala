package git

import git.GitEvents.{Branch, Repository}
import zio.json._

import java.time.ZonedDateTime

object GithubCheck {

  // conclusion is required if we provide a completedAt or a status of completed

  // TODO: make this apply function private to force usage of the smart constructors
  final case class Check private (
      @jsonField("repository_owner") repositoryOwner: String,
      @jsonField("repository_name") repositoryName: String,
      name: String,
      branch: Branch,
      status: Status,
      conclusion: Option[Conclusion],
      @jsonField("started_at") startedAt: ZonedDateTime,
      @jsonField("completed_at") completedAt: Option[ZonedDateTime],
      @jsonField("details_url") detailsUrl: String,
      @jsonField("external_id") externalId: String,
      accept: String = "application/vnd.github.v3+json"
  )

  object Check {
    def checkStarted(
        repository: Repository,
        name: String,
        branch: Branch
    ): Check = ???

    // TODO: Should this take the previous check so we maintain the startedAt?
    //    probably don't need to have a start time for a completed check?
    def checkComplete(
        repository: Repository,
        name: String,
        branch: Branch,
        status: Status
    ): Check = {
      val now = ZonedDateTime.now()
//      Check(repositoryOwner = repository.owner,
//        repositoryName = repository.name,
//        name = name,
//        branch = branch,
//        status = status,
//        conclusion = Some(Conclusion.Neutral),
//
//      )
      ???
    }
  }

  sealed trait Status
  object Status {
    case object Queued extends Status
    case object InProgress extends Status
    case object Complete extends Status
  }

  sealed trait Conclusion
  object Conclusion {
    case object ActionRequired extends Conclusion
    case object Cancelled extends Conclusion
    case object Failure extends Conclusion
    case object Neutral extends Conclusion
    case object Success extends Conclusion
    case object Skipped extends Conclusion
    case object Stale extends Conclusion
    case object TimedOut extends Conclusion
  }

}
