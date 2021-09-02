package git

import git.Authentication.AuthenticationScheme
import zio._
import zio.test._
import zio.test.Assertion.equalTo
import zio.test.environment.{TestEnvironment, TestSystem}
import zio.test.environment.TestSystem.Data
import zio.magic._
import sttp.client3.httpclient.zio.HttpClientZioBackend

object AuthenticationSpec extends DefaultRunnableSpec {
  private val testBearerToken = "valid"
  private val testUser = "valid"
  private val testToken = "testtoken"
  val spec: ZSpec[TestEnvironment, Any] =
    suite("git authentication")(
      testM("valid bearer token") {
        val envData = Data(envs = Map("GITHUB_BEARER_TOKEN" -> testBearerToken))
        val gitAuth = Authentication.live
        for {
          authValue <- ZIO
            .service[Authentication.Service]
            .flatMap(auth => auth.getAuthentication())
            .inject(
              gitAuth,
              HttpClientZioBackend.layer(),
              TestSystem.live(envData),
              GithubApiSpec.test
            )
        } yield assert(authValue)(
          equalTo(AuthenticationScheme.Bearer(testBearerToken))
        )
      },
      testM("valid user and token") {
        val envData = Data(envs =
          Map("GITHUB_USERNAME" -> testUser, "GITHUB_TOKEN" -> testToken)
        )
        val gitAuth = Authentication.live
        for {
          authValue <- ZIO
            .service[Authentication.Service]
            .flatMap(auth => auth.getAuthentication())
            .inject(
              gitAuth,
              HttpClientZioBackend.layer(),
              TestSystem.live(envData),
              GithubApiSpec.test
            )
        } yield assert(authValue)(
          equalTo(AuthenticationScheme.Basic(testUser, testToken))
        )

      }
    )
}
