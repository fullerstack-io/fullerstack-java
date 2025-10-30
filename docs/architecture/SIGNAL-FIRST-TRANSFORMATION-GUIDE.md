# Signal-First Transformation Guide

**Date**: 2025-10-29
**Purpose**: Detailed implementation guide for migrating from data-first to signal-first architecture
**Related**: [ADR-001: Signal-First Architecture](./ADR-001-SIGNAL-FIRST-NOT-DATA-FIRST.md)

---

## Table of Contents

1. [Overview](#overview)
2. [Core Principles](#core-principles)
3. [Broker Monitoring Transformation](#broker-monitoring-transformation)
4. [Producer Monitoring Transformation](#producer-monitoring-transformation)
5. [Cell Hierarchy Changes](#cell-hierarchy-changes)
6. [Testing Strategy](#testing-strategy)
7. [Migration Plan](#migration-plan)
8. [Validation Checklist](#validation-checklist)

---

## Overview

### The Problem

**Current (Data-First - WRONG):**
```
Sensor → Collect raw JMX → emit(BrokerMetrics) → Composer interprets → emit(MonitorSignal)
                                    ↑
                           Context LOST here
```

**Target (Signal-First - CORRECT):**
```
Sensor → Collect raw JMX → Compare to baseline → Interpret → emit(MonitorSignal)
                                    ↑                            ↑
                           Context EXISTS here         Signal with meaning
```

### Key Insight from Humainary Blogs

> "Monitoring renaissance: Redesign monitoring featuring **purposeful signals over raw data streams** (encode transitions and anomalies with intent)"

**Interpretation must happen WHERE context exists** - at the sensor, not in a Composer downstream.

---

## Core Principles

### Principle 1: Sensors Emit Signals, Not Data

**DON'T:**
```java
// Sensor emits data bag
BrokerMetrics metrics = new BrokerMetrics(
    brokerId, heapUsed, heapMax, cpuUsage, ...
);
cell.emit(metrics);  // Data without meaning
```

**DO:**
```java
// Sensor emits interpreted signal
MonitorSignal signal = assessBrokerHealth(
    brokerId, jmxData, baseline, trends
);
cell.emit(signal);  // Signal with embedded meaning
```

### Principle 2: Interpretation Requires Context

**Context Available at Sensor:**
- ✅ Historical baselines (via BaselineService)
- ✅ Current trends (increasing/decreasing)
- ✅ Expected values for this entity at this time
- ✅ Recent observation history

**Context LOST at Composer:**
- ❌ No baseline knowledge
- ❌ No trend awareness
- ❌ Cannot distinguish "85% heap is normal during peak" vs "unusual"

### Principle 3: Signals Encode Intent and Transitions

**DON'T:**
```java
// Just state current value
payload: { "heapUsed": "8GB", "heapMax": "10GB" }
```

**DO:**
```java
// Encode transition, baseline, assessment, recommendation
payload: {
    "metric": "heap",
    "current": "80%",
    "baseline": "60%",
    "transition": "60% → 80% over 10min",
    "trend": "increasing",
    "assessment": "Memory pressure detected, GC thrashing likely",
    "recommendation": "Investigate memory leak or increase heap size",
    "confidence": "MEASURED"
}
```

### Principle 4: Cells Route Signals, Don't Interpret Data

**DON'T:**
```java
// Cell transforms data to signal
Cell<BrokerMetrics, MonitorSignal> cell = circuit.cell(
    new BrokerMetricsComposer(),  // Interpretation happens here - WRONG
    observerPipe
);
```

**DO:**
```java
// Cell routes/enriches signals
Cell<MonitorSignal, MonitorSignal> cell = circuit.cell(
    new SignalEnrichmentComposer(),  // Only enriches (VectorClock, etc.)
    observerPipe
);
```

---

## Broker Monitoring Transformation

### Component Changes Overview

| Component | Current State | Target State | Status |
|-----------|---------------|--------------|--------|
| **BrokerMetrics** | Data bag with 15 fields | DELETE | 🔴 Remove |
| **BrokerMonitoringAgent** | Emits BrokerMetrics | Emits MonitorSignal | 🟡 Transform |
| **BrokerMetricsComposer** | Interprets BrokerMetrics | DELETE | 🔴 Remove |
| **BaselineService** | N/A | Provides baselines/trends | 🟢 Created |
| **BrokerHealthAssessor** | N/A | Interprets JMX + baseline | 🟡 Create |
| **SignalEnrichmentComposer** | N/A | Enriches signals (VectorClock) | 🟡 Create |

### 1. BrokerMetrics.java - DELETE

**Current File**: `/workspaces/fullerstack-java/fullerstack-kafka-broker/src/main/java/io/fullerstack/kafka/broker/models/BrokerMetrics.java`

**Status**: 🔴 **DELETE THIS FILE**

**Why**: This is a data bag. We emit MonitorSignals directly instead.

**Migration**:
- All references to `BrokerMetrics` must be removed
- Replace with `MonitorSignal` throughout codebase
- Update tests to expect MonitorSignal, not BrokerMetrics

### 2. BrokerHealthAssessor.java - CREATE NEW

**New File**: `/workspaces/fullerstack-java/fullerstack-kafka-broker/src/main/java/io/fullerstack/kafka/broker/assessment/BrokerHealthAssessor.java`

**Purpose**: Interprets raw JMX data into MonitorSignals using baselines and trends.

**Interface**:
```java
/**
 * Assesses broker health from raw JMX data and emits interpreted MonitorSignal.
 * <p>
 * This is the CORE of signal-first architecture - interpretation happens here,
 * WHERE context exists (baselines, trends, historical data).
 * <p>
 * Responsibilities:
 * - Compare current JMX values to baselines
 * - Detect transitions ("heap went from 60% → 85%")
 * - Assess severity using Monitors.Condition enum
 * - Determine confidence level
 * - Build rich payload with assessment, evidence, recommendations
 */
public class BrokerHealthAssessor {
    private final BaselineService baselineService;

    public BrokerHealthAssessor(BaselineService baselineService) {
        this.baselineService = baselineService;
    }

    /**
     * Assess broker health from raw JMX data.
     *
     * @param brokerId Broker identifier
     * @param subject Substrates Subject for this broker
     * @param jmxData Raw JMX metrics (heap, CPU, etc.)
     * @return MonitorSignal with interpreted assessment
     */
    public MonitorSignal assess(String brokerId, Subject subject, JmxData jmxData) {
        // 1. Extract metrics
        double heapPercent = jmxData.heapPercent();
        double cpuUsage = jmxData.cpuUsage();
        long underReplicated = jmxData.underReplicatedPartitions();

        // 2. Get baselines
        double expectedHeap = baselineService.getExpectedHeapPercent(brokerId, Instant.now());
        double expectedCpu = baselineService.getExpectedCpuUsage(brokerId, Instant.now());

        // 3. Get trends
        String heapTrend = baselineService.getTrend(brokerId, "heap", Duration.ofMinutes(10));
        String cpuTrend = baselineService.getTrend(brokerId, "cpu", Duration.ofMinutes(10));

        // 4. Assess condition
        Monitors.Condition condition = determineCondition(
            heapPercent, expectedHeap,
            cpuUsage, expectedCpu,
            underReplicated,
            heapTrend, cpuTrend
        );

        // 5. Determine confidence
        Monitors.Confidence confidence = determineConfidence(
            baselineService.getConfidence(brokerId, "heap"),
            heapTrend
        );

        // 6. Build rich payload
        Map<String, String> payload = buildPayload(
            heapPercent, expectedHeap, heapTrend,
            cpuUsage, expectedCpu, cpuTrend,
            underReplicated,
            condition
        );

        // 7. Record observation for future baselines
        baselineService.recordObservation(brokerId, "heap", heapPercent, Instant.now());
        baselineService.recordObservation(brokerId, "cpu", cpuUsage, Instant.now());

        // 8. Create and return signal
        return MonitorSignal.create(subject, condition, confidence, payload);
    }

    private Monitors.Condition determineCondition(
        double heapPercent, double expectedHeap,
        double cpuUsage, double expectedCpu,
        long underReplicated,
        String heapTrend, String cpuTrend
    ) {
        // CRITICAL: Offline partitions or under-replicated → DEFECTIVE
        if (underReplicated > 0) {
            return Monitors.Condition.DEFECTIVE;
        }

        // DEGRADED: Heap significantly above baseline with increasing trend
        if (heapPercent > expectedHeap * 1.3 && "increasing".equals(heapTrend)) {
            return Monitors.Condition.DEGRADED;
        }

        // DEGRADED: CPU very high
        if (cpuUsage > 0.90) {
            return Monitors.Condition.DEGRADED;
        }

        // DIVERGING: Heap above baseline but stable
        if (heapPercent > expectedHeap * 1.2) {
            return Monitors.Condition.DIVERGING;
        }

        // ERRATIC: High variance in metrics
        if ("erratic".equals(heapTrend) || "erratic".equals(cpuTrend)) {
            return Monitors.Condition.ERRATIC;
        }

        // CONVERGING: Was problematic but improving
        if ("decreasing".equals(heapTrend) && heapPercent > expectedHeap * 1.1) {
            return Monitors.Condition.CONVERGING;
        }

        // STABLE: Everything normal
        return Monitors.Condition.STABLE;
    }

    private Monitors.Confidence determineConfidence(
        double baselineConfidence,
        String trend
    ) {
        // High confidence if we have solid baseline and stable trend
        if (baselineConfidence > 0.8 && "stable".equals(trend)) {
            return Monitors.Confidence.CONFIRMED;
        }

        // Medium confidence if baseline exists
        if (baselineConfidence > 0.5) {
            return Monitors.Confidence.MEASURED;
        }

        // Low confidence if limited data
        return Monitors.Confidence.TENTATIVE;
    }

    private Map<String, String> buildPayload(
        double heapPercent, double expectedHeap, String heapTrend,
        double cpuUsage, double expectedCpu, String cpuTrend,
        long underReplicated,
        Monitors.Condition condition
    ) {
        Map<String, String> payload = new HashMap<>();

        // Core metrics
        payload.put("metric", "broker_health");
        payload.put("heap_current", String.format("%.1f%%", heapPercent));
        payload.put("heap_baseline", String.format("%.1f%%", expectedHeap));
        payload.put("heap_trend", heapTrend);
        payload.put("cpu_current", String.format("%.2f", cpuUsage));
        payload.put("cpu_baseline", String.format("%.2f", expectedCpu));
        payload.put("cpu_trend", cpuTrend);

        if (underReplicated > 0) {
            payload.put("under_replicated_partitions", String.valueOf(underReplicated));
        }

        // Interpretation
        payload.put("assessment", buildAssessment(condition, heapPercent, expectedHeap, underReplicated));

        // Evidence
        payload.put("evidence", buildEvidence(heapPercent, expectedHeap, heapTrend, cpuUsage, underReplicated));

        // Recommendation
        if (condition != Monitors.Condition.STABLE) {
            payload.put("recommendation", buildRecommendation(condition, heapPercent, expectedHeap, cpuUsage, underReplicated));
        }

        return payload;
    }

    private String buildAssessment(Monitors.Condition condition, double heapPercent, double expectedHeap, long underReplicated) {
        return switch (condition) {
            case STABLE -> "Broker operating normally within expected parameters";
            case CONVERGING -> "Broker recovering from previous issues, metrics improving";
            case DIVERGING -> String.format("Broker diverging from baseline (heap %.1f%% vs %.1f%% expected)", heapPercent, expectedHeap);
            case ERRATIC -> "Broker showing unstable behavior with high metric variance";
            case DEGRADED -> "Broker experiencing operational degradation requiring attention";
            case DEFECTIVE -> underReplicated > 0
                ? String.format("Broker DEFECTIVE with %d under-replicated partitions", underReplicated)
                : "Broker in defective state with severe operational issues";
            case DOWN -> "Broker is non-operational";
        };
    }

    private String buildEvidence(double heapPercent, double expectedHeap, String heapTrend, double cpuUsage, long underReplicated) {
        StringBuilder evidence = new StringBuilder();

        if (heapPercent > expectedHeap * 1.2) {
            evidence.append(String.format("Heap %.1f%% vs baseline %.1f%% (%s trend). ",
                heapPercent, expectedHeap, heapTrend));
        }

        if (cpuUsage > 0.80) {
            evidence.append(String.format("CPU %.0f%%. ", cpuUsage * 100));
        }

        if (underReplicated > 0) {
            evidence.append(String.format("%d under-replicated partitions. ", underReplicated));
        }

        return evidence.toString().trim();
    }

    private String buildRecommendation(Monitors.Condition condition, double heapPercent, double expectedHeap, double cpuUsage, long underReplicated) {
        if (underReplicated > 0) {
            return "Investigate replication lag, check broker connectivity and ISR status";
        }

        if (heapPercent > expectedHeap * 1.3) {
            return "Investigate heap usage: check for memory leaks, increase heap size, or analyze GC behavior";
        }

        if (cpuUsage > 0.90) {
            return "Investigate CPU usage: check request load, thread pool saturation, or consider scaling";
        }

        return "Monitor closely for trend changes";
    }

    /**
     * Raw JMX data container.
     */
    public record JmxData(
        double heapPercent,
        double cpuUsage,
        long requestRate,
        long underReplicatedPartitions,
        long offlinePartitions,
        long networkIdlePercent,
        long requestHandlerIdlePercent
    ) {}
}
```

### 3. BrokerMonitoringAgent.java - TRANSFORM

**Current File**: `/workspaces/fullerstack-java/fullerstack-kafka-broker/src/main/java/io/fullerstack/kafka/broker/sensors/BrokerMonitoringAgent.java`

**Status**: 🟡 **MAJOR TRANSFORMATION**

**Changes Required**:

#### Change 1: Constructor - Add Dependencies

**BEFORE:**
```java
public BrokerMonitoringAgent(ClusterConfig config, BiConsumer<Name, BrokerMetrics> metricsEmitter) {
    this.config = Objects.requireNonNull(config, "config cannot be null");
    this.metricsEmitter = Objects.requireNonNull(metricsEmitter, "metricsEmitter cannot be null");
    // ...
}
```

**AFTER:**
```java
public BrokerMonitoringAgent(
    ClusterConfig config,
    BiConsumer<Name, MonitorSignal> signalEmitter,  // ← Changed type
    BaselineService baselineService                  // ← NEW dependency
) {
    this.config = Objects.requireNonNull(config, "config cannot be null");
    this.signalEmitter = Objects.requireNonNull(signalEmitter, "signalEmitter cannot be null");
    this.baselineService = Objects.requireNonNull(baselineService, "baselineService cannot be null");
    this.assessor = new BrokerHealthAssessor(baselineService);  // ← NEW
    // ...
}
```

#### Change 2: Fields - Update Types

**BEFORE:**
```java
private final BiConsumer<Name, BrokerMetrics> metricsEmitter;
```

**AFTER:**
```java
private final BiConsumer<Name, MonitorSignal> signalEmitter;
private final BaselineService baselineService;
private final BrokerHealthAssessor assessor;
```

#### Change 3: collectAndEmit() - Signal-First Logic

**BEFORE (Data-First):**
```java
private void collectAndEmit(BrokerEndpoint endpoint) {
    // 1. Collect raw JMX metrics
    BrokerMetrics metrics = collector.collect(endpoint.jmxUrl);

    // 2. Normalize broker ID
    BrokerMetrics normalizedMetrics = new BrokerMetrics(
        endpoint.brokerId,
        metrics.heapUsed(),
        metrics.heapMax(),
        // ... all 15 fields
    );

    // 3. Increment VectorClock
    vectorClock.increment(endpoint.brokerId);

    // 4. Create Name
    Name brokerName = config.getBrokerName(cortex, endpoint.brokerId);

    // 5. Emit DATA BAG
    metricsEmitter.accept(brokerName, normalizedMetrics);
}
```

**AFTER (Signal-First):**
```java
private void collectAndEmit(BrokerEndpoint endpoint) {
    // 1. Collect raw JMX metrics
    BrokerMetrics rawMetrics = collector.collect(endpoint.jmxUrl);

    // 2. Create Subject for this broker
    Name brokerName = config.getBrokerName(cortex, endpoint.brokerId);
    Subject brokerSubject = cortex.subject(
        cortex.name("kafka.broker.health"),
        brokerName
    );

    // 3. Convert to JmxData
    JmxData jmxData = new JmxData(
        rawMetrics.heapUsagePercent(),  // Already a percentage
        rawMetrics.cpuUsage(),
        rawMetrics.requestRate(),
        rawMetrics.underReplicatedPartitions(),
        rawMetrics.offlinePartitionsCount(),
        rawMetrics.networkProcessorAvgIdlePercent(),
        rawMetrics.requestHandlerAvgIdlePercent()
    );

    // 4. INTERPRET using assessor (THIS IS THE KEY CHANGE)
    MonitorSignal signal = assessor.assess(
        endpoint.brokerId,
        brokerSubject,
        jmxData
    );

    // 5. Enrich with VectorClock
    vectorClock.increment(endpoint.brokerId);
    MonitorSignal enrichedSignal = signal.withClock(
        vectorClock.getSnapshot(endpoint.brokerId)
    );

    // 6. Emit INTERPRETED SIGNAL
    signalEmitter.accept(brokerName, enrichedSignal);

    logger.debug("Emitted {} signal for broker {} (confidence: {})",
        enrichedSignal.status().condition(),
        endpoint.brokerId,
        enrichedSignal.status().confidence());
}
```

**Key Differences**:
1. ✅ **Interpretation happens HERE** using BrokerHealthAssessor
2. ✅ **Baseline comparison** via BaselineService
3. ✅ **Trend detection** via BaselineService
4. ✅ **Rich payload** with assessment, evidence, recommendations
5. ✅ **MonitorSignal emitted**, not BrokerMetrics

### 4. BrokerMetricsComposer.java - DELETE

**Current File**: `/workspaces/fullerstack-java/fullerstack-kafka-broker/src/main/java/io/fullerstack/kafka/broker/composers/BrokerMetricsComposer.java`

**Status**: 🔴 **DELETE THIS FILE**

**Why**: This Composer was doing interpretation (data → signal). Now interpretation happens in BrokerMonitoringAgent.

**Replacement**: Use SignalEnrichmentComposer if additional enrichment needed (but primary enrichment already done in agent).

### 5. Cell Hierarchy - UPDATE

**Location**: Where Cells are created (likely in runtime/setup code)

**BEFORE:**
```java
// Cell transforms data to signal
Cell<BrokerMetrics, MonitorSignal> brokerCell = circuit.cell(
    new BrokerMetricsComposer(),  // Interprets data
    observerPipe
);
```

**AFTER:**
```java
// Cell routes/enriches signals
Cell<MonitorSignal, MonitorSignal> brokerCell = circuit.cell(
    Composer.pipe(),  // Just routing, no transformation
    observerPipe
);

// OR if additional enrichment needed:
Cell<MonitorSignal, MonitorSignal> brokerCell = circuit.cell(
    new SignalEnrichmentComposer(),  // Only enriches (adds metadata, etc.)
    observerPipe
);
```

---

## Producer Monitoring Transformation

### Component Changes Overview

| Component | Current State | Target State | Status |
|-----------|---------------|--------------|--------|
| **ProducerEventMetrics** | Data bag with 9 fields | DELETE | 🔴 Remove |
| **ProducerEventInterceptor** | Emits ProducerEventMetrics | Emits ServiceSignal | 🟡 Transform |
| **ProducerEventComposer** | Interprets ProducerEventMetrics | DELETE | 🔴 Remove |
| **BaselineService** | Exists | Use for latency baselines | 🟢 Reuse |

### 1. ProducerEventMetrics.java - DELETE

**Current File**: `/workspaces/fullerstack-java/fullerstack-kafka-producer/src/main/java/io/fullerstack/kafka/producer/models/ProducerEventMetrics.java`

**Status**: 🔴 **DELETE THIS FILE**

**Why**: This is a data bag. We emit ServiceSignals directly instead.

### 2. ProducerEventInterceptor.java - TRANSFORM

**Current File**: `/workspaces/fullerstack-java/fullerstack-kafka-producer/src/main/java/io/fullerstack/kafka/producer/sensors/ProducerEventInterceptor.java`

**Status**: 🟡 **MAJOR TRANSFORMATION**

#### Change 1: Configuration - Add BaselineService

**BEFORE:**
```java
public static final String METRICS_PIPE_KEY = "fullerstack.metrics.pipe";

private Pipe<ProducerEventMetrics> metricsPipe;
```

**AFTER:**
```java
public static final String SIGNAL_PIPE_KEY = "fullerstack.signal.pipe";
public static final String BASELINE_SERVICE_KEY = "fullerstack.baseline.service";

private Pipe<ServiceSignal> signalPipe;
private BaselineService baselineService;
```

#### Change 2: configure() - Extract BaselineService

**BEFORE:**
```java
@Override
public void configure(Map<String, ?> configs) {
    Object pipeObj = configs.get(METRICS_PIPE_KEY);
    if (pipeObj instanceof Pipe) {
        this.metricsPipe = (Pipe<ProducerEventMetrics>) pipeObj;
    }
}
```

**AFTER:**
```java
@Override
@SuppressWarnings("unchecked")
public void configure(Map<String, ?> configs) {
    // Extract signal pipe
    Object pipeObj = configs.get(SIGNAL_PIPE_KEY);
    if (pipeObj instanceof Pipe) {
        this.signalPipe = (Pipe<ServiceSignal>) pipeObj;
    }

    // Extract baseline service
    Object baselineObj = configs.get(BASELINE_SERVICE_KEY);
    if (baselineObj instanceof BaselineService) {
        this.baselineService = (BaselineService) baselineObj;
    }

    if (signalPipe != null && baselineService == null) {
        logger.warn("Signal pipe configured but no baseline service - " +
            "will emit signals without baseline comparisons");
    }
}
```

#### Change 3: onSend() - Emit ServiceSignal

**BEFORE (Data-First):**
```java
@Override
public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
    if (metricsPipe == null) return record;

    try {
        // Record send time
        inFlightRequests.put(key, System.nanoTime());

        // Emit DATA BAG
        ProducerEventMetrics metrics = ProducerEventMetrics.call(
            producerId,
            record.topic(),
            record.partition() != null ? record.partition() : -1
        );
        metricsPipe.emit(metrics);
    } catch (Exception e) {
        logger.error("Error in onSend", e);
    }

    return record;
}
```

**AFTER (Signal-First):**
```java
@Override
public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
    if (signalPipe == null) return record;

    try {
        // Record send time for latency tracking
        String key = requestKey(record.topic(), record.partition());
        inFlightRequests.put(key, System.nanoTime());

        // Create Subject
        Subject producerSubject = cortex.subject(
            cortex.name("kafka.producer.interactions"),
            cortex.name(producerId + "." + record.topic())
        );

        // Build payload with context
        Map<String, String> payload = new HashMap<>();
        payload.put("producerId", producerId);
        payload.put("topic", record.topic());
        payload.put("partition", String.valueOf(record.partition() != null ? record.partition() : -1));
        payload.put("assessment", "INITIATED");

        // Emit INTERPRETED SIGNAL
        ServiceSignal signal = ServiceSignal.call(producerSubject, payload);
        signalPipe.emit(signal);

        logger.trace("Producer {} CALL for topic {}", producerId, record.topic());
    } catch (Exception e) {
        logger.error("Error in onSend", e);
    }

    return record;
}
```

#### Change 4: onAcknowledgement() - Interpret with Baseline

**BEFORE (Data-First):**
```java
@Override
public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    if (metricsPipe == null) return;

    try {
        long latencyMs = calculateLatency(metadata);

        ProducerEventMetrics metrics;
        if (exception == null) {
            metrics = ProducerEventMetrics.succeeded(producerId, metadata, latencyMs);
        } else {
            metrics = ProducerEventMetrics.failed(producerId, metadata.topic(),
                metadata.partition(), exception, latencyMs);
        }

        metricsPipe.emit(metrics);  // DATA BAG
    } catch (Exception e) {
        logger.error("Error in onAcknowledgement", e);
    }
}
```

**AFTER (Signal-First):**
```java
@Override
public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
    if (signalPipe == null) return;

    try {
        // Calculate latency
        String key = requestKey(metadata.topic(), metadata.partition());
        Long sendTime = inFlightRequests.remove(key);
        long latencyMs = sendTime != null ? (System.nanoTime() - sendTime) / 1_000_000 : 0L;

        // Create Subject
        Subject producerSubject = cortex.subject(
            cortex.name("kafka.producer.interactions"),
            cortex.name(producerId + "." + metadata.topic())
        );

        ServiceSignal signal;

        if (exception == null) {
            // SUCCESS - compare to baseline
            signal = assessSuccessfulSend(producerSubject, metadata, latencyMs);
        } else {
            // FAILURE - build failure signal
            signal = assessFailedSend(producerSubject, metadata, exception, latencyMs);
        }

        signalPipe.emit(signal);  // INTERPRETED SIGNAL

    } catch (Exception e) {
        logger.error("Error in onAcknowledgement", e);
    }
}

private ServiceSignal assessSuccessfulSend(Subject subject, RecordMetadata metadata, long latencyMs) {
    Map<String, String> payload = new HashMap<>();
    payload.put("producerId", producerId);
    payload.put("topic", metadata.topic());
    payload.put("partition", String.valueOf(metadata.partition()));
    payload.put("offset", String.valueOf(metadata.offset()));
    payload.put("latency_ms", String.valueOf(latencyMs));

    // Get baseline if available
    if (baselineService != null && baselineService.hasBaseline(producerId)) {
        long expectedLatency = baselineService.getExpectedProducerLatency(producerId, metadata.topic());
        payload.put("baseline_latency_ms", String.valueOf(expectedLatency));

        // INTERPRET: Is this slow?
        if (latencyMs > expectedLatency * 1.5) {
            // SLOW - 50% above baseline
            payload.put("assessment", "SLOW");
            payload.put("concern", "HIGH");
            payload.put("interpretation", String.format(
                "Latency %.0f%% above baseline (actual: %dms, expected: %dms)",
                ((latencyMs - expectedLatency) * 100.0 / expectedLatency),
                latencyMs, expectedLatency
            ));
            payload.put("recommendation", "Investigate broker load or network latency");

        } else if (latencyMs > expectedLatency * 1.2) {
            // ELEVATED - 20% above baseline
            payload.put("assessment", "ELEVATED");
            payload.put("concern", "MEDIUM");
            payload.put("interpretation", String.format(
                "Latency elevated (actual: %dms, expected: %dms)",
                latencyMs, expectedLatency
            ));

        } else {
            // NORMAL
            payload.put("assessment", "NORMAL");
            payload.put("interpretation", "Within expected latency range");
        }

        // Record observation for future baselines
        baselineService.recordObservation(producerId, "latency." + metadata.topic(),
            latencyMs, Instant.now());
    } else {
        // No baseline - just report latency
        payload.put("assessment", "SUCCEEDED");
        payload.put("interpretation", "Send completed successfully");
    }

    // Add broker metadata
    payload.put("serialized_key_size", String.valueOf(metadata.serializedKeySize()));
    payload.put("serialized_value_size", String.valueOf(metadata.serializedValueSize()));
    payload.put("broker_timestamp", String.valueOf(metadata.timestamp()));

    return ServiceSignal.succeeded(subject, payload);
}

private ServiceSignal assessFailedSend(Subject subject, RecordMetadata metadata, Exception exception, long latencyMs) {
    Map<String, String> payload = new HashMap<>();
    payload.put("producerId", producerId);
    payload.put("topic", metadata.topic());
    payload.put("partition", String.valueOf(metadata.partition()));
    payload.put("latency_ms", String.valueOf(latencyMs));
    payload.put("assessment", "FAILED");
    payload.put("concern", "CRITICAL");

    // Exception details
    payload.put("exception_type", exception.getClass().getName());
    payload.put("exception_message", exception.getMessage() != null ? exception.getMessage() : "");
    if (exception.getCause() != null) {
        payload.put("exception_cause", exception.getCause().toString());
    }

    // Interpretation
    payload.put("interpretation", "Producer send failed: " + exception.getMessage());

    // Recommendation based on exception type
    String recommendation = buildFailureRecommendation(exception);
    payload.put("recommendation", recommendation);

    return ServiceSignal.failed(subject, payload);
}

private String buildFailureRecommendation(Exception exception) {
    String exceptionType = exception.getClass().getSimpleName();

    if (exceptionType.contains("Timeout")) {
        return "Check broker availability and network connectivity";
    } else if (exceptionType.contains("NotLeader")) {
        return "Partition leader election in progress, retry expected";
    } else if (exceptionType.contains("RecordTooLarge")) {
        return "Reduce message size or increase broker max.message.bytes";
    } else if (exceptionType.contains("Authentication")) {
        return "Check producer credentials and broker security configuration";
    }

    return "Investigate broker logs for error details";
}
```

**Key Differences**:
1. ✅ **Baseline comparison** for latency
2. ✅ **Assessment** (NORMAL, ELEVATED, SLOW for success; FAILED for errors)
3. ✅ **Interpretation** with context
4. ✅ **Recommendations** based on exception type
5. ✅ **ServiceSignal emitted**, not ProducerEventMetrics

### 3. ProducerEventComposer.java - DELETE

**Current File**: `/workspaces/fullerstack-java/fullerstack-kafka-producer/src/main/java/io/fullerstack/kafka/producer/composers/ProducerEventComposer.java`

**Status**: 🔴 **DELETE THIS FILE**

**Why**: Interpretation now happens in ProducerEventInterceptor.

---

## Cell Hierarchy Changes

### Current Architecture (Data-First - WRONG)

```java
// Broker monitoring
Cell<BrokerMetrics, MonitorSignal> brokerCell = circuit.cell(
    new BrokerMetricsComposer(),  // Transforms data to signal
    observerPipe
);

// Producer monitoring
Cell<ProducerEventMetrics, ServiceSignal> producerCell = circuit.cell(
    new ProducerEventComposer(),  // Transforms data to signal
    observerPipe
);
```

**Problem**: Cells are doing interpretation that should happen at sensor level.

### Target Architecture (Signal-First - CORRECT)

```java
// Broker monitoring
Cell<MonitorSignal, MonitorSignal> brokerCell = circuit.cell(
    Composer.pipe(),  // Just routing, no transformation
    observerPipe
);

// Producer monitoring
Cell<ServiceSignal, ServiceSignal> producerCell = circuit.cell(
    Composer.pipe(),  // Just routing, no transformation
    observerPipe
);
```

**Benefit**: Cells are simple routers. Interpretation already done by sensors.

### Granular Cells for Topics/Partitions

According to ADR-001, we should have granular Cells for semantic entities:

```java
// Circuit for broker health
Circuit brokerHealthCircuit = cortex.circuit(cortex.name("kafka.broker.health"));

// Container manages broker Cells dynamically
Container<Pool<Monitor>, Source<MonitorSignal>> brokers =
    brokerHealthCircuit.container(
        cortex.name("brokers"),
        Composer.pipe()
    );

// Each broker is a Cell that emits MonitorSignals
// brokers.get(cortex.name("broker-1")) → Cell for broker-1
// brokers.get(cortex.name("broker-2")) → Cell for broker-2

// Hierarchical subscription - automatically observe ALL brokers
brokers.source().subscribe(
    cortex.subscriber(
        cortex.name("cluster-health-observer"),
        (brokerSubject, registrar) -> {
            registrar.register(brokerSource -> {
                // Subscribe to this broker's signals
                brokerSource.subscribe(healthAggregator);
            });
        }
    )
);
```

**For Topics/Partitions:**

```java
// Circuit for partition behavior
Circuit partitionCircuit = cortex.circuit(cortex.name("kafka.partitions"));

// Container for topics
Container<Pool<Queue>, Source<QueueSignal>> topics =
    partitionCircuit.container(
        cortex.name("topics"),
        Composer.pipe()
    );

// Each topic has its own Cell
Cell<QueueSignal, QueueSignal> ordersTopicCell = topics.get(cortex.name("orders"));

// For partitions within a topic, create nested Container
// topics.get("orders").container("partitions") → Container for orders partitions
// Each partition signals: PUT, TAKE, OVERFLOW
```

**Key Pattern**:
- **One Cell per semantic entity** (broker, producer, topic, partition)
- **Container manages Cell lifecycle** (create on demand, cleanup on removal)
- **Hierarchical subscription** (observe all without manual tracking)

---

## Testing Strategy

### Unit Tests

#### 1. BaselineService Tests

**File**: `SimpleBaselineServiceTest.java`

**Test Cases:**
- ✅ Returns static baselines initially
- ✅ Records observations correctly
- ✅ Calculates trends (stable, increasing, decreasing, erratic)
- ✅ Determines confidence based on observation count
- ✅ Thread-safety of observation recording

#### 2. BrokerHealthAssessor Tests

**File**: `BrokerHealthAssessorTest.java`

**Test Cases:**
- ✅ STABLE when heap within baseline ±10%
- ✅ DIVERGING when heap > baseline * 1.2
- ✅ DEGRADED when heap > baseline * 1.3 with increasing trend
- ✅ DEFECTIVE when under-replicated partitions > 0
- ✅ DOWN when offline partitions > 0
- ✅ ERRATIC when trend is erratic
- ✅ CONVERGING when improving from previous issue
- ✅ Confidence CONFIRMED when baseline confidence > 0.8
- ✅ Confidence MEASURED when baseline confidence > 0.5
- ✅ Confidence TENTATIVE when baseline confidence < 0.5
- ✅ Payload contains assessment, evidence, recommendation
- ✅ Records observations to BaselineService

#### 3. BrokerMonitoringAgent Tests

**File**: `BrokerMonitoringAgentTest.java`

**Changes Required:**
- ❌ Remove all tests expecting BrokerMetrics
- ✅ Add tests expecting MonitorSignal
- ✅ Verify signal condition matches JMX state
- ✅ Verify payload contains interpretation
- ✅ Verify VectorClock incremented
- ✅ Verify baseline service called

**Example Test:**
```java
@Test
void collectAndEmit_degradedBroker_emitsDegradedSignal() {
    // Given: JMX data showing degraded state
    BrokerMetrics jmx = new BrokerMetrics(
        "broker-1",
        8_500_000_000L,  // 85% of 10GB heap
        10_000_000_000L,
        0.75,  // 75% CPU
        // ... other fields
    );

    when(collector.collect(any())).thenReturn(jmx);
    when(baselineService.getExpectedHeapPercent(any(), any())).thenReturn(60.0);  // Baseline 60%
    when(baselineService.getTrend(any(), eq("heap"), any())).thenReturn("increasing");

    // When: Agent collects
    agent.collectAndEmit(endpoint);

    // Then: Should emit DEGRADED signal
    verify(signalEmitter).accept(any(), signalCaptor.capture());
    MonitorSignal signal = signalCaptor.getValue();

    assertThat(signal.status().condition()).isEqualTo(Monitors.Condition.DEGRADED);
    assertThat(signal.status().confidence()).isEqualTo(Monitors.Confidence.MEASURED);
    assertThat(signal.payload()).containsEntry("heap_current", "85.0%");
    assertThat(signal.payload()).containsEntry("heap_baseline", "60.0%");
    assertThat(signal.payload()).containsEntry("heap_trend", "increasing");
    assertThat(signal.payload()).containsKey("assessment");
    assertThat(signal.payload()).containsKey("recommendation");
}
```

#### 4. ProducerEventInterceptor Tests

**File**: `ProducerEventInterceptorTest.java`

**Changes Required:**
- ❌ Remove all tests expecting ProducerEventMetrics
- ✅ Add tests expecting ServiceSignal
- ✅ Verify signal assessment (NORMAL, ELEVATED, SLOW, FAILED)
- ✅ Verify latency comparison to baseline
- ✅ Verify payload contains interpretation

**Example Test:**
```java
@Test
void onAcknowledgement_slowSend_emitsElevatedAssessment() {
    // Given: Baseline 100ms, actual 180ms (80% above)
    when(baselineService.getExpectedProducerLatency(any(), any())).thenReturn(100L);
    when(baselineService.hasBaseline(any())).thenReturn(true);

    // When: Send completes with high latency
    interceptor.onSend(record);
    Thread.sleep(180);  // Simulate 180ms latency
    interceptor.onAcknowledgement(metadata, null);

    // Then: Should emit SUCCEEDED with ELEVATED assessment
    verify(signalPipe).accept(signalCaptor.capture());
    ServiceSignal signal = signalCaptor.getValue();

    assertThat(signal.signal()).isEqualTo(Services.Signal.SUCCEEDED);
    assertThat(signal.payload()).containsEntry("assessment", "ELEVATED");
    assertThat(signal.payload()).containsEntry("concern", "MEDIUM");
    assertThat(signal.payload()).containsKey("interpretation");
}
```

### Integration Tests

#### 1. End-to-End Signal Flow

**Test**: Verify signal flows from sensor → Cell → Observer with correct interpretation

```java
@Test
void brokerDegradation_signalFlowsWithInterpretation() {
    // Given: Real Circuit + Cell + Observer
    Circuit circuit = cortex.circuit(cortex.name("test.broker.health"));
    List<MonitorSignal> capturedSignals = new ArrayList<>();

    Cell<MonitorSignal, MonitorSignal> brokerCell = circuit.cell(
        Composer.pipe(),
        signal -> capturedSignals.add(signal)
    );

    // Given: Agent with real dependencies
    BaselineService baseline = new SimpleBaselineService();
    BrokerMonitoringAgent agent = new BrokerMonitoringAgent(
        config,
        (name, signal) -> brokerCell.emit(signal),
        baseline
    );

    // When: Agent collects from degraded broker
    agent.start();
    Thread.sleep(1000);  // Wait for collection
    agent.shutdown();

    // Then: Should capture DEGRADED signal with interpretation
    assertThat(capturedSignals).isNotEmpty();
    MonitorSignal signal = capturedSignals.get(0);
    assertThat(signal.status().condition()).isIn(
        Monitors.Condition.STABLE,
        Monitors.Condition.DEGRADED,
        Monitors.Condition.DIVERGING
    );
    assertThat(signal.payload()).containsKey("assessment");
    assertThat(signal.payload()).containsKey("evidence");
}
```

---

## Migration Plan

### Phase 1: Create New Components (Week 1)

**Day 1-2: Infrastructure**
- ✅ Create BaselineService interface
- ✅ Create SimpleBaselineService implementation
- ✅ Write BaselineService unit tests

**Day 3-4: Broker Assessment**
- ✅ Create BrokerHealthAssessor
- ✅ Write BrokerHealthAssessor unit tests
- ✅ Verify all Monitors.Condition cases covered

**Day 5: Producer Assessment**
- ✅ Create helper methods for ProducerEventInterceptor (assessSuccessfulSend, assessFailedSend)
- ✅ Write unit tests for assessment logic

### Phase 2: Transform Broker Monitoring (Week 2)

**Day 1-2: BrokerMonitoringAgent**
- ✅ Update constructor (add BaselineService)
- ✅ Add BrokerHealthAssessor field
- ✅ Transform collectAndEmit() method
- ✅ Change BiConsumer type (BrokerMetrics → MonitorSignal)

**Day 3: Update Tests**
- ✅ Update BrokerMonitoringAgentTest
- ✅ Remove BrokerMetrics expectations
- ✅ Add MonitorSignal expectations
- ✅ Verify signal interpretation

**Day 4: Cell Hierarchy**
- ✅ Update Cell creation (BrokerMetrics → MonitorSignal)
- ✅ Remove BrokerMetricsComposer
- ✅ Use Composer.pipe() or remove Composer entirely

**Day 5: Delete Old Components**
- ✅ Delete BrokerMetrics.java
- ✅ Delete BrokerMetricsComposer.java
- ✅ Update all references

### Phase 3: Transform Producer Monitoring (Week 3)

**Day 1-2: ProducerEventInterceptor**
- ✅ Update configure() method (add BaselineService)
- ✅ Transform onSend() method
- ✅ Transform onAcknowledgement() method
- ✅ Add assessment methods

**Day 3: Update Tests**
- ✅ Update ProducerEventInterceptorTest
- ✅ Remove ProducerEventMetrics expectations
- ✅ Add ServiceSignal expectations
- ✅ Verify signal interpretation

**Day 4: Cell Hierarchy**
- ✅ Update Cell creation (ProducerEventMetrics → ServiceSignal)
- ✅ Remove ProducerEventComposer

**Day 5: Delete Old Components**
- ✅ Delete ProducerEventMetrics.java
- ✅ Delete ProducerEventComposer.java
- ✅ Update all references

### Phase 4: Integration & Validation (Week 4)

**Day 1-2: Integration Tests**
- ✅ Write end-to-end signal flow tests
- ✅ Verify broker degradation detected
- ✅ Verify producer latency assessment
- ✅ Verify Cell hierarchy works

**Day 3: Granular Cells**
- ✅ Implement Container for topics
- ✅ Implement Container for partitions
- ✅ Test hierarchical subscription

**Day 4: Performance Testing**
- ✅ Verify BaselineService performance
- ✅ Verify no latency regression
- ✅ Memory profiling

**Day 5: Documentation**
- ✅ Update architecture docs
- ✅ Update API docs
- ✅ Create migration guide for users

---

## Validation Checklist

### Signal-First Principles

- [ ] ✅ Sensors emit signals (MonitorSignal, ServiceSignal), not data bags
- [ ] ✅ Interpretation happens at sensor level, WHERE context exists
- [ ] ✅ Baseline comparison performed at observation point
- [ ] ✅ Signals contain assessment, evidence, recommendations
- [ ] ✅ Cells route/enrich signals, don't interpret data
- [ ] ✅ No data bags (BrokerMetrics, ProducerEventMetrics) remaining

### Humainary Blog Alignment

- [ ] ✅ "Purposeful signals over raw data streams" - Signals encode transitions and intent
- [ ] ✅ "Contextual enrichment at generation point" - Baselines and trends at sensor
- [ ] ✅ "Top-down cognition" - High-level signal (DEGRADED) before details
- [ ] ✅ "OODA loop support" - Signals enable immediate Orient step
- [ ] ✅ "Measurement to sense-making" - Signals provide meaning, not just numbers

### Technical Correctness

- [ ] ✅ All tests passing (unit + integration)
- [ ] ✅ BaselineService working (trends, confidence)
- [ ] ✅ MonitorSignals emitted with correct Monitors.Condition
- [ ] ✅ ServiceSignals emitted with correct Services.Signal
- [ ] ✅ VectorClock incremented correctly
- [ ] ✅ Subject hierarchy correct (circuit + entity)
- [ ] ✅ Cell types correct (MonitorSignal → MonitorSignal, not BrokerMetrics → MonitorSignal)
- [ ] ✅ No Composers doing interpretation (only enrichment)

### Documentation

- [ ] ✅ ADR-001 complete
- [ ] ✅ Transformation guide complete
- [ ] ✅ API docs updated
- [ ] ✅ Examples provided
- [ ] ✅ Migration guide for users

---

## Summary

This transformation guide provides:

1. **Clear rationale** - Why signal-first is correct (Humainary blog alignment)
2. **Detailed changes** - Exactly what changes in each component
3. **Before/After examples** - Line-by-line transformations
4. **Testing strategy** - How to validate correctness
5. **Migration plan** - Phased approach over 4 weeks
6. **Validation checklist** - Criteria for success

**Key Takeaway**:

> Interpretation must happen WHERE context exists - at the sensor level, using baselines and trends. Cells route signals; they don't interpret data.

This aligns with Humainary's vision of **purposeful signals that encode transitions and intent**, supporting **top-down human cognition** and the **OODA loop**.

---

**Next Steps**: Review this guide, then proceed with Phase 1 implementation.
