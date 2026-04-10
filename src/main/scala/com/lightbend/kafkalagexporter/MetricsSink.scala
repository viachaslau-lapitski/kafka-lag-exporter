/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */

package com.lightbend.kafkalagexporter

import akka.actor.typed.ActorRef
import com.lightbend.kafkalagexporter.MetricsSink._

object MetricsSink {
  trait Message
  final case class Stop(sender: ActorRef[KafkaClusterManager.Message])
      extends MetricsSink.Message

  sealed trait MetricDefinition {
    def name: String
    def help: String
    def labels: List[String]
  }
  final case class GaugeDefinition(
      name: String,
      help: String,
      labels: List[String]
  ) extends MetricDefinition
  final case class CounterDefinition(
      name: String,
      help: String,
      labels: List[String]
  ) extends MetricDefinition

  type MetricDefinitions = List[MetricDefinition]

  trait ClusterMetric extends Metric {
    def clusterName: String
  }

  trait Metric {
    def labels: List[String]
    def definition: MetricDefinition
  }

  trait MetricValue extends ClusterMetric {
    def value: Double
  }

  trait RemoveMetric extends ClusterMetric
}

trait MetricsSink {
  def report(m: MetricValue): Unit
  def remove(m: RemoveMetric): Unit
  def stop(): Unit = ()
}
