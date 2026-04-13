# Copilot Instructions

## Build, Test & Lint

> **Prerequisites**: Java 17 and sbt are required. If not on PATH, they are installed at `/home/slava/tools/jdk-17.0.11+9` and `/home/slava/tools/sbt`.

```bash
# Compile
sbt compile

# Unit tests (fast, no Docker needed)
sbt test

# Single unit test suite
sbt "testOnly com.lightbend.kafkalagexporter.PrometheusEndpointSinkSpec"

# Integration tests (requires Docker for Testcontainers)
sbt "IntegrationTest/testOnly com.lightbend.kafkalagexporter.integration.testcontainers.*"

# Format all sources
sbt scalafmt

# Check formatting (CI enforces this)
sbt scalafmtCheckAll

# Fat jar (runnable with java -jar)
sbt assembly
# Output: target/scala-2.12/kafka-lag-exporter-<version>.jar

# Docker image
sbt docker:publishLocal
```

**Running the fat jar locally:**
```bash
# JVM flags must come BEFORE -jar
java -Dconfig.file=/path/to/application.conf -jar target/scala-2.12/kafka-lag-exporter-*.jar
```

## Architecture

The app is an **Akka Typed** actor system with this hierarchy:

```
MainApp
└── KafkaClusterManager (ActorSystem root)
    ├── ConsumerGroupCollector (one per cluster)
    │   └── polls Kafka via KafkaClient on a fixed interval
    └── MetricsReporter (one per sink type)
        └── wraps a MetricsSink implementation
```

**Data flow**: `KafkaClient` fetches offsets → `ConsumerGroupCollector` computes lag → broadcasts `Metrics.*Message` to all `MetricsReporter` actors → each reporter calls `MetricsSink.report()`.

**Sink implementations** in `src/main/scala/.../`:
- `PrometheusEndpointSink` — HTTP server on port 8000 (default), scraped by Prometheus
- `GraphiteEndpointSink` — pushes to Graphite/StatsD
- `InfluxDBPusherSink` — pushes to InfluxDB

**Configuration** is Typesafe Config (`reference.conf` + override file). All keys live under `kafka-lag-exporter {}`. Most settings also have `${?KAFKA_LAG_EXPORTER_*}` env var overrides.

## Key Conventions

### Metric definitions
All metrics are defined as vals in `Metrics.scala` and registered centrally via `Metrics.definitions`. Each metric is either a `GaugeDefinition` or `CounterDefinition` (both extend `MetricDefinition`).

- **Offset/position metrics** (`LatestOffsetMetric`, `EarliestOffsetMetric`, `LastGroupOffsetMetric`) are `CounterDefinition` — they are monotonically increasing values. In Prometheus output these get the `_total` suffix automatically.
- **Lag metrics** (`OffsetLagMetric`, `TimeLagMetric`, etc.) are `GaugeDefinition` — they represent current difference, not cumulative counts.

When adding a new metric: create a `GaugeDefinition` or `CounterDefinition` val in `Metrics.scala`, add it to `Metrics.definitions`, then emit it in `ConsumerGroupCollector`.

### Counter delta tracking
`PrometheusEndpointSink` tracks the last reported absolute value per `(CounterDefinition, labelValues)` key and calls `counter.inc(delta)`. On first report it calls `counter.inc(0)` to register the label even when the starting value is zero (otherwise the metric won't appear in Prometheus scrape output).

### Metric messages
`Metrics.scala` defines message case classes keyed by shape:
- `ClusterValueMessage` — cluster-level scalar
- `TopicPartitionValueMessage` / `TopicPartitionRemoveMetricMessage` — per topic-partition
- `GroupValueMessage` / `GroupRemoveMetricMessage` — per consumer group
- `GroupPartitionValueMessage` / `GroupPartitionRemoveMetricMessage` — per group+partition
- `GroupTopicValueMessage` / `GroupTopicRemoveMetricMessage` — per group+topic

Always emit the `Remove*` variant when evicting stale metrics (e.g., deleted topic, decommissioned consumer).

### File headers
All `.scala` source files must begin with the Apache 2.0 copyright header (enforced by `sbt-header`). Use the existing header format:
```scala
/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 * Copyright (C) 2022 Sean Glover <https://seanglover.com>
 */
```
Run `sbt headerCreate` to add missing headers.

### Test structure
- **Unit tests**: `src/test/scala/` — pure Scala, no Docker, run with `sbt test`
- **Integration tests**: `src/it/scala/` — use Testcontainers (Docker required), run in `IntegrationTest` sbt config scope
- Integration test utilities are in `src/it/scala/.../integration/`: `PrometheusUtils` (HTTP scrape + regex assertions), `LagSim` (Kafka producer/consumer simulation)
- `Rule.create(definition, assertion, labelValues*)` builds a scrape assertion; `CounterDefinition` metrics match against `<name>_total{...}` in the Prometheus output
- Tests using `assertAllStagesStopped` must call `Http(system).shutdownAllConnectionPools().futureValue` before the block closes to avoid PoolFlow leaks

### scala-java8-compat version conflict
Kafka 3.7+ requires `scala-java8-compat` 1.0.2, Akka 2.6 requires 0.8.0. Resolved via:
```scala
libraryDependencySchemes += "org.scala-lang.modules" %% "scala-java8-compat" % VersionScheme.Always
```
Do not remove this line.
