import zio.logging.Logging
import zio.ZEnv
import nhyne.prometheus.Metrics.MetricsService

package object nhyne {
  type BaseEnv = Logging with MetricsService with ZEnv
}
