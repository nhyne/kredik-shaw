import zhttp.service._
import zhttp.http._
import zio._
import zio.blocking.Blocking
import zio.console.{Console, putStrLn}
import zio.process.{Command, CommandError}
import com.coralogix.zio.k8s.client.config._
import com.coralogix.zio.k8s.client.config.httpclient._
import com.coralogix.zio.k8s.client.{K8sFailure, model}
import com.coralogix.zio.k8s.client.v1.namespaces.{Namespaces, create}
import com.coralogix.zio.k8s.model.core.v1.Namespace
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.ObjectMeta
import zio.system.System

import java.io.File

object WebhookApi {

  private val PORT = 8090

  private val apiRoot = Root / "api" / "sre-webhook"

  private val fooBar: HttpApp[Console, HttpError] = HttpApp.collectM {
    case req@Method.POST -> `apiRoot` / "pull-request" => handlePostRequest(req)
  }

  val server: Server[Console, HttpError] = Server.port(PORT) ++ Server.app(fooBar) ++ Server.maxRequestSize(10 * 1024)

  def handlePostRequest(request: Request): ResponseM[Console, HttpError] = for {
    a <- putStrLn(request.toString).mapBoth(
      _ => HttpError.NotImplemented("whoops"),
      _ => Response.text("OK")
    )
  } yield a

  private def gitClone(repo: String) = Command("git", "clone", repo)

  private def gitCheckoutBranch(branch: String) = Command("git", "checkout", branch)

  private def gitMerge(target: String) = Command("git", "merge", target)

  def gitCloneAndMerge(organization: String, repo: String, branch: String, target: String): ZIO[Blocking with Console, Exception, ExitCode] = for {
    _ <- gitClone(s"https://github.com/$organization/$repo").run
    workingDir = new File(repo)
    _ <- gitCheckoutBranch(branch).workingDirectory(workingDir).run
    exitCode <- gitMerge(target).workingDirectory(workingDir).run.exitCode
  } yield exitCode

  def createPRNamespace(prNumber: Int, repo: String): ZIO[Namespaces, K8sFailure, Namespace] = {
    val newNamespace = Namespace(metadata = Some(ObjectMeta(
      name = Some(s"$repo-PR-$prNumber")
    )))
    create(newNamespace)
  }

  final case class PushEvent(
                              ref: String

                            )

}
