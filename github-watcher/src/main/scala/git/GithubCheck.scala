package git

import git.GitEvents.{Branch, Repository}

import java.time.ZonedDateTime

object GithubCheck {

  // conclusion is required if we provide a startedAt or a status of completed

  // TODO: make this apply function private to force usage of the smart constructors
  final case class Check(
      repositoryOwner: String,
      repositoryName: String,
      name: String,
      branch: Branch,
      status: Status,
      conclusion: Option[Conclusion],
      startedAt: ZonedDateTime,
      completedAt: Option[ZonedDateTime],
      detailsUrl: String,
      externalId: String,
      accept: String = "application/vnd.github.v3+json"
  )

  object Check {
    def checkStarted(
        repository: Repository,
        name: String,
        branch: Branch
    ): Check = ???

    // TODO: Should this take the previous check so we maintain the startedAt?
    def checkComplete(
        repository: Repository,
        name: String,
        branch: Branch,
        status: Status
    ): Check = ???
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
