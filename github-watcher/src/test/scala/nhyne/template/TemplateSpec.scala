package nhyne.template

import com.coralogix.zio.k8s.quicklens._
import com.softwaremill.quicklens._
import com.coralogix.zio.k8s.model.apps.v1.{Deployment, DeploymentSpec}
import com.coralogix.zio.k8s.model.pkg.apis.meta.v1.LabelSelector
import com.coralogix.zio.k8s.model.core.v1.{
  Container,
  EnvVar,
  PodSpec,
  PodTemplateSpec
}
import nhyne.template.Template.updateDeployEnvVars
import zio.test.environment.TestEnvironment
import zio.test._
import zio.test.Assertion._

object TemplateSpec extends DefaultRunnableSpec {

  def spec: ZSpec[TestEnvironment, Any] =
    suite("template service")(
      testM("injecting environment vars updates deploy properly") {
        val deploy = Deployment(
          spec = DeploymentSpec(
            template = PodTemplateSpec(
              spec = PodSpec(
                containers = Vector(
                  Container(
                    name = "test-container",
                    env = Vector(
                      EnvVar("something", "cool")
                    )
                  )
                )
              )
            ),
            selector = LabelSelector()
          )
        )

        val updatedDeploy =
          updateDeployEnvVars(deploy, Vector(EnvVar("another", "var")))
        assertM(updatedDeploy)(
          equalTo(
            deploy
              .modify(_.spec.each.template.spec.each.containers.each.each.env)
              .setTo(
                Vector(EnvVar("something", "cool"), EnvVar("another", "var"))
              )
          )
        )
      },
      testM("we do not inject environment vars twice") {
        val deploy = Deployment(
          spec = DeploymentSpec(
            template = PodTemplateSpec(
              spec = PodSpec(
                containers = Vector(
                  Container(
                    name = "test-container",
                    env = Vector(
                      EnvVar("something", "cool")
                    )
                  )
                )
              )
            ),
            selector = LabelSelector()
          )
        )

        val updatedDeploy =
          updateDeployEnvVars(deploy, Vector(EnvVar("something", "cool")))
        assertM(updatedDeploy)(equalTo(deploy))
      }
    )

}
