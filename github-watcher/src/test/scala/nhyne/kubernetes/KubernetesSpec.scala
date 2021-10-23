package nhyne.kubernetes

import nhyne.git.GitEvents.{ Branch, Owner, PullRequest, Repository }
import zio.test._
import zio.test.Assertion.equalTo
import zio.test.environment.TestEnvironment

object KubernetesSpec extends DefaultRunnableSpec {
  val spec: ZSpec[TestEnvironment, Any] =
    suite("kubernetes")(
      test("basic namespace name creation") {
        val branch = Branch(
          "ref",
          "sha",
          Repository("name", "fullName", Owner("owner"), "url", "url", "url")
        )
        val nsName = Kubernetes.namespaceName(
          PullRequest("url", 123, 5, "state", branch, branch)
        )
        assert(nsName)(equalTo("pr-name-5"))
      },
      test("long namespace name creation") {
        val branch = Branch(
          "ref",
          "sha",
          Repository(
            "name" * 100,
            "fullName",
            Owner("owner"),
            "url",
            "url",
            "url"
          )
        )
        val nsName = Kubernetes.namespaceName(
          PullRequest("url", 123, 5, "state", branch, branch)
        )
        assert(nsName)(equalTo(("pr-" + "name" * 100).take(63)))

      }
    )
}
