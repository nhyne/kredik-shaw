package prom

import zio.metrics.prometheus.helpers.counter
import zio.metrics.prometheus.{Counter, Metric, Registry}
import zio.{Has, Task, UIO, ULayer, ZIO, ZLayer}

object Metrics {

  type MetricsService = Has[Service]
  trait Service {
    def namespaceCreated(repository: String): Task[Unit]
  }

  /*
  namespaces deleted
  template commands being used
  events received
  comments posted
  stale PR namespaces deleted
   */
  val live: ZLayer[Registry, Throwable, Has[Service]] = ZLayer.fromEffect {
    val namespaceMetricName = "namespaces_created"
    for {
      c <- counter.register(
        namespaceMetricName,
        Array("repository"),
        "the number of namespaces created"
      )
      layer = new Service {
        private val metricsMap: Map[String, Counter] =
          Map(namespaceMetricName -> c)
        override def namespaceCreated(repository: String): Task[Unit] =
          metricsMap(namespaceMetricName).inc(Array(repository))
      }
    } yield layer
  }
}
