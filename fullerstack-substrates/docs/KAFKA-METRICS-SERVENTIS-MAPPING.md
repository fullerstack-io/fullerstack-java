# Kafka Metrics → Serventis API Complete Mapping

**Date**: 2025-11-05
**Kafka Version**: 3.x (backwards compatible with 2.x)
**Serventis Version**: RC6
**Status**: Authoritative Reference

---

## Table of Contents

1. [Overview](#overview)
2. [Broker Metrics](#broker-metrics)
3. [Producer Metrics](#producer-metrics)
4. [Consumer Metrics](#consumer-metrics)
5. [Topic/Partition Metrics](#topicpartition-metrics)
6. [Network Metrics](#network-metrics)
7. [Implementation Status](#implementation-status)
8. [Priority Matrix](#priority-matrix)

---

## Overview

This document maps **all major Kafka JMX metrics** to the **12 Serventis RC6 instrument APIs**, showing:
- ✅ **Implemented** in fullerstack-kafka
- 🔄 **Planned** (documented in stories)
- ⭐ **New Opportunities** (RC6 enhancements)
- ❌ **Not Mapped** (future consideration)

### Serventis API Quick Reference

| API | Phase | Purpose | Signal Count |
|-----|-------|---------|--------------|
| **Probes** | OBSERVE | Communication outcomes | 16 (8 signs × 2 orientations) |
| **Services** | OBSERVE | Interaction lifecycle | 16 (8 signs × 2 orientations) |
| **Queues** | OBSERVE | Flow control | 4 signs |
| **Counters** | OBSERVE | Monotonic increments | 3 signs |
| **Gauges** | OBSERVE | Bidirectional values | 5 signs |
| **Caches** | OBSERVE | Cache operations | 7 signs |
| **Routers** | OBSERVE | Packet routing | 9 signs |
| **Monitors** | ORIENT | Condition assessment | 7 signs (+Confidence) |
| **Resources** | ORIENT | Resource acquisition | 6 signs |
| **Reporters** | DECIDE | Situation urgency | 3 signs |
| **Agents** | ACT | Promise coordination | 20 (10 × 2 directions) |
| **Actors** | ACT | Speech acts | 11 signs |

---

## Broker Metrics

### 1. Broker Health & Performance

#### 1.1 CPU & Memory

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **JVM Heap Usage** | `java.lang:type=Memory` → `HeapMemoryUsage` | **Gauges** | ❌ Not mapped |
| **JVM Non-Heap Usage** | `java.lang:type=Memory` → `NonHeapMemoryUsage` | **Gauges** | ❌ Not mapped |
| **GC Time** | `java.lang:type=GarbageCollector,name=*` → `CollectionTime` | **Counters** | ❌ Not mapped |
| **GC Count** | `java.lang:type=GarbageCollector,name=*` → `CollectionCount` | **Counters** | ❌ Not mapped |
| **CPU Usage** | OS-level (via MXBeans) | **Gauges** | ❌ Not mapped |
| **Thread Count** | `java.lang:type=Threading` → `ThreadCount` | **Gauges** | ❌ Not mapped |

**Recommended Mapping**:
```java
// JVM Heap Gauge Monitor
Gauge heapGauge = gauges.get(cortex().name("broker-" + brokerId + ".jvm.heap"));
double heapUsed = memoryUsage.getUsed();
double heapMax = memoryUsage.getMax();
double utilization = heapUsed / heapMax;

if (utilization >= 0.90) {
    heapGauge.overflow();  // Critical: 90%+ heap
} else if (utilization >= 0.75) {
    heapGauge.increment(); // Warning: 75-90% heap
} else {
    heapGauge.decrement(); // Normal: <75% heap
}
```

**Monitor Assessment** (ORIENT phase):
```java
// Broker Health Monitor
Monitor brokerHealth = monitors.get(cortex().name("broker-" + brokerId + ".health"));
if (heapUtil >= 0.90 && gcTime > gcThreshold) {
    brokerHealth.degraded(Monitors.Confidence.CONFIRMED);
} else if (heapUtil >= 0.75) {
    brokerHealth.diverging(Monitors.Confidence.MEASURED);
} else {
    brokerHealth.stable(Monitors.Confidence.CONFIRMED);
}
```

---

#### 1.2 Thread Pools

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Network Thread Idle %** | `kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent` | **Resources** | ✅ **Implemented** (`ThreadPoolResourceMonitor`) |
| **I/O Thread Idle %** | `kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent` | **Resources** | ✅ **Implemented** (`ThreadPoolResourceMonitor`) |
| **Log Cleaner Recopy %** | `kafka.log:type=LogCleaner,name=cleaner-recopy-percent` | **Resources** | ✅ **Implemented** (`ThreadPoolResourceMonitor`) |
| **Request Queue Size** | `kafka.network:type=RequestChannel,name=RequestQueueSize` | **Queues** | ❌ Not mapped |

**Current Implementation** (`ThreadPoolResourceMonitor.java`):
```java
// Resources API: Thread acquisition tracking
Resource networkThreads = resources.get(cortex().name("broker-1.network-threads"));
if (idlePercent < 0.10) {
    networkThreads.deny();  // Pool exhausted
} else if (idlePercent < 0.30) {
    networkThreads.timeout(); // Pool saturation
} else {
    networkThreads.grant();  // Thread available
}
```

**Gap**: Request queue as Queues API signal not yet implemented

**Recommended Addition**:
```java
// Request Queue Monitor (Queues API)
Queue requestQueue = queues.get(cortex().name("broker-1.request-queue"));
int queueSize = getRequestQueueSize();
int maxQueueSize = 500; // Typical default

if (queueSize >= maxQueueSize) {
    requestQueue.overflow(queueSize); // Queue full, backpressure
} else if (queueSize > 0) {
    requestQueue.put(queueSize);      // Requests queued
} else {
    requestQueue.take();              // Queue draining
}
```

---

#### 1.3 Connections

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Connection Count** | `kafka.server:type=socket-server-metrics,listener=PLAINTEXT` → `connection-count` | **Gauges** | ✅ **Implemented** (`ConnectionPoolGaugeMonitor`) |
| **Connection Creation Rate** | `kafka.server:type=socket-server-metrics` → `connection-creation-rate` | **Counters** | ❌ Not mapped |
| **Connection Close Rate** | `kafka.server:type=socket-server-metrics` → `connection-close-rate` | **Counters** | ❌ Not mapped |
| **Failed Authentication Count** | `kafka.server:type=socket-server-metrics` → `failed-authentication-total` | **Counters** + **Probes** | ❌ Not mapped |
| **Authentication Success** | `kafka.server:type=socket-server-metrics` → `successful-authentication-total` | **Probes** | ❌ Not mapped |

**Current Implementation** (`ConnectionPoolGaugeMonitor.java`):
```java
// Gauges API: Connection pool utilization
Gauge connGauge = gauges.get(cortex().name("broker-1.connections"));
int delta = currentConns - previousConns;

if (delta > 0) {
    for (int i = 0; i < delta; i++) connGauge.increment();
    if (utilization >= 0.95) connGauge.overflow();
} else if (delta < 0) {
    for (int i = 0; i < Math.abs(delta); i++) connGauge.decrement();
    if (utilization <= 0.10) connGauge.underflow();
}
```

**Gap**: Authentication events not mapped to Probes API

**Recommended Addition** (Probes API for auth):
```java
// Authentication Probe
Probe authProbe = probes.get(cortex().name("broker-1.authentication"));
if (authSuccessful) {
    authProbe.operation(
        Probes.Operation.AUTHENTICATE,
        Probes.Role.SERVER,
        Probes.Outcome.SUCCESS,
        Probes.Orientation.RECEIPT  // "They authenticated successfully"
    );
} else {
    authProbe.operation(
        Probes.Operation.AUTHENTICATE,
        Probes.Role.SERVER,
        Probes.Outcome.FAILURE,
        Probes.Orientation.RECEIPT  // "They failed authentication"
    );
}
```

---

### 2. Broker Replication (ISR)

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **ISR Shrinks** | `kafka.server:type=ReplicaManager,name=IsrShrinksPerSec` | ⭐ **Routers** (RC6) | 🔄 **Proposed** (Enhancement #2) |
| **ISR Expands** | `kafka.server:type=ReplicaManager,name=IsrExpandsPerSec` | ⭐ **Routers** (RC6) | 🔄 **Proposed** (Enhancement #2) |
| **Under Replicated Partitions** | `kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions` | **Monitors** | ❌ Not mapped |
| **Offline Partitions** | `kafka.controller:type=KafkaController,name=OfflinePartitionsCount` | **Monitors** | ❌ Not mapped |
| **Active Controller Count** | `kafka.controller:type=KafkaController,name=ActiveControllerCount` | **Monitors** | ❌ Not mapped |
| **Replica Lag Max** | `kafka.server:type=ReplicaFetcherManager,name=MaxLag,clientId=Replica` | ⭐ **Routers** (RC6) | 🔄 **Proposed** (Enhancement #2) |
| **Replica Fetch Rate** | `kafka.server:type=ReplicaFetcherManager,name=MinFetchRate,clientId=Replica` | ⭐ **Routers** (RC6) | 🔄 **Proposed** (Enhancement #2) |

**RC6 Routers API Proposed Enhancement** (Enhancement #2 - ISR Replication):
```java
// ISR Replication Monitor (Routers API)
Router leader = routers.get(cortex().name("partition-0.leader.broker-1"));
Router follower = routers.get(cortex().name("partition-0.follower.broker-2"));

long replicaLag = getReplicaLag(partition, followerId);

if (replicaLag == 0) {
    leader.send();       // Leader sends to follower
    follower.receive();  // Follower receives (in-sync)
} else if (replicaLag > 0 && replicaLag < 100) {
    leader.send();
    follower.receive();  // Slow but not critical
} else if (replicaLag >= 100) {
    leader.send();
    follower.drop();     // Follower falling behind, ISR shrink imminent
}
```

**Monitor Assessment**:
```java
// Partition Health Monitor
Monitor partitionHealth = monitors.get(cortex().name("partition-0.health"));
int underReplicated = getUnderReplicatedPartitions();

if (underReplicated > 0) {
    partitionHealth.degraded(Monitors.Confidence.CONFIRMED);
} else {
    partitionHealth.stable(Monitors.Confidence.CONFIRMED);
}
```

---

### 3. Broker Request Metrics

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Produce Request Rate** | `kafka.network:type=RequestMetrics,name=RequestsPerSec,request=Produce` | **Counters** | ❌ Not mapped |
| **Fetch Request Rate** | `kafka.network:type=RequestMetrics,name=RequestsPerSec,request=Fetch` | **Counters** | ❌ Not mapped |
| **Request Latency (avg)** | `kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Produce` | **Gauges** | ❌ Not mapped |
| **Request Queue Time** | `kafka.network:type=RequestMetrics,name=RequestQueueTimeMs,request=*` | **Gauges** | ❌ Not mapped |
| **Response Queue Time** | `kafka.network:type=RequestMetrics,name=ResponseQueueTimeMs,request=*` | **Gauges** | ❌ Not mapped |
| **Errors Per Second** | `kafka.network:type=RequestMetrics,name=ErrorsPerSec,request=*` | **Counters** | ❌ Not mapped |

**Recommended Mapping** (Counters for rates):
```java
// Produce Request Counter
Counter produceCounter = counters.get(cortex().name("broker-1.produce-requests"));
double produceRate = getProduceRequestRate(); // From JMX

// Emit signals based on rate
if (produceRate >= warningThreshold) {
    produceCounter.overflow();  // High request rate
}
produceCounter.increment();  // Normal increment
```

**Recommended Mapping** (Gauges for latency):
```java
// Request Latency Gauge
Gauge latencyGauge = gauges.get(cortex().name("broker-1.produce-latency"));
double avgLatency = getAverageProduceLatency();

if (avgLatency >= p99Threshold) {
    latencyGauge.overflow();  // Latency spike
} else if (avgLatency < p50Threshold) {
    latencyGauge.decrement(); // Latency improving
} else {
    latencyGauge.increment(); // Latency increasing
}
```

---

### 4. Broker Log Metrics

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Log Flush Rate** | `kafka.log:type=LogFlushStats,name=LogFlushRateAndTimeMs` | **Counters** | ❌ Not mapped |
| **Log Segment Count** | `kafka.log:type=Log,name=NumLogSegments,topic=*,partition=*` | **Gauges** | ❌ Not mapped |
| **Log Size** | `kafka.log:type=Log,name=Size,topic=*,partition=*` | **Gauges** | ❌ Not mapped |
| **Log End Offset** | `kafka.log:type=Log,name=LogEndOffset,topic=*,partition=*` | **Counters** | ❌ Not mapped |
| **Log Start Offset** | `kafka.log:type=Log,name=LogStartOffset,topic=*,partition=*` | **Counters** | ❌ Not mapped |

**Recommended Mapping** (Gauges for sizes):
```java
// Log Size Gauge
Gauge logSizeGauge = gauges.get(cortex().name("broker-1.topic-X.partition-0.log-size"));
long logSize = getLogSize(topic, partition);
long maxSize = getRetentionBytes();

if (logSize >= maxSize * 0.95) {
    logSizeGauge.overflow();  // Near retention limit
} else {
    logSizeGauge.increment(); // Log growing
}
```

---

## Producer Metrics

### 1. Producer Buffer & Batching

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Buffer Available Bytes** | `kafka.producer:type=producer-metrics,client-id=*` → `buffer-available-bytes` | **Queues** | 🔄 **Planned** (`ProducerBufferMonitor`) |
| **Buffer Total Bytes** | `kafka.producer:type=producer-metrics,client-id=*` → `buffer-total-bytes` | **Gauges** | ❌ Not mapped |
| **Buffered Records** | `kafka.producer:type=producer-metrics,client-id=*` → `buffer-exhausted-total` | **Queues** | 🔄 **Planned** |
| **Batch Size (avg)** | `kafka.producer:type=producer-metrics,client-id=*` → `batch-size-avg` | **Gauges** | ❌ Not mapped |
| **Records Per Request (avg)** | `kafka.producer:type=producer-metrics,client-id=*` → `records-per-request-avg` | **Gauges** | ❌ Not mapped |

**Planned Implementation** (`ProducerBufferMonitor.java`):
```java
// Producer Buffer Monitor (Queues API)
Queue producerBuffer = queues.get(cortex().name("producer-1.buffer"));
double utilization = 1.0 - (availableBytes / totalBytes);

if (utilization >= 0.95) {
    producerBuffer.overflow((long)(utilization * 100));  // Buffer 95%+ full
} else if (utilization >= 0.80) {
    producerBuffer.put((long)(utilization * 100));       // Buffer pressure
} else {
    producerBuffer.put();  // Normal operation
}
```

**Gap**: Batch size and records-per-request not yet mapped (Gauges API opportunity)

---

### 2. Producer Send Metrics

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Record Send Rate** | `kafka.producer:type=producer-metrics,client-id=*` → `record-send-rate` | **Counters** | ❌ Not mapped |
| **Record Send Total** | `kafka.producer:type=producer-metrics,client-id=*` → `record-send-total` | **Counters** | ❌ Not mapped |
| **Record Error Rate** | `kafka.producer:type=producer-metrics,client-id=*` → `record-error-rate` | **Counters** + **Probes** | ❌ Not mapped |
| **Record Retry Rate** | `kafka.producer:type=producer-metrics,client-id=*` → `record-retry-rate` | **Counters** + **Services** | ❌ Not mapped |
| **Request Latency (avg)** | `kafka.producer:type=producer-metrics,client-id=*` → `request-latency-avg` | **Gauges** | ❌ Not mapped |

**Recommended Mapping** (Services API for send lifecycle):
```java
// Producer Send Service
Service producerSend = services.get(cortex().name("producer-1.send"));

// On send attempt
producerSend.call(Services.Orientation.RELEASE);  // "I am sending"

// On acknowledgment
if (success) {
    producerSend.succeed(Services.Orientation.RELEASE);  // "I succeeded"
} else if (retriable) {
    producerSend.retry(Services.Orientation.RELEASE);    // "I am retrying"
} else {
    producerSend.fail(Services.Orientation.RELEASE);     // "I failed"
}
```

**Recommended Mapping** (Probes API for errors):
```java
// Producer Error Probe
Probe producerProbe = probes.get(cortex().name("producer-1.network"));
if (error) {
    producerProbe.operation(
        Probes.Operation.SEND,
        Probes.Role.CLIENT,
        Probes.Outcome.FAILURE,
        Probes.Orientation.RELEASE  // "I failed to send"
    );
}
```

---

### 3. Producer Connection Metrics

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Connection Count** | `kafka.producer:type=producer-metrics,client-id=*` → `connection-count` | **Gauges** | ❌ Not mapped |
| **Connection Creation Rate** | `kafka.producer:type=producer-metrics,client-id=*` → `connection-creation-rate` | **Counters** | ❌ Not mapped |
| **IO Wait Time** | `kafka.producer:type=producer-metrics,client-id=*` → `io-wait-time-ns-avg` | **Gauges** | ❌ Not mapped |

**Recommended Mapping** (same pattern as broker connections):
```java
// Producer Connection Gauge
Gauge connGauge = gauges.get(cortex().name("producer-1.connections"));
// Same increment/decrement pattern as ConnectionPoolGaugeMonitor
```

---

## Consumer Metrics

### 1. Consumer Lag

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Consumer Lag** | Via AdminClient: `listConsumerGroupOffsets()` | **Queues** | ✅ **Implemented** (`ConsumerLagMonitor`) |
| **Records Lag Max** | `kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*` → `records-lag-max` | **Queues** | ❌ Not mapped (client-side metric) |
| **Fetch Latency** | `kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*` → `fetch-latency-avg` | **Gauges** | ❌ Not mapped |

**Current Implementation** (`ConsumerLagMonitor.java`):
```java
// Consumer Lag Monitor (Queues API)
Queue lagQueue = queues.get(cortex().name("consumer-group-1.lag"));
long totalLag = endOffset - currentOffset;

if (totalLag >= 10_000) {
    lagQueue.underflow(totalLag);  // Severe lag
} else if (totalLag >= 1_000) {
    lagQueue.underflow(totalLag);  // Lagging
} else {
    lagQueue.take();  // Normal consumption
}
```

**Gap**: Client-side lag metrics (records-lag-max, fetch-latency) not yet integrated

---

### 2. Consumer Rebalancing

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Rebalance Events** | Via ConsumerRebalanceListener | **Services** | ✅ **Implemented** (`ConsumerRebalanceListenerAdapter`) |
| **Partition Assignment** | Via ConsumerRebalanceListener | ⭐ **Agents** (RC6) | 🔄 **Proposed** (Enhancement #1) |
| **Rebalance Time** | Custom tracking | **Gauges** | ❌ Not mapped |

**Current Implementation** (`ConsumerRebalanceListenerAdapter.java`):
```java
// Consumer Rebalance (Services API)
Service consumerService = services.get(cortex().name("consumer-1.operations"));

@Override
public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
    consumerService.suspend(Services.Orientation.RELEASE);  // "I am suspending"
}

@Override
public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
    consumerService.resume(Services.Orientation.RELEASE);  // "I am resuming"
}
```

**RC6 Proposed Enhancement (Agents API)** - Enhancement #1 (Consumer Rebalance Coordination):
```java
// Consumer Rebalance Agent Monitor (Agents API)
Agent consumer = agents.get(cortex().name("consumer-1"));
Agent coordinator = agents.get(cortex().name("coordinator-group-1"));

@Override
public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
    consumer.breach(Agents.Direction.OUTBOUND);  // "I breached my promise"
}

@Override
public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
    consumer.inquire(Agents.Direction.OUTBOUND);   // "Can I join?"
    coordinator.offer(Agents.Direction.INBOUND);   // "Here are partitions"
    consumer.promise(Agents.Direction.OUTBOUND);   // "I commit to consume"
    coordinator.accept(Agents.Direction.INBOUND);  // "Assignment confirmed"
}

// On successful consumption
public void onConsumptionSuccess() {
    consumer.fulfill(Agents.Direction.OUTBOUND);  // "I fulfilled my promise"
}
```

---

### 3. Consumer Fetch Metrics

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Fetch Rate** | `kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*` → `fetch-rate` | **Counters** | ❌ Not mapped |
| **Bytes Consumed Rate** | `kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*` → `bytes-consumed-rate` | **Counters** | ❌ Not mapped |
| **Records Consumed Rate** | `kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*` → `records-consumed-rate` | **Counters** | ❌ Not mapped |
| **Fetch Size Avg** | `kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*` → `fetch-size-avg` | **Gauges** | ❌ Not mapped |

**Recommended Mapping** (Counters for rates):
```java
// Consumer Fetch Counter
Counter fetchCounter = counters.get(cortex().name("consumer-1.fetches"));
double fetchRate = getFetchRate();

fetchCounter.increment();  // Each fetch increments

if (fetchRate < expectedRate * 0.5) {
    fetchCounter.underflow();  // Fetch rate dropped (consumer slow)
}
```

---

## Topic/Partition Metrics

### 1. Partition State

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Partition Size** | `kafka.log:type=Log,name=Size,topic=*,partition=*` | **Gauges** | ❌ Not mapped |
| **Log End Offset** | `kafka.log:type=Log,name=LogEndOffset,topic=*,partition=*` | **Counters** | ❌ Not mapped |
| **Leader Epoch** | Via AdminClient | **Monitors** | ❌ Not mapped |
| **ISR Count** | Via AdminClient `describeTopics()` | ⭐ **Routers** (RC6) | 🔄 **Proposed** (Enhancement #2) |

**Recommended Mapping** (Gauges for size):
```java
// Partition Size Gauge
Gauge partitionSize = gauges.get(cortex().name("topic-X.partition-0.size"));
long size = getPartitionSize();
long maxSize = getRetentionBytes();

if (size >= maxSize * 0.95) {
    partitionSize.overflow();  // Near retention limit
}
```

---

### 2. Partition Reassignment

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Reassignment Progress** | Via AdminClient `listPartitionReassignments()` | ⭐ **Routers** (RC6) | 🔄 **Proposed** (Enhancement #3) |
| **Adding Replicas** | Via AdminClient | ⭐ **Routers** (RC6) | 🔄 **Proposed** (Enhancement #3) |
| **Removing Replicas** | Via AdminClient | ⭐ **Routers** (RC6) | 🔄 **Proposed** (Enhancement #3) |

**RC6 Routers API Proposed Enhancement** (Enhancement #3 - Partition Reassignment):
```java
// Partition Reassignment Monitor (Routers API)
Router oldLeader = routers.get(cortex().name("partition-0.old-leader"));
Router newLeader = routers.get(cortex().name("partition-0.new-leader"));

if (!addingReplicas.isEmpty()) {
    oldLeader.fragment();  // Traffic splitting between old/new
}

if (!removingReplicas.isEmpty()) {
    oldLeader.drop();      // Old leader shedding traffic
    newLeader.reassemble(); // New leader consolidating
}
```

---

## Network Metrics

### 1. Network I/O

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Network Processor Idle %** | `kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent` | **Resources** | ✅ **Implemented** (as thread pool) |
| **Request Rate** | `kafka.network:type=RequestMetrics,name=RequestsPerSec,request=*` | **Counters** | ❌ Not mapped |
| **Response Rate** | `kafka.network:type=RequestMetrics,name=ResponsesPerSec` | **Counters** | ❌ Not mapped |
| **Bytes In Rate** | `kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec` | **Counters** | ❌ Not mapped |
| **Bytes Out Rate** | `kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec` | **Counters** | ❌ Not mapped |

**Recommended Mapping** (Counters for throughput):
```java
// Network Throughput Counter
Counter bytesInCounter = counters.get(cortex().name("broker-1.bytes-in"));
double bytesInRate = getBytesInRate();

bytesInCounter.increment();  // Each polling cycle

if (bytesInRate >= networkCapacity * 0.90) {
    bytesInCounter.overflow();  // Network saturation
}
```

---

### 2. Network Errors

| Kafka JMX Metric | MBean ObjectName | Serventis API | Implementation Status |
|------------------|------------------|---------------|----------------------|
| **Network Errors** | `kafka.network:type=RequestMetrics,name=ErrorsPerSec,request=*` | **Probes** | ❌ Not mapped |
| **Connection Failures** | `kafka.server:type=socket-server-metrics` → `failed-authentication-total` | **Probes** | ❌ Not mapped |
| **Temporary Auth Failures** | `kafka.server:type=socket-server-metrics` → `temporary-authentication-failure-total` | **Probes** | ❌ Not mapped |

**Recommended Mapping** (Probes API):
```java
// Network Error Probe
Probe networkProbe = probes.get(cortex().name("broker-1.network"));
if (networkError) {
    networkProbe.operation(
        Probes.Operation.SEND,  // or RECEIVE
        Probes.Role.SERVER,
        Probes.Outcome.FAILURE,
        Probes.Orientation.RECEIPT  // Observed failure
    );
}
```

---

## Implementation Status

### ✅ Implemented in fullerstack-kafka (4 monitors, RC5 APIs)

| Monitor | Module | Serventis API | Kafka Metrics Covered |
|---------|--------|---------------|----------------------|
| **ConnectionPoolGaugeMonitor** | fullerstack-kafka-core | Gauges | Broker connection-count |
| **ThreadPoolResourceMonitor** | fullerstack-kafka-broker | Resources | Network/IO/LogCleaner thread idle % |
| **ConsumerLagMonitor** | fullerstack-kafka-consumer | Queues | Consumer lag (via AdminClient) |
| **ConsumerRebalanceListenerAdapter** | fullerstack-kafka-consumer | Services | Rebalance SUSPEND/RESUME events |

**Total Metrics Covered**: ~6 out of 100+ Kafka metrics

---

### 🔄 Planned (documented in stories, RC5 APIs)

| Monitor | Story | Serventis API | Kafka Metrics to Cover |
|---------|-------|---------------|----------------------|
| **ProducerBufferMonitor** | Story 1.5.1 (partial) | Queues | Producer buffer-available-bytes |
| **InFlightRequestGaugeMonitor** | Story 1.5.2 | Gauges | Producer/Consumer in-flight requests |
| **PartitionReassignmentGaugeMonitor** | Story 1.5.3 | Gauges | Partition reassignment progress |

**Total Additional Metrics**: ~3-5 metrics

---

### ⭐ RC6 Opportunities (new with RC6 upgrade)

**Note**: These are **proposed implementations** for fullerstack-kafka modules (not existing epics). See `RC6-FULLERSTACK-KAFKA-IMPACT-ASSESSMENT.md` for detailed implementation plans.

| Enhancement | Proposal Reference | Serventis API (RC6) | Kafka Metrics to Cover |
|-------------|-------------------|---------------------|----------------------|
| **Consumer Rebalance Coordination** | Proposed Enhancement #1 | **Agents** | Rebalance promise lifecycle (INQUIRE→FULFILL/BREACH) |
| **ISR Replication Topology** | Proposed Enhancement #2 | **Routers** | ISR shrinks/expands, replica lag, fetch rate |
| **Partition Reassignment Tracking** | Proposed Enhancement #3 | **Routers** | Reassignment FRAGMENT→REASSEMBLE lifecycle |
| **JMX Operation Tracking** | Proposed Enhancement #4 | **Actors** | Dynamic config changes, topic operations |
| **Network Partition Detection** | Proposed Enhancement #5 | **Routers** | Cross-broker communication DROP patterns |

**Total Additional Metrics**: ~8-12 metrics with deep semantic intelligence

---

### ❌ Not Yet Mapped (future opportunities)

**High Priority** (commonly used metrics):
- JVM heap/GC metrics → Gauges + Monitors
- Request/response rates → Counters
- Request latency → Gauges
- Producer send rates/errors → Counters + Probes
- Consumer fetch rates → Counters
- Network throughput (bytes in/out) → Counters
- Log flush/segment metrics → Counters + Gauges
- Under-replicated partitions → Monitors
- Offline partitions → Monitors

**Medium Priority**:
- Authentication success/failure → Probes
- Topic/partition sizes → Gauges
- Log end offset → Counters
- Batch sizes → Gauges

**Low Priority** (niche metrics):
- ZooKeeper metrics (deprecated in KRaft)
- Log cleaner metrics (only for compacted topics)
- Quota metrics (enterprise feature)

**Total Unmapped**: ~80-90 metrics

---

## Priority Matrix

### Priority 1: Critical Gaps (Immediate Implementation)

| Metric Category | Serventis API | Business Value | Complexity |
|----------------|---------------|----------------|------------|
| **JVM Heap/GC** | Gauges + Monitors | ⭐⭐⭐⭐⭐ (OOM prevention) | Low |
| **Request Latency** | Gauges + Monitors | ⭐⭐⭐⭐⭐ (SLA tracking) | Low |
| **Under-Replicated Partitions** | Monitors | ⭐⭐⭐⭐⭐ (data durability) | Low |
| **Producer Buffer (complete)** | Queues | ⭐⭐⭐⭐⭐ (backpressure) | Low (partial exists) |

**Effort**: 3-5 days total
**Impact**: Cover 10-15 additional critical metrics

---

### Priority 2: High-Value Enhancements (Short-term)

**Note**: These are **proposed enhancements** for fullerstack-kafka. See `RC6-FULLERSTACK-KAFKA-IMPACT-ASSESSMENT.md` for complete implementation plans.

| Enhancement | Serventis API (RC6) | Business Value | Complexity | Proposal Ref |
|-------------|---------------------|----------------|------------|--------------|
| **Consumer Rebalance Coordination** | Agents | ⭐⭐⭐⭐⭐ | Medium (3-4 days) | Enhancement #1 |
| **ISR Replication Topology** | Routers | ⭐⭐⭐⭐⭐ | High (5-6 days) | Enhancement #2 |
| **Request/Response Rates** | Counters | ⭐⭐⭐⭐ | Low (1-2 days) | - |
| **Authentication Tracking** | Probes | ⭐⭐⭐⭐ | Low (1-2 days) | - |

**Effort**: 10-14 days total
**Impact**: Transformative observability for rebalancing + ISR

---

### Priority 3: Medium-Value Gaps (Medium-term)

| Metric Category | Serventis API | Business Value | Complexity | Proposal Ref |
|----------------|---------------|----------------|------------|--------------|
| **Producer Send Lifecycle** | Services + Probes | ⭐⭐⭐⭐ | Medium (2-3 days) | - |
| **Network Throughput** | Counters | ⭐⭐⭐ | Low (1-2 days) | - |
| **Topic/Partition Sizes** | Gauges | ⭐⭐⭐ | Low (1-2 days) | - |
| **Partition Reassignment** | Routers | ⭐⭐⭐⭐ | High (4-5 days) | Enhancement #3 |

**Effort**: 8-12 days total

---

### Priority 4: Nice-to-Have (Long-term)

| Metric Category | Serventis API | Business Value | Complexity | Proposal Ref |
|----------------|---------------|----------------|------------|--------------|
| **Log Flush Metrics** | Counters | ⭐⭐ | Low | - |
| **Batch Sizes** | Gauges | ⭐⭐ | Low | - |
| **JMX Operations** | Actors | ⭐⭐⭐ | Medium | Enhancement #4 |
| **Network Partition Detection** | Routers | ⭐⭐⭐ | High | Enhancement #5 |

**Effort**: 8-12 days total

---

## Summary Statistics

### Current Coverage

| Category | Metrics Available | Metrics Mapped | Coverage % |
|----------|------------------|----------------|------------|
| **Broker** | ~40 | 6 | 15% |
| **Producer** | ~25 | 0 | 0% |
| **Consumer** | ~20 | 1 | 5% |
| **Topics/Partitions** | ~15 | 0 | 0% |
| **Network** | ~10 | 0 | 0% |
| **TOTAL** | **~110** | **7** | **6%** |

### After Priority 1 Implementation

| Category | Additional Metrics | New Coverage % |
|----------|-------------------|----------------|
| **Broker** | +8 | 35% |
| **Producer** | +2 | 8% |
| **Consumer** | +1 | 10% |
| **Topics/Partitions** | +2 | 13% |
| **Network** | +1 | 10% |
| **TOTAL** | **+14** | **19%** |

### After Priority 2 (with RC6)

| Category | Additional Metrics | New Coverage % |
|----------|-------------------|----------------|
| **Broker** | +6 (ISR, reassignment) | 50% |
| **Producer** | +3 (send lifecycle) | 20% |
| **Consumer** | +4 (rebalance coordination) | 30% |
| **Network** | +2 (auth, throughput) | 30% |
| **TOTAL** | **+15** | **38%** |

---

## Recommendations

### Immediate Actions (Priority 1)

1. **Implement JVM Health Monitor** (Gauges + Monitors)
   - Heap usage → Gauges.increment/overflow
   - GC time → Counters
   - Broker health assessment → Monitors.degraded/stable
   - **Effort**: 1-2 days
   - **File**: `fullerstack-kafka-broker/.../JvmHealthMonitor.java`

2. **Implement Request Latency Monitor** (Gauges + Monitors)
   - Track p50/p99/p999 latencies
   - Emit Gauges.overflow on latency spikes
   - Monitor degradation assessment
   - **Effort**: 1-2 days
   - **File**: `fullerstack-kafka-broker/.../RequestLatencyMonitor.java`

3. **Implement Under-Replicated Partitions Monitor** (Monitors)
   - Track under-replicated partition count
   - Emit Monitors.degraded when count > 0
   - **Effort**: 1 day
   - **File**: `fullerstack-kafka-broker/.../PartitionHealthMonitor.java`

4. **Complete Producer Buffer Monitor** (Queues)
   - Enhance existing partial implementation
   - Add buffer-exhausted-total tracking
   - **Effort**: 0.5 day
   - **File**: Enhance existing `ProducerBufferMonitor.java`

**Total Priority 1 Effort**: 3.5-5.5 days
**Total Priority 1 Impact**: +14 metrics, critical observability gaps filled

---

### Short-term Goals (Priority 2 with RC6)

**Note**: These are proposed enhancements for fullerstack-kafka modules.

1. **Consumer Rebalance Coordination** (Agents API) - Enhancement #1
2. **ISR Replication Topology** (Routers API) - Enhancement #2
3. **Request/Response Rate Tracking** (Counters)
4. **Authentication Tracking** (Probes)

**Total Priority 2 Effort**: 10-14 days
**Total Priority 2 Impact**: +15 metrics, transformative rebalancing + ISR visibility

See `RC6-FULLERSTACK-KAFKA-IMPACT-ASSESSMENT.md` for detailed implementation plans.

---

### Long-term Roadmap

- **Year 1**: Priority 1 + Priority 2 (38% coverage)
- **Year 2**: Priority 3 (50-60% coverage)
- **Year 3**: Priority 4 + comprehensive coverage (70-80% coverage)

**Ultimate Goal**: 80%+ coverage of critical Kafka metrics with full Serventis RC6 API integration

---

## References

- **Apache Kafka Monitoring**: https://kafka.apache.org/documentation/#monitoring
- **Kafka JMX Metrics Reference**: https://docs.confluent.io/platform/current/kafka/monitoring.html
- **Serventis RC6 APIs**: `/workspaces/fullerstack-java/fullerstack-substrates/docs/RC6-UPGRADE-SUMMARY.md`
- **fullerstack-kafka Implementation Status**: `/workspaces/fullerstack-java/fullerstack-substrates/docs/RC6-FULLERSTACK-KAFKA-IMPACT-ASSESSMENT.md`
