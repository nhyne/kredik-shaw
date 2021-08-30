package prom

import zio.metrics.prometheus.helpers.counter
import zio.metrics.prometheus.{Counter, Metric, Registry}
import zio.{Has, Task, UIO, ULayer, ZIO, ZLayer}

object Metrics {

  type MetricsService = Has[Service]
  trait Service {

    def namespaceCreated(): Task[Unit]
  }

  val live: ZLayer[Registry, Throwable, Has[Service]] = ZLayer.fromEffect {
    val namespaceMetricName = "namespaces"
    for {
      c <- counter.register(
        namespaceMetricName,
        "the number of namespaces created"
      )
      layer = new Service {
        private val metricsMap: Map[String, Counter] =
          Map(namespaceMetricName -> c)
        override def namespaceCreated(): Task[Unit] =
          metricsMap(namespaceMetricName).inc()
      }
    } yield layer
  }
}
