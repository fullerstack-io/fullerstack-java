# ADR-001: Signal-First Architecture (Not Data-First)

**Date**: 2025-10-29
**Status**: ACCEPTED
**Deciders**: Architecture Team
**Context**: Post-refactoring review after implementing ProducerEventMetrics pattern

---

## Context and Problem Statement

After reading Humainary's blog posts ["The Observability Paradox"](https://humainary.io/blog/the-observability-paradox/) and ["The Gap Between Human Cognition and Observability"](https://humainary.io/blog/the-gap-between-human-cognition-and-observability/), we realized our current implementation violates the core principles of semiotic observability.

**The Critical Flaw:** We are collecting **data bags** (BrokerMetrics, ProducerEventMetrics) and deferring interpretation to Composers, rather than emitting **interpretable signals** at the point of observation.

### Current Anti-Pattern (Data-First)

```java
// Sensor collects raw data
public record BrokerMetrics(
    String brokerId,
    long heapUsed,      // Raw measurement
    long heapMax,       // Raw measurement
    double cpuUsage,    // Raw measurement
    // ... 13 raw measurements
) {}

// Flow: Data collection → Later interpretation
JMX Collector
  → emit(BrokerMetrics)      // Data bag with no meaning
  → Cell<BrokerMetrics, MonitorSignal>
  → Composer (extracts meaning)  // Interpretation happens HERE
  → emit(MonitorSignal)           // Finally a signal
```

**Problems:**
1. **Context loss** - By the time Composer sees data, baseline/trend context is lost
2. **Bottom-up cognition** - Forces operators to assemble meaning from fragments
3. **Matches traditional monitoring** - Raw metrics with deferred interpretation
4. **Violates "purposeful signals"** principle - No encoded intent or transition meaning

### What We Should Be Doing (Signal-First)

From Humainary blog:
> "Monitoring renaissance: Redesign monitoring featuring **purposeful signals over raw data streams** (encode transitions and anomalies with intent)"

```java
// Sensor interprets at observation point
JMX Collector WITH INTERPRETATION
  → reads raw JMX (heapUsed: 85%, gcTime: 500ms)
  → compares to baseline (normal heap: 60% at this hour)
  → detects transition (60% → 85% over 10 minutes)
  → interprets meaning (GC thrashing imminent)
  → emit(MonitorSignal.degraded(
      confidence: MEASURED,
      payload: {
          "primary_concern": "heap_pressure",
          "transition": "60% → 85% over 10min",
          "baseline": "normal 60% at this hour",
          "assessment": "GC thrashing imminent",
          "recommendation": "investigate memory leak"
      }
  ))  // ← Signal with MEANING from the start
```

**Benefits:**
1. **Contextual enrichment at generation point** - Baseline/trend known where data is collected
2. **Top-down cognition** - High-level signal ("DEGRADED") before details
3. **Purposeful signals** - Encoded transitions, baselines, and intent
4. **Supports projection** - "What happens next" embedded in signal

---

## Decision Drivers

### 1. Cognitive Alignment (Endsley's Model)

**Current (Wrong):**
- **Perception**: Raw metrics (heapUsed: 8GB, cpuUsage: 0.85)
- **Comprehension**: ??? (Operator must figure out what this means)
- **Projection**: ??? (Cannot predict outcomes from raw data)

**Signal-First (Correct):**
- **Perception**: MonitorSignal.DEGRADED ("Broker degrading")
- **Comprehension**: payload explains WHY ("heap pressure, GC thrashing")
- **Projection**: payload suggests OUTCOME ("imminent failure if continues")

From blog: *"Effective observability should extend human situational intelligence rather than burden it"*

### 2. OODA Loop Support

**Current (Wrong):**
- **Observe**: Raw metrics appear on dashboard
- **Orient**: Operator must correlate heap + GC + CPU to understand situation ❌
- **Decide**: Cannot decide without understanding
- **Act**: Delayed response

**Signal-First (Correct):**
- **Observe**: Signal arrives: "Broker DEGRADED due to heap pressure"
- **Orient**: Immediate comprehension of situation ✅
- **Decide**: Recommended action provided
- **Act**: Rapid response

From blog: *"Current systems fail at observation (alert fatigue) and orientation (incomprehensible dashboards)"*

### 3. "Measurement to Sense-Making" Transformation

From blog: *"Transform monitoring from measurement to sense-making, creating a semantic layer that operates in alignment with human cognition"*

**Measurement (Wrong):**
```
heapUsed=8589934592, heapMax=10737418240, gcTime=500
```

