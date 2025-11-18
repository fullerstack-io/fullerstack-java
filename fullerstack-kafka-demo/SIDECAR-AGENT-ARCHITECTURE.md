# Sidecar Agent Architecture

## Overview

Deploy `kafka-obs-agent.jar` as a sidecar in each microservice container to capture **in-process producer/consumer metrics** that aren't visible from broker JMX.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Microservice Container (order-service)                     │
│                                                              │
│  ┌────────────────────────┐  ┌─────────────────────────┐  │
│  │  order-service.jar     │  │  kafka-obs-agent.jar    │  │
│  │                        │  │                         │  │
│  │  Main Application:     │  │  Sidecar Agent:         │  │
│  │  ├─ Business Logic     │  │  ├─ JMX Client          │  │
│  │  ├─ KafkaProducer      │  │  │   └─ Connects to    │  │
│  │  │   └─ Exposes JMX ───┼──┼──┤     localhost JMX   │  │
│  │  └─ Kafka client-id:   │  │  │                     │  │
│  │     order-producer     │  │  ├─ MetricsCollector   │  │
│  │                        │  │  │   └─ Polls JMX      │  │
│  │  JMX Port: localhost   │  │  │     every 10s       │  │
│  │  (in-process, no port) │  │  │                     │  │
│  └────────────────────────┘  │  ├─ SubstratesEmitter │  │
│                               │  │   └─ Emits signals  │  │
│                               │  │     to Circuit      │  │
│                               │  │                     │  │
│                               │  └─ gRPC Client        │  │
│                               │      └─ Streams to     │  │
│                               │        central         │  │
│                               │        aggregator      │  │
│                               └─────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
                                        │
                                        │ gRPC stream
                                        ▼
            ┌──────────────────────────────────────────┐
            │  Central kafka-obs Platform              │
            │                                          │
            │  ├─ Signal Aggregator                   │
            │  ├─ HierarchyManager (Cell tree)        │
            │  ├─ Observers (ORIENT layer)            │
            │  ├─ Reporters (DECIDE layer)            │
            │  └─ Actors (ACT layer)                  │
            └──────────────────────────────────────────┘
```

## What Each Component Captures

### Sidecar Agent Capabilities

#### 1. Producer Metrics (via in-process JMX)
```java
// kafka.producer:type=producer-metrics,client-id=order-producer
- buffer-available-bytes     → Queue.PRESSURE when >80% full
- buffer-total-bytes         → Gauge.SET for baseline
- buffer-exhausted-total     → Counter.INCREMENT on exhaustion
- batch-size-avg             → Gauge.INCREMENT/DECREMENT
- record-send-rate           → Gauge for throughput
- request-latency-avg        → Monitor.DIVERGING when increasing
- record-error-rate          → Probe.FAILURE on errors
```

#### 2. Consumer Metrics (via in-process JMX)
```java
// kafka.consumer:type=consumer-fetch-manager-metrics
- records-lag-max            → Queue.OVERFLOW when lag spikes
- records-consumed-rate      → Gauge for consumption rate
- fetch-latency-avg          → Monitor.DEGRADED when slow
- bytes-consumed-rate        → Gauge for throughput

// kafka.consumer:type=consumer-coordinator-metrics
- assigned-partitions        → Resource.GRANT/DENY
- commit-latency-avg         → Service.SUCCEEDED/FAILED
- rebalance-latency-avg      → Monitor.ERRATIC during storms
```

#### 3. Application Business Metrics
```java
// Custom instrumentation in order-service
- orders-processed-count     → Counter.INCREMENT
- order-validation-failures  → Probe.FAILURE
- inventory-check-latency    → Service latency
- payment-gateway-calls      → Service.CALL/SUCCEEDED/FAILED
```

#### 4. JVM Health Metrics
```java
// java.lang:type=Memory
- HeapMemoryUsage.used       → Gauge for heap
- HeapMemoryUsage.max        → Resource capacity

// java.lang:type=GarbageCollector
- CollectionCount            → Counter for GC events
- CollectionTime             → Monitor.DEGRADED on long pauses
```

## Deployment Models

### Model 1: In-Process Agent (JAR in same JVM)
```dockerfile
# Dockerfile for order-service
FROM eclipse-temurin:21-jre
COPY order-service.jar /app/
COPY kafka-obs-agent.jar /app/
CMD ["java", "-javaagent:/app/kafka-obs-agent.jar", "-jar", "/app/order-service.jar"]
```

**Pros**:
- Zero network latency
- Direct MBean access
- Can intercept method calls

**Cons**:
- Shares heap with application
- Agent bugs could crash app

### Model 2: Sidecar Container (Separate process)
```yaml
# Kubernetes Pod
apiVersion: v1
kind: Pod
metadata:
  name: order-service
spec:
  containers:
  - name: order-service
    image: order-service:latest
    env:
    - name: JAVA_TOOL_OPTIONS
      value: "-Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote.authenticate=false"

  - name: kafka-obs-agent
    image: kafka-obs-agent:latest
    env:
    - name: JMX_URL
      value: "localhost:9999"
    - name: AGGREGATOR_URL
      value: "grpc://kafka-obs-aggregator:50051"
```

**Pros**:
- Isolated from application
- Can't crash application
- Independent scaling/updates

**Cons**:
- Requires JMX port exposure
- Slight network overhead (localhost)

## Signal Emission Examples

### Producer Buffer Pressure
```java
// In kafka-obs-agent.jar - ProducerMetricsCollector
long bufferAvailable = getJmxMetric("buffer-available-bytes");
long bufferTotal = getJmxMetric("buffer-total-bytes");
double utilization = 1.0 - (bufferAvailable / (double) bufferTotal);

