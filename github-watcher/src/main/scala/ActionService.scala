import zio.{Has, ZIO, ZLayer}
import zio.console.{Console, putStrLn}

object ActionService {
  type ActionService = Has[Service]

  val live: ZLayer[Console, Throwable, ActionService] = ZLayer.succeed(
    new Service {
      override def performAction(action: TopicAction): ZIO[Console, Throwable, Unit] = action match {
        case TopicAction.K8sAction(config) => putStrLn("k8s action")
        case TopicAction.PullRequestEnvironmentAction(config) => putStrLn("pr actoin")
        case TopicAction.ArgoSyncAction(config) => putStrLn("argo action")
      }
    }
  )

  trait Service {
    def performAction(action: TopicAction): ZIO[Console, Throwable, Unit]
  }

  final case class K8sActionConfig(clusterName: String)

  final case class PullRequestEnvironmentConfig(prNumber: Int)

  final case class ArgoConfig(manifestsPath: String)

  sealed trait TopicAction

  case object TopicAction {
    final case class K8sAction(config: K8sActionConfig) extends TopicAction

    final case class PullRequestEnvironmentAction(config: PullRequestEnvironmentConfig) extends TopicAction

    final case class ArgoSyncAction(config: ArgoConfig) extends TopicAction
  }
}