**Sense-Making (Correct):**
```
"Broker-1 is experiencing memory pressure with GC thrashing.
Heap usage 80% (baseline 60%), GC time 500ms (baseline 50ms).
Pattern suggests memory leak or undersized heap.
Recommend: investigate heap dump or increase heap size."
```

### 4. Semiotic Hierarchy Alignment

From vision document:

| Level | Current (Wrong) | Signal-First (Correct) |
|-------|-----------------|------------------------|
| **Level 0** | BrokerMetrics with 13 fields ← WE ARE HERE | (Skip this level) |
| **Level 1** | ??? | MonitorSignal.DEGRADED ← START HERE |
| **Level 2** | Composer interprets | Observers interpret |

**We must START at Level 1 (Signals), not Level 0 (Raw Data).**

---

## Decision

**We adopt Signal-First Architecture:**

### Principle 1: Sensors Emit Signals, Not Data

**Before:**
```java
// ❌ Sensor emits raw data
class BrokerMonitoringAgent {
    void collectMetrics() {
        BrokerMetrics metrics = new BrokerMetrics(
            brokerId, heapUsed, heapMax, cpuUsage, ...
        );
        cell.emit(metrics);  // Data bag
    }
}
```

**After:**
```java
// ✅ Sensor emits interpreted signal
class BrokerMonitoringAgent {
    void assess() {
        // Collect raw JMX
        long heapUsed = jmx.getHeapUsed();
        long heapMax = jmx.getHeapMax();

        // INTERPRET with context
        double heapPercent = (heapUsed * 100.0) / heapMax;
        double baseline = baselineService.getExpectedHeap(brokerId, now());

        Monitors.Condition condition;
        if (heapPercent > baseline * 1.3) {
            condition = Monitors.Condition.DEGRADED;
        } else if (heapPercent > baseline * 1.1) {
            condition = Monitors.Condition.DIVERGING;
        } else {
            condition = Monitors.Condition.STABLE;
        }

        // Emit SIGNAL with meaning
        MonitorSignal signal = MonitorSignal.of(
            subject,
            condition,
            Monitors.Confidence.MEASURED,
            Map.of(
                "heapPercent", String.valueOf(heapPercent),
                "baseline", String.valueOf(baseline),
                "assessment", condition.name(),
                "evidence", "heap " + heapPercent + "% vs baseline " + baseline + "%"
            )
        );

        cell.emit(signal);  // Signal with embedded meaning
    }
}
```

### Principle 2: Cells Route Signals, Don't Interpret Data

**Before:**
```java
// ❌ Cell transforms data → signal
Cell<BrokerMetrics, MonitorSignal> brokerCell = circuit.cell(
    new BrokerMetricsComposer(),  // Composer interprets here
    observerPipe
);
```

**After:**
```java
// ✅ Cell routes/enriches signals
Cell<MonitorSignal, MonitorSignal> brokerCell = circuit.cell(
    new SignalEnrichmentComposer(),  // Only enriches (adds VectorClock, etc.)
    observerPipe
);
```

### Principle 3: Interpretation Happens Where Context Exists

**Context exists at sensor level:**
- Historical baselines
- Current trends
- Expected values for this entity at this time
- System load context

**Context is LOST by the time data reaches Composer:**
- Composer sees isolated data point
- No baseline knowledge
- No trend context
- Cannot distinguish "85% heap is normal during peak" vs "85% heap is unusual"

### Principle 4: Granular Cells for Semantic Entities

Each semantic entity should be a Cell that signals its state:

| Semantic Entity | Cell Type | Signals State As |
|-----------------|-----------|------------------|
| Broker | `Cell<MonitorSignal, MonitorSignal>` | STABLE / DEGRADED / DOWN |
| Producer | `Cell<ServiceSignal, ServiceSignal>` | CALL / SUCCEEDED / FAILED |
| Topic | `Cell<QueueSignal, QueueSignal>` | PUT / TAKE / OVERFLOW |
| Partition | `Cell<QueueSignal, QueueSignal>` | PUT / TAKE / OVERFLOW |
| Consumer Group | `Cell<ServiceSignal, ServiceSignal>` | START / STOP / SUSPEND |

**Container manages Cell lifecycle dynamically:**

```java
// ✅ Correct pattern
Container<Pool<Monitor>, Source<MonitorSignal>> brokers =
    brokerCircuit.container(
        cortex.name("brokers"),
        Composer.pipe()  // No transformation, just routing
    );

// Each broker is a Cell
// When broker-3 joins cluster → Container creates Cell for broker-3
// Observers subscribed to Container automatically see broker-3's signals
```

---

## Consequences

### Positive

1. **Cognitive Alignment** ✅
   - Signals match how humans understand systems (top-down)
   - Operators see "Broker DEGRADED" immediately, not "heap=8GB"
   - Supports OODA loop (Observe → Orient → Decide → Act)

