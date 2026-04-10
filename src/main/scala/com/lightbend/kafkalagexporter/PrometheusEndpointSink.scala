/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import com.lightbend.kafkalagexporter.MetricsSink._
import com.lightbend.kafkalagexporter.EndpointSink.ClusterGlobalLabels
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{CollectorRegistry, Counter, Gauge}

import scala.util.Try

object PrometheusEndpointSink {
  def apply(
      sinkConfig: PrometheusEndpointSinkConfig,
      definitions: MetricDefinitions,
      clusterGlobalLabels: ClusterGlobalLabels,
      registry: CollectorRegistry
  ): MetricsSink = {
    Try(
      new PrometheusEndpointSink(
        sinkConfig: PrometheusEndpointSinkConfig,
        definitions,
        clusterGlobalLabels,
        new HTTPServer(sinkConfig.port),
        registry
      )
    )
      .fold(
        t => throw new Exception("Could not create Prometheus Endpoint", t),
        sink => sink
      )
  }

  def apply(
      sinkConfig: PrometheusEndpointSinkConfig,
      definitions: MetricDefinitions,
      clusterGlobalLabels: ClusterGlobalLabels,
      server: HTTPServer,
      registry: CollectorRegistry
  ): MetricsSink = {
    Try(
      new PrometheusEndpointSink(
        sinkConfig: PrometheusEndpointSinkConfig,
        definitions,
        clusterGlobalLabels,
        server,
        registry
      )
    )
      .fold(
        t => throw new Exception("Could not create Prometheus Endpoint", t),
        sink => sink
      )
  }
}

class PrometheusEndpointSink private (
    sinkConfig: PrometheusEndpointSinkConfig,
    definitions: MetricDefinitions,
    clusterGlobalLabels: ClusterGlobalLabels,
    server: HTTPServer,
    registry: CollectorRegistry
) extends EndpointSink(clusterGlobalLabels) {
  DefaultExports.initialize()

  private val gauges: Map[GaugeDefinition, Gauge] =
    definitions
      .collect { case d: GaugeDefinition => d }
      .filter(d => sinkConfig.metricWhitelist.exists(d.name.matches))
      .map { d =>
        d -> Gauge
          .build()
          .name(d.name)
          .help(d.help)
          .labelNames(globalLabelNames ++ d.labels: _*)
          .register(registry)
      }
      .toMap

  private val counters: Map[CounterDefinition, Counter] =
    definitions
      .collect { case d: CounterDefinition => d }
      .filter(d => sinkConfig.metricWhitelist.exists(d.name.matches))
      .map { d =>
        d -> Counter
          .build()
          .name(d.name)
          .help(d.help)
          .labelNames(globalLabelNames ++ d.labels: _*)
          .register(registry)
      }
      .toMap

  // Tracks last reported absolute value per (CounterDefinition, labelValues) to compute deltas
  private var counterPreviousValues
      : Map[(CounterDefinition, Seq[String]), Double] = Map.empty

  override def report(m: MetricValue): Unit = {
    if (sinkConfig.metricWhitelist.exists(m.definition.name.matches)) {
      val labelValues = getGlobalLabelValuesOrDefault(m.clusterName) ++ m.labels
      m.definition match {
        case gd: GaugeDefinition =>
          gauges
            .getOrElse(
              gd,
              throw new IllegalArgumentException(
                s"No metric with definition ${gd.name} registered"
              )
            )
            .labels(labelValues: _*)
            .set(m.value)
        case cd: CounterDefinition =>
          val key = (cd, labelValues)
          val prev = counterPreviousValues.getOrElse(key, 0.0)
          val delta = m.value - prev
          if (delta > 0) {
            counters
              .getOrElse(
                cd,
                throw new IllegalArgumentException(
                  s"No metric with definition ${cd.name} registered"
                )
              )
              .labels(labelValues: _*)
              .inc(delta)
            counterPreviousValues = counterPreviousValues.updated(key, m.value)
          } else if (delta < 0) {
            // Counter reset (e.g. topic deletion or log compaction) — reset tracking baseline
            counterPreviousValues = counterPreviousValues.updated(key, m.value)
          }
      }
    }
  }

  override def remove(m: RemoveMetric): Unit = {
    if (sinkConfig.metricWhitelist.exists(m.definition.name.matches)) {
      val labelValues = getGlobalLabelValuesOrDefault(m.clusterName) ++ m.labels
      m.definition match {
        case gd: GaugeDefinition =>
          for (gauge <- gauges.get(gd)) {
            gauge.remove(labelValues: _*)
          }
        case cd: CounterDefinition =>
          for (counter <- counters.get(cd)) {
            counter.remove(labelValues: _*)
            counterPreviousValues = counterPreviousValues - ((cd, labelValues))
          }
      }
    }
  }

  override def stop(): Unit = {
    /*
     * Unregister all collectors (i.e. Gauges and Counters).  Useful for integration tests.
     * NOTE: This will nuke all JVM metrics too, but we don't care about those in tests.
     */
    registry.clear()
    server.stop()
  }

}
