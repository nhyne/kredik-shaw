package nhyne.git

import zio.json._

object GitEvents {

  // TODO: zio-json decoder for this trait
  //   issue with this is that there is nothing to disambiguate the json bodies other than the bodies themselves
  //  implicit val webhookEventDecoder: JsonDecoder[WebhookEvent] = DeriveJsonDecoder.gen[WebhookEvent]

  sealed trait WebhookEvent {
    def baseRepo(): Repository
  }

  object WebhookEvent {

    final case class IssueCommentEvent(
      action: ActionVerb,
      comment: Comment,
      repository: Repository,
      issue: Issue
    ) extends WebhookEvent {
      def getBody()  = comment.body
      def baseRepo() = repository
    }

    final case class PullRequestEvent(
      action: ActionVerb,
      number: Int,
      @jsonField("pull_request") pullRequest: PullRequest
    ) extends WebhookEvent {
      def baseRepo() = pullRequest.base.repo
    }
  }

  sealed trait DeployableGitState {
    def getBaseRepoName: String
    def getBaseFullName: String
    def getSha: String
  }

  final case class Branch(
    ref: String,
    sha: String,
    repo: Repository // there are a lot more fields than just these
  ) extends DeployableGitState {
    def getBaseRepoName: String = repo.name
    def getBaseFullName: String = repo.fullName
    def getSha: String          = sha
  }

  object Branch {
    def fromString(branchName: String, repo: Repository) =
      Branch(branchName, branchName, repo)
  }

  final case class PullRequest(
    url: String,
    id: Long,
    number: Int,
    state: String, // TODO: Should be a union type when it is used
    head: Branch,
    base: Branch
  ) extends DeployableGitState {
    def getBaseFullName: String = base.repo.fullName
    def getSha: String          = head.sha
    def getBaseRepoName: String = base.repo.name
    def getBaseOwner: String    = base.repo.owner.login
  }
  final case class Repository(
    name: String,
    @jsonField("full_name") fullName: String,
    owner: Owner,
    @jsonField("html_url") htmlUrl: String,
    @jsonField("ssh_url") sshUrl: String,
    @jsonField("clone_url") cloneUrl: String
  )
  object Repository {
    def fromNameAndOwner(name: String, owner: String): Repository =
      Repository(
        name,
        s"$name/$owner",
        Owner(owner),
        htmlUrl = s"https://github.com/$owner/$name",
        sshUrl = s"git@github.com:$owner/$name.git",
        cloneUrl = s"https://github.com/$owner/$name.git"
      )
  }
  final case class Owner(login: String)
  final case class Label()
  // TODO: Do I want to also read the time is was created and see if it was a recent comment?
  final case class Comment(url: String, body: String)
  final case class Issue(@jsonField("number") prNumber: Int)

  final case class GitRef(
    `object`: GitRefObject
  )

  final case class GitRefObject(
    sha: String
  )

  sealed trait ActionVerb

  object ActionVerb {
    case object Opened extends ActionVerb

    case object Synchronize extends ActionVerb

    case object Closed extends ActionVerb

    case object Created extends ActionVerb

    final case class Unknown(`type`: String) extends ActionVerb

    implicit val actionVerbDecoder: JsonDecoder[ActionVerb] =
      JsonDecoder[String].map {
        case "opened"      => Opened
        case "synchronize" => Synchronize
        case "closed"      => Closed
        case "created"     => Created
        case actionType    => Unknown(actionType)
      }
  }

  implicit val gitRefObjectDecoder: JsonDecoder[GitRefObject] =
    DeriveJsonDecoder.gen[GitRefObject]
  implicit val gitRefDecoder: JsonDecoder[GitRef]             =
    DeriveJsonDecoder.gen[GitRef]

  implicit val issueDecoder: JsonDecoder[Issue]                                    = DeriveJsonDecoder.gen[Issue]
  implicit val ownerDecoder: JsonDecoder[Owner]                                    = DeriveJsonDecoder.gen[Owner]
  implicit val repositoryDecoder: JsonDecoder[Repository]                          =
    DeriveJsonDecoder.gen[Repository]
  implicit val branchDecoder: JsonDecoder[Branch]                                  =
    DeriveJsonDecoder.gen[Branch]
  implicit val pullRequestDecoder: JsonDecoder[PullRequest]                        =
    DeriveJsonDecoder.gen[PullRequest]
  implicit val pullRequestEventDecoder: JsonDecoder[WebhookEvent.PullRequestEvent] =
    DeriveJsonDecoder.gen[WebhookEvent.PullRequestEvent]
  implicit val commentDecoder: JsonDecoder[Comment]                                =
    DeriveJsonDecoder.gen[Comment]
  implicit val issueCommentDecoder: JsonDecoder[WebhookEvent.IssueCommentEvent]    =
    DeriveJsonDecoder.gen[WebhookEvent.IssueCommentEvent]

  implicit val labelDecoder: JsonDecoder[Label] = DeriveJsonDecoder.gen[Label]

}