2. **Contextual Enrichment** ✅
   - Baseline comparisons at observation point
   - Transition detection ("60% → 85% over 10min")
   - Trend awareness ("increasing" vs "stable high")

3. **Purposeful Signals** ✅
   - Encoded intent ("GC thrashing imminent")
   - Recommended actions ("investigate memory leak")
   - Assessment confidence (MEASURED, TENTATIVE)

4. **Simpler Observers** ✅
   - Observers receive interpreted signals, not raw data
   - Can focus on correlation/patterns, not basic interpretation
   - Example: Observer sees "broker DEGRADED + producer FAILED" → detects cascade

5. **True Semiotic Observability** ✅
   - Starts at Level 1 (Signals), not Level 0 (Data)
   - Matches Humainary vision
   - Enables cognitive hierarchy (Signals → Signs → Symptoms → Syndromes)

### Negative

1. **More Complex Sensors** ⚠️
   - Sensors must have baseline/context knowledge
   - Requires BaselineService or similar
   - More logic at collection point

   **Mitigation**: This complexity is NECESSARY. Deferring it doesn't eliminate it, just moves it to wrong place.

2. **Potential Signal Noise** ⚠️
   - Every state change emits signal
   - Could generate high volume

   **Mitigation**:
   - Use Monitors.Confidence to filter (only emit MEASURED/CONFIRMED)
   - Adaptive filtration (suppress stable signals, amplify transitions)
   - ML-learned signal importance over time

3. **Refactoring Cost** ⚠️
   - Must redesign existing BrokerMetrics/ProducerEventMetrics
   - Update all tests
   - Reimplement sensors

   **Mitigation**: Cost is justified - we're fixing fundamental architectural flaw

### Neutral

1. **Cell Granularity**
   - More Cells (one per broker, producer, partition)
   - Container manages lifecycle
   - Hierarchical subscription pattern

---

## Implementation Plan

### Phase 1: Baseline Infrastructure (Week 1)

**1.1 Create BaselineService**
```java
interface BaselineService {
    double getExpectedHeap(String brokerId, Instant time);
    double getExpectedCpu(String brokerId, Instant time);
    long getExpectedLatency(String producerId, String topic);
    String getTrend(String entity, String metric, Duration window);
}
```

**1.2 Create Signal Factories**
```java
class MonitorSignalFactory {
    MonitorSignal assess(
        Subject subject,
        double value,
        double baseline,
        String metric,
        Map<String, String> evidence
    );
}
```

### Phase 2: Redesign Broker Monitoring (Week 2)

**2.1 Transform BrokerMonitoringAgent**
- Remove BrokerMetrics emission
- Add interpretation logic
- Emit MonitorSignal directly
- Compare to baselines
- Detect transitions

**2.2 Update BrokerMetricsComposer → SignalEnrichmentComposer**
- Remove interpretation logic
- Only add VectorClock
- Only add temporal metadata

**2.3 Update Cell hierarchy**
```java
Cell<MonitorSignal, MonitorSignal> brokerCell = ...;
// Not Cell<BrokerMetrics, MonitorSignal>
```

### Phase 3: Redesign Producer Monitoring (Week 3)

**3.1 Transform ProducerEventInterceptor**
- Remove ProducerEventMetrics emission
- Add latency baseline checking
- Emit ServiceSignal directly with assessment
- Include "NORMAL" vs "SLOW" interpretation

**3.2 Remove ProducerEventMetrics class**
- No longer needed
- All interpretation happens in interceptor

**3.3 Remove ProducerEventComposer**
- Signals already interpreted
- Use SignalEnrichmentComposer if needed

### Phase 4: Add Granular Cells (Week 4)

**4.1 Topic-level Cells**
```java
Container<Pool<Queue>, Source<QueueSignal>> topics =
    topicCircuit.container(
        cortex.name("topics"),
        Composer.pipe()
    );
```

**4.2 Partition-level Cells**
```java
// Each partition signals its queue state
Cell<QueueSignal, QueueSignal> partition = topicContainer.get(
    cortex.name("orders.partition-0")
);
```

**4.3 Dynamic Cell creation**
- Container creates Cells on demand
- New broker joins → Cell created automatically
- New partition added → Cell created automatically

### Phase 5: Update Observers (Week 5)

**5.1 Simplify Observers**
- Receive interpreted signals, not raw data
- Focus on correlation/patterns
- Example: CascadeDetector correlates "broker DEGRADED" + "producer FAILED"

**5.2 Remove data interpretation**
- No more "if heapUsed > 85%..." logic
- Signals already say "DEGRADED"

