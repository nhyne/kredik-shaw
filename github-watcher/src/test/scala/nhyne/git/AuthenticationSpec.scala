package nhyne.git

import nhyne.config.ApplicationConfig
import nhyne.git.Authentication.AuthenticationScheme
import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.environment.{ TestEnvironment, TestSystem }
import zio.test.environment.TestSystem.Data
import zio.magic._
import sttp.client3.httpclient.zio.HttpClientZioBackend
import zio.blocking.Blocking

object AuthenticationSpec extends DefaultRunnableSpec {
  private val testBearerToken           = "valid"
  private val testUser                  = "valid"
  private val testToken                 = "testtoken"
  val spec: ZSpec[TestEnvironment, Any] =
    suite("git authentication")(
      testM("valid bearer token") {
        val envData = Data(envs = Map("GITHUB_BEARER_TOKEN" -> testBearerToken))
        for {
          authValue <- ZIO
                         .service[Authentication]
                         .flatMap(auth => auth.getAuthentication())
                         .inject(
                           HttpClientZioBackend.layer(),
                           TestSystem.live(envData),
                           GithubApiSpec.test,
                           Blocking.live,
                           GitCliSpec.test,
                           ApplicationConfig.test,
                           Authentication.live
                         )
        } yield assert(authValue)(
          equalTo(AuthenticationScheme.Bearer(testBearerToken))
        )
      },
      testM("valid user and token") {
        val envData = Data(envs = Map("GITHUB_USERNAME" -> testUser, "GITHUB_TOKEN" -> testToken))
        for {
          authValue <- ZIO
                         .service[Authentication]
                         .flatMap(auth => auth.getAuthentication())
                         .inject(
                           GithubApiSpec.test,
                           GitCliSpec.test,
                           Blocking.live,
                           ApplicationConfig.test,
                           HttpClientZioBackend.layer(),
                           TestSystem.live(envData),
                           Authentication.live
                         )
        } yield assert(authValue)(
          equalTo(AuthenticationScheme.Basic(testUser, testToken))
        )
      }
      //      testM("missing all credential variables") {
      //        assertM(ZIO
      //          .service[Authentication.Service]
      //          .flatMap(auth => auth.getAuthentication())
      //          .inject(
      //            TestSystem.live(Data()),
      //            GithubApiSpec.test,
      //            HttpClientZioBackend.layer(),
      //            Authentication.live
      //          ))()
      //        )
      //      }
    )
}
