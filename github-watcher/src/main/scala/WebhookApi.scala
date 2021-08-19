import zhttp.service._
import zhttp.http._
import zio._
import zio.blocking.{Blocking, effectBlocking}
import zio.console.{Console, putStrLn}
import zio.process.{Command, CommandError}
import com.coralogix.zio.k8s.client.K8sFailure
import com.coralogix.zio.k8s.client.v1.namespaces.{Namespaces, create, get}
import com.coralogix.zio.k8s.model.core.v1.Namespace
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import zio.json._

import java.nio.file.Files
import java.io.File

object WebhookApi {

  private val PORT = 8090

  private val apiRoot = Root / "api" / "sre-webhook"

  private val fooBar: HttpApp[Console, HttpError] = HttpApp.collectM {
    case req@Method.POST -> `apiRoot` => handlePostRequest(req).mapBoth(cause => HttpError.InternalServerError(cause = Some(new Throwable(cause))), body => Response.text(body.toString))
  }

  val server: Server[Console, HttpError] = Server.port(PORT) ++ Server.app(fooBar) ++ Server.maxRequestSize(100 * 1024)

  def handlePostRequest(request: Request) = request.getBodyAsString match {

    case Some(body) => for {
      _ <- putStrLn(body).mapError(_.toString)
      pullRequestEvent <- ZIO.fromEither(body.fromJson[PullRequestEvent])
    } yield pullRequestEvent
    case None => ZIO.fail("Did not receive a request body")

  }

  def performEventAction(event: PullRequestEvent) = {
    event.action match {
      case PullRequestAction.Opened => ZIO.succeed("open")
      case PullRequestAction.Synchronize => ZIO.succeed("sync")
      case PullRequestAction.Closed => ZIO.succeed("close")
    }
  }

//  private def openedEvent(event: PullRequestEvent) = for {
//    _ <- gitCloneAndMerge()
//  }

  private def gitClone(repo: String) = Command("git", "clone", repo)

  private def gitCheckoutBranch(branch: String) = Command("git", "checkout", branch)

  private def gitMerge(target: String) = Command("git", "merge", target)

  private def createRepoCloneDir(repo: String) = ZIO.effect {
    Files.createTempDirectory(s"pr-$repo").toFile
  }

  // TODO: Should do this work in a temp directory that gets cleaned up later
  def gitCloneAndMerge(organization: String, repo: String, branch: String, target: String): ZIO[Blocking with Console, Throwable, ExitCode] = for {
    workingDir <- createRepoCloneDir(s"$organization-$repo")
    _ <- gitClone(s"https://github.com/$organization/$repo").workingDirectory(workingDir).run
    repoDir = new File(s"${workingDir.getAbsolutePath}/$repo")
    _ <- gitCheckoutBranch(branch).workingDirectory(repoDir).run
    exitCode <- gitMerge(target).workingDirectory(repoDir).run.exitCode
  } yield exitCode

  def cleanupTempDir(dir: File): RIO[Blocking, Boolean] = {
    effectBlocking(dir.delete())
  }

  def createPRNamespace(prNumber: Int, repo: String): ZIO[Namespaces, K8sFailure, Namespace] = {
    val namespaceName = s"$repo-pr-$prNumber"
    val prNamespace = Namespace(metadata = ObjectMeta(
      name = Some(namespaceName)
    ))
    for {
      ns <- get(namespaceName).foldM(
        _ => create(prNamespace),
        success => ZIO.succeed(success))
    } yield ns
  }


  final case class PullRequestEvent(action: PullRequestAction, number: Int, @jsonField("pull_request") pullRequest: PullRequest)

  final case class PullRequest(
                                url: String,
                                id: Long,
                                number: Int,
                                state: String,
                                head: Branch,
                                base: Branch
                              )

  final case class Branch(ref: String, sha: String, repo: Repository) // there are a lot more fields than just these
  final case class Repository(name: String, @jsonField("full_name") fullName: String)
  // TODO: Would be better if I can just pull the owner from the request body. Not sure if there's something different between "owner" and "organization"
//  final case class Owner()

  implicit val repositoryDecoder: JsonDecoder[Repository] = DeriveJsonDecoder.gen[Repository]
  implicit val branchDecoder: JsonDecoder[Branch] = DeriveJsonDecoder.gen[Branch]
  implicit val pullRequestDecoder: JsonDecoder[PullRequest] = DeriveJsonDecoder.gen[PullRequest]
  implicit val pullRequestEventDecoder: JsonDecoder[PullRequestEvent] = DeriveJsonDecoder.gen[PullRequestEvent]

  sealed trait PullRequestAction

  object PullRequestAction {
    case object Opened extends PullRequestAction

    case object Synchronize extends PullRequestAction

    case object Closed extends PullRequestAction

    implicit val prActionDecoder: JsonDecoder[PullRequestAction] = JsonDecoder[String].map {
      case "opened" => Opened
      case "synchronize" => Synchronize
      case "closed" => Closed
      case _ => ???
    }
  }


}