### Phase 6: Testing & Validation (Week 6)

**6.1 Unit tests**
- Test signal assessment logic
- Test baseline comparisons
- Test transition detection

**6.2 Integration tests**
- Test Cell hierarchy
- Test hierarchical subscription
- Test dynamic Cell creation

**6.3 Validate against Humainary principles**
- Cognitive alignment check
- OODA loop support check
- "Purposeful signals" validation

---

## Related Documents

- [Humainary Blog: The Observability Paradox](https://humainary.io/blog/the-observability-paradox/)
- [Humainary Blog: The Gap Between Human Cognition and Observability](https://humainary.io/blog/the-gap-between-human-cognition-and-observability/)
- [Vision Document: Semiotic Observability Complete Vision](/workspaces/kafka-obs/docs/architecture/SEMIOTIC-OBSERVABILITY-COMPLETE-VISION.md)
- [Serventis APIs Kafka Mapping](/workspaces/kafka-obs/docs/architecture/SERVENTIS-APIS-KAFKA-MAPPING.md)

---

## Key Quotes from Humainary Blogs

> "In the relentless pursuit of collecting sufficient data to comprehend all that has transpired, the discipline has systematically devalued and undermined the tools and practices essential for understanding the present moment."

> "Current observability presents isolated metrics disconnected from coherent situational models."

> "Real-time comprehension must become the cognitive core of modern system stewardship."

> "Monitoring renaissance: Redesign monitoring featuring purposeful signals over raw data streams (encode transitions and anomalies with intent)."

> "Like cartographers attempting to navigate continents using individual grains of sand, we've built monitoring systems that provide data without delivering situational understanding."

---

## Decision Record

**Status**: ACCEPTED
**Date**: 2025-10-29
**Supersedes**: Previous data-first architecture
**Next Review**: After Phase 3 completion (3 weeks)

**Signed**:
- Architecture Team
- Winston (Architect Agent)

---

## Appendix: Before/After Examples

### Example 1: Broker Health

**Before (Data-First - WRONG):**
```java
// Sensor
BrokerMetrics metrics = new BrokerMetrics(
    "broker-1",
    8589934592L,  // heapUsed
    10737418240L, // heapMax
    0.85,         // cpuUsage
    // ... 10 more raw fields
);
cell.emit(metrics);

// Composer (too late for context!)
MonitorSignal signal = assessHealth(metrics);
// How do we know if 85% heap is normal or concerning?
```

**After (Signal-First - CORRECT):**
```java
// Sensor (with context!)
double heapPercent = 80.0;
double baseline = baselineService.getExpectedHeap("broker-1", now());  // Returns 60%
String trend = trendService.getTrend("broker-1", "heap", Duration.ofMinutes(10));  // Returns "increasing"

MonitorSignal signal = MonitorSignal.degraded(
    brokerSubject,
    Monitors.Confidence.MEASURED,
    Map.of(
        "metric", "heap",
        "value", "80%",
        "baseline", "60%",
        "trend", "increasing",
        "assessment", "Memory pressure detected, GC thrashing likely",
        "recommendation", "Investigate memory leak or increase heap"
    )
);
cell.emit(signal);  // Signal with full context and meaning
```

### Example 2: Producer Send

**Before (Data-First - WRONG):**
```java
// Interceptor
ProducerEventMetrics metrics = ProducerEventMetrics.succeeded(
    "producer-1",
    metadata,
    250L  // latency
);
cell.emit(metrics);

// Composer (no baseline knowledge!)
ServiceSignal signal = new ServiceSignal(...);
// Is 250ms good or bad? We don't know!
```

**After (Signal-First - CORRECT):**
```java
// Interceptor (with baseline!)
long latency = 250L;
long expectedLatency = baselineService.getExpectedLatency("producer-1", "orders");  // Returns 150ms

if (latency > expectedLatency * 1.5) {
    // SLOW - emit with concern
    ServiceSignal signal = ServiceSignal.succeeded(
        producerSubject,
        Map.of(
            "outcome", "SUCCEEDED",
            "assessment", "SLOW",
            "latency", "250ms",
            "baseline", "150ms",
            "concern", "HIGH",
            "interpretation", "Latency 67% above baseline, investigate broker load"
        )
    );
} else {
    // NORMAL - emit without concern
    ServiceSignal signal = ServiceSignal.succeeded(
        producerSubject,
        Map.of(
            "outcome", "SUCCEEDED",
            "assessment", "NORMAL",
            "latency", "250ms",
            "baseline", "150ms"
        )
    );
}
cell.emit(signal);  // Signal with assessment
```

---

**End of ADR-001**