if (utilization > 0.95) {
    queue.overflow(95L);  // OVERFLOW signal
    logger.warn("Producer buffer near full: {}%", (int)(utilization * 100));
} else if (utilization > 0.80) {
    queue.pressure(80L);  // PRESSURE signal (custom threshold)
} else {
    queue.put();  // Normal PUT signal
}
```

### Consumer Lag Spike Detection
```java
// In kafka-obs-agent.jar - ConsumerMetricsCollector
long currentLag = getJmxMetric("records-lag-max");
long previousLag = lagHistory.get(consumerId);

if (currentLag > 10000) {
    queue.overflow(currentLag);  // High lag
    monitor.status(Monitors.Condition.DEGRADED, Monitors.Confidence.HIGH);
} else if (currentLag > previousLag * 2) {
    monitor.status(Monitors.Condition.DIVERGING, Monitors.Confidence.MEDIUM);
    logger.warn("Consumer lag doubled: {} → {}", previousLag, currentLag);
} else {
    monitor.status(Monitors.Condition.STABLE, Monitors.Confidence.HIGH);
}
```

### Rebalance Storm Detection
```java
// In kafka-obs-agent.jar - ConsumerCoordinatorCollector
long rebalanceCount = getJmxMetric("rebalance-total");
double rebalanceLatency = getJmxMetric("rebalance-latency-avg");

if (rebalanceCount > lastRebalanceCount) {
    long timeSinceLastRebalance = System.currentTimeMillis() - lastRebalanceTime;

    if (timeSinceLastRebalance < 60000) {  // < 1 minute
        monitor.status(Monitors.Condition.ERRATIC, Monitors.Confidence.HIGH);
        logger.error("Rebalance storm detected: {} rebalances in {}s",
            rebalanceCount - lastRebalanceCount, timeSinceLastRebalance / 1000);
    }

    if (rebalanceLatency > 5000) {  // > 5 seconds
        monitor.status(Monitors.Condition.DEGRADED, Monitors.Confidence.MEDIUM);
        logger.warn("Slow rebalance: {}ms", rebalanceLatency);
    }
}
```

## Communication with Central Platform

### Option 1: gRPC Streaming
```protobuf
service SignalAggregator {
  rpc StreamSignals(stream Signal) returns (stream Ack);
}

message Signal {
  string source_id = 1;         // "order-service-pod-abc123"
  string signal_type = 2;       // "Queue.OVERFLOW"
  int64 timestamp_ns = 3;
  map<string, string> context = 4;  // client-id, topic, partition
  bytes payload = 5;            // Serialized Substrates Signal
}
```

### Option 2: Kafka Topic (Meta!)
```java
// Sidecar agents publish to __kafka-obs-signals topic
// Central platform consumes from it
// Uses Kafka to monitor Kafka!

Properties props = new Properties();
props.put("bootstrap.servers", "kafka-cluster:9092");
props.put("client.id", "kafka-obs-agent-" + podName);

KafkaProducer<String, SubstratesSignal> producer = new KafkaProducer<>(props);
producer.send(new ProducerRecord<>(
    "__kafka-obs-signals",
    producerId,
    queueOverflowSignal
));
```

### Option 3: Direct Circuit Federation
```java
// Each sidecar creates local Circuit
// Circuits federate to central Circuit
Circuit localCircuit = cortex().circuit(cortex().name("order-service-local"));

// Emit locally
Queue queue = localCircuit.conduit(...).percept(...);
queue.overflow(95L);

// Signals automatically replicate to central circuit via federation
```

## Benefits of Sidecar Approach

### 1. Complete Visibility
- ✅ Broker-level metrics (from broker JMX)
- ✅ Producer/Consumer metrics (from sidecar JMX)
- ✅ Application metrics (from sidecar instrumentation)
- ✅ Infrastructure metrics (from K8s/Docker)

### 2. Correlation
```
Order-123 failed:
├─ Application: OrderValidationException (from sidecar logs)
├─ Producer: buffer-exhausted-total=5 (from sidecar JMX)
├─ Broker: under-replicated partitions=2 (from broker JMX)
└─ Network: partition leader election (from AdminClient)

→ Root cause: Broker failure → buffer backpressure → order timeout
```

### 3. Early Warning
```
Sidecar detects:
  request-latency-avg: 50ms → 150ms → 500ms (DIVERGING)

Broker shows:
  Normal metrics, no issues visible

Analysis:
  Network degradation between order-service and brokers
  Caught BEFORE it causes failures!
```

## Comparison: Broker-Only vs Sidecar vs Both

| Metric                    | Broker JMX | Sidecar | Both  |
|---------------------------|------------|---------|-------|
| Topic bytes-in/out        | ✅         | ❌      | ✅    |
| Partition under-replication| ✅        | ❌      | ✅    |
| Producer buffer pressure  | ❌         | ✅      | ✅    |
| Consumer lag              | ⚠️ (AdminClient) | ✅ (JMX) | ✅ |
| Request latency           | ⚠️ (server-side) | ✅ (client-side) | ✅ |
| Business metrics          | ❌         | ✅      | ✅    |
| Rebalance storms          | ❌         | ✅      | ✅    |
| Network issues            | ⚠️ (timeout only) | ✅ (latency trends) | ✅ |

**Conclusion**: **Both** gives you 360° observability!

## Demo Enhancement Plan

To demonstrate sidecar capabilities:

1. **Create kafka-obs-agent module** (lightweight sidecar)
2. **Deploy as Java agent** in order-service/payment-service containers
3. **Monitor producer buffer** in real-time (the metrics we couldn't access before!)
4. **Detect consumer lag spikes** from inventory-service
5. **Show rebalance detection** when we add/remove consumers
6. **Aggregate signals** to central platform showing complete picture

This would demonstrate the **full production observability architecture**!
