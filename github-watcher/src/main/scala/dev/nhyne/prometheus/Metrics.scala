package dev.nhyne.prometheus

import zio.metrics.prometheus.helpers.counter
import zio.metrics.prometheus.{Counter, Registry}
import zio.{Has, Task, ZLayer}

object Metrics {

  type MetricsService = Has[Service]

  trait Service {
    def namespaceCreated(repository: String): Task[Unit]
    def namespaceDeleted(repository: String): Task[Unit]
  }

  val live: ZLayer[Registry, Throwable, Has[Service]] = ZLayer.fromEffect {
    val createdNamespaces = "namespaces_created"
    val deletedNamespaces = "namespaces_deleted"
    for {
      namespacesCreated <- counter.register(
        createdNamespaces,
        Array("repository"),
        "the number of namespaces created"
      )
      namespacesDeleted <- counter.register(
        deletedNamespaces,
        Array("repository"),
        "the number of namespaces deleted"
      )
      layer = new Service {
        private val metricsMap: Map[String, Counter] =
          Map(
            createdNamespaces -> namespacesCreated,
            deletedNamespaces -> namespacesDeleted
          )

        override def namespaceCreated(repository: String): Task[Unit] =
          metricsMap(createdNamespaces).inc(Array(repository))

        override def namespaceDeleted(repository: String): Task[Unit] =
          metricsMap(deletedNamespaces).inc(Array(repository))
      }
    } yield layer
  }
}
