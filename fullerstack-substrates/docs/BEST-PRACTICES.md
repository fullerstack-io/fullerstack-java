# Fullerstack Substrates - Best Practices Guide

## Overview

This guide provides best practices, patterns, and recommendations for building applications with Fullerstack Substrates.

**Target Audience:** Developers using Substrates to build observability systems

**Prerequisites:** Familiarity with [Architecture](ARCHITECTURE.md) and [Core Concepts](CONCEPTS.md)

---

## Table of Contents

1. [General Principles](#general-principles)
2. [Naming Best Practices](#naming-best-practices)
3. [Circuit Management](#circuit-management)
4. [Conduit and Channel Patterns](#conduit-and-channel-patterns)
5. [Flow and Sift Transformations](#flow-and-sift-transformations)
6. [Subscriber Management](#subscriber-management)
7. [Resource Lifecycle](#resource-lifecycle)
8. [Cell Hierarchies](#cell-hierarchies)
9. [Clock and Timing](#clock-and-timing)
10. [Testing Strategies](#testing-strategies)
11. [Performance Tips](#performance-tips)
12. [Common Pitfalls](#common-pitfalls)

---

## General Principles

### 1. Cache Pipes for Repeated Emissions

✅ **GOOD:**
```java
Pipe<MetricValue> pipe = conduit.get(cortex.name("kafka.broker.1.bytes-in"));

for (int i = 0; i < 1000000; i++) {
    pipe.emit(new MetricValue(System.currentTimeMillis(), bytesIn));
}
```

❌ **BAD:**
```java
for (int i = 0; i < 1000000; i++) {
    // Repeated lookups - unnecessary overhead
    conduit.get(cortex.name("kafka.broker.1.bytes-in"))
        .emit(new MetricValue(System.currentTimeMillis(), bytesIn));
}
```

**Why:** `conduit.get()` involves a map lookup. Cache the Pipe once and reuse it.

---

### 2. Use Hierarchical Names

✅ **GOOD:**
```java
Name brokerName = cortex.name("kafka.broker.1");
Name metricsName = brokerName.name("metrics");
Name bytesInName = metricsName.name("bytes-in");
// Result: "kafka.broker.1.metrics.bytes-in"

Pipe<MetricValue> pipe = conduit.get(bytesInName);
```

❌ **BAD:**
```java
// Building strings manually loses hierarchical structure
String name = "kafka.broker.1.metrics.bytes-in";
Pipe<MetricValue> pipe = conduit.get(cortex.name(name));
```

**Why:** Hierarchical names preserve parent-child relationships and enable queries.

---

### 3. Close Resources Explicitly

✅ **GOOD:**
```java
try (Circuit circuit = cortex.circuit(cortex.name("my-circuit"))) {
    // Use circuit
} // Automatically closed
```

Or with Scope:
```java
Scope scope = cortex.scope(cortex.name("transaction"));
Circuit circuit = scope.register(cortex.circuit(cortex.name("my-circuit")));
Conduit<Pipe<Event>, Event> conduit = scope.register(
    circuit.conduit(cortex.name("events"), Composer.pipe())
);

// Do work

scope.close();  // Closes all registered resources
```

❌ **BAD:**
```java
Circuit circuit = cortex.circuit(cortex.name("my-circuit"));
// Use circuit
// Never closed - resource leak!
```

**Why:** Prevents resource leaks, ensures proper cleanup of threads and schedulers.

---

## Naming Best Practices

### Use Dot Notation for Hierarchy

```java
// Application domain hierarchy
Name kafkaName = cortex.name("kafka");
Name brokerName = kafkaName.name("broker");
Name broker1Name = brokerName.name("1");

// Metrics hierarchy
Name metricsName = broker1Name.name("metrics");
Name bytesInName = metricsName.name("bytes-in");
Name bytesOutName = metricsName.name("bytes-out");

// Result: "kafka.broker.1.metrics.bytes-in"
//         "kafka.broker.1.metrics.bytes-out"
```

### Consistent Naming Conventions

```java
// ✅ GOOD - Consistent, hierarchical
cortex.name("kafka.broker.1.jvm.heap.used")
cortex.name("kafka.broker.1.jvm.heap.max")
cortex.name("kafka.broker.1.jvm.gc.count")

// ❌ BAD - Inconsistent structure
cortex.name("kafka_broker1_heap_used")
cortex.name("broker-1-gc-count")
cortex.name("JVM_MAX_HEAP_broker_1")
```

### Domain-Specific Naming

```java
// Kafka monitoring domain
interface KafkaNames {
    static Name kafka(Cortex cortex) {
        return cortex.name("kafka");
    }

    static Name broker(Cortex cortex, int brokerId) {
        return kafka(cortex).name("broker").name(String.valueOf(brokerId));
    }

    static Name metrics(Cortex cortex, int brokerId) {
        return broker(cortex, brokerId).name("metrics");
    }
}

// Usage
Name bytesIn = KafkaNames.metrics(cortex, 1).name("bytes-in");
```

---

## Circuit Management

### One Circuit Per Domain

```java
// ✅ GOOD - Separate circuits for different domains
Circuit kafkaCircuit = cortex.circuit(cortex.name("kafka"));
Circuit systemCircuit = cortex.circuit(cortex.name("system"));
Circuit appCircuit = cortex.circuit(cortex.name("application"));
```

❌ **BAD:**
```java
// Mixing unrelated domains in one circuit
Circuit everythingCircuit = cortex.circuit(cortex.name("everything"));
```

**Why:**
- Better isolation - failures don't cross domains
- Easier to reason about event ordering per domain
- Can close/restart domains independently

### Circuit Lifecycle

```java
public class KafkaMonitoringService {
    private final Cortex cortex;
    private Circuit circuit;

    public void start() {
        circuit = cortex.circuit(cortex.name("kafka.monitoring"));
        // Set up conduits, clocks, etc.
    }

    public void stop() {
        if (circuit != null) {
            circuit.close();
            circuit = null;
        }
    }
}
```

---

## Conduit and Channel Patterns

### Create Conduits by Signal Type

```java
// ✅ GOOD - One conduit per signal type
Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors =
    circuit.conduit(cortex.name("monitors"), Composer.pipe());

Conduit<Pipe<ServiceSignal>, ServiceSignal> services =
    circuit.conduit(cortex.name("services"), Composer.pipe());

Conduit<Pipe<QueueSignal>, QueueSignal> queues =
    circuit.conduit(cortex.name("queues"), Composer.pipe());
```

❌ **BAD:**
```java
// Mixing signal types in one conduit
Conduit<Pipe<Object>, Object> everything =
    circuit.conduit(cortex.name("everything"), Composer.pipe());
```

**Why:** Type safety, clear separation of concerns, easier to subscribe to specific signal types.

### Subject-Based Channel Access

```java
// Get Pipe for specific subject
Pipe<MonitorSignal> brokerHeap =
    monitors.get(cortex.name("broker-1.jvm.heap"));

Pipe<MonitorSignal> brokerGc =
    monitors.get(cortex.name("broker-1.jvm.gc"));

// Each subject gets its own Pipe with transformations
```

### Reuse Pipes for Same Subject

```java
public class BrokerMonitor {
    private final Pipe<MonitorSignal> heapPipe;
    private final Pipe<MonitorSignal> gcPipe;

    public BrokerMonitor(Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors, int brokerId) {
        String brokerPrefix = "broker-" + brokerId;
        this.heapPipe = monitors.get(cortex.name(brokerPrefix + ".jvm.heap"));
        this.gcPipe = monitors.get(cortex.name(brokerPrefix + ".jvm.gc"));
    }

    public void reportHeap(long used, long max) {
        heapPipe.emit(MonitorSignal.stable(/* ... */));
    }

    public void reportGc(long count, long time) {
        gcPipe.emit(MonitorSignal.stable(/* ... */));
    }
}
```

---

## Flow and Sift Transformations

### Configure Once at Conduit Creation

```java
// ✅ GOOD - Configure transformations when creating Conduit
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("numbers"),
    Composer.pipe(flow -> flow
        .sift(n -> n > 0)           // Filter: only positive
        .limit(1000)                // Max 1000 emissions
        .sample(10)                 // Every 10th emission
    )
);

// All Pipes from this Conduit inherit transformations
```

### Common Transformation Patterns

#### 1. Rate Limiting
```java
Composer.pipe(flow -> flow.limit(1000))  // Max 1000 emissions total
```

#### 2. Sampling
```java
Composer.pipe(flow -> flow.sample(100))  // Every 100th emission
```

#### 3. Filtering
```java
Composer.pipe(flow -> flow.sift(signal ->
    signal.status() == MonitorStatus.DEGRADED ||
    signal.status() == MonitorStatus.DOWN
))  // Only emit unhealthy signals
```

#### 4. Combining Transformations
```java
Composer.pipe(flow -> flow
    .sift(signal -> signal.severity() != ReporterSeverity.INFO)  // Skip INFO
    .sample(10)                                                   // Sample
    .limit(1000)                                                  // Limit
)
```

### Transformation Order Matters

```java
// ✅ GOOD - Filter first, then sample
flow.sift(n -> n > 100).sample(10)
// Out of 1000 emissions: ~500 pass filter, ~50 sampled

// ❌ DIFFERENT RESULT - Sample first, then filter
flow.sample(10).sift(n -> n > 100)
// Out of 1000 emissions: ~100 sampled, ~50 pass filter
```

---

## Subscriber Management

### Subscribe Once, Process Many

```java
// ✅ GOOD - One subscriber processes all emissions
monitors.subscribe(
    cortex.subscriber(
        cortex.name("health-aggregator"),
        (subject, registrar) -> {
            registrar.register(signal -> {
                // Process signal
                aggregateHealth(signal);
            });
        }
    )
);
```

### Subject-Specific Subscribers

```java
monitors.subscribe(
    cortex.subscriber(
        cortex.name("broker-1-monitor"),
        (subject, registrar) -> {
            registrar.register(signal -> {
                // Filter by subject in callback
                if (signal.subject().equals("broker-1.jvm.heap")) {
                    handleHeapSignal(signal);
                } else if (signal.subject().equals("broker-1.jvm.gc")) {
                    handleGcSignal(signal);
                }
            });
        }
    )
);
```

### Unsubscribe When Done

```java
Subscription subscription = monitors.subscribe(
    cortex.subscriber(
        cortex.name("temporary-listener"),
        (subject, registrar) -> {
            registrar.register(signal -> {
                // Process signal
            });
        }
    )
);

// Later, when no longer needed
subscription.close();
```

### Multiple Subscribers Pattern

```java
// Different subscribers for different purposes
monitors.subscribe(healthAggregator);     // Aggregate health
monitors.subscribe(alertingSystem);       // Send alerts
monitors.subscribe(metricsCollector);     // Collect metrics
monitors.subscribe(auditLogger);          // Audit log

// All receive the same emissions
```

---

## Resource Lifecycle

### Use Scope for Grouped Resources

```java
public class KafkaMonitoringSession {
    private final Scope scope;

    public KafkaMonitoringSession(Cortex cortex) {
        this.scope = cortex.scope(cortex.name("kafka-session"));
    }

    public void start() {
        // Register all resources with scope
        Circuit circuit = scope.register(
            cortex.circuit(cortex.name("kafka"))
        );

        Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors = scope.register(
            circuit.conduit(cortex.name("monitors"), Composer.pipe())
        );

        Clock clock = scope.register(
            circuit.clock(cortex.name("poll-clock"))
        );

        // Use resources
    }

    public void stop() {
        scope.close();  // Closes all registered resources
    }
}
```

### Try-With-Resources for Single Resources

```java
public void runOneTimeJob() {
    try (Circuit circuit = cortex.circuit(cortex.name("one-time"))) {
        Conduit<Pipe<Event>, Event> events =
            circuit.conduit(cortex.name("events"), Composer.pipe());

        // Do work

    } // Circuit automatically closed
}
```

### Explicit Cleanup Order

```java
public void shutdown() {
    // Close in reverse order of creation
    try {
        clock.close();
    } catch (Exception e) {
        log.error("Failed to close clock", e);
    }

    try {
        conduit.close();
    } catch (Exception e) {
        log.error("Failed to close conduit", e);
    }

    try {
        circuit.close();
    } catch (Exception e) {
        log.error("Failed to close circuit", e);
    }
}
```

---

## Cell Hierarchies

### Build Transformation Pipelines

```java
// Level 1: Broker stats → Broker health
Cell<BrokerStats, BrokerHealth> brokerCell = circuit.cell(
    cortex.name("broker-1"),
    stats -> new BrokerHealth(
        assessHeapHealth(stats.heapUsed, stats.heapMax),
        assessGcHealth(stats.gcCount, stats.gcTime),
        assessThreadHealth(stats.threadCount)
    )
);

// Level 2: Broker health → Cluster health
Cell<BrokerHealth, ClusterHealth> clusterCell = brokerCell.cell(
    cortex.name("cluster"),
    health -> aggregateClusterHealth(health)
);

// Subscribe to cluster health
clusterCell.subscribe(
    cortex.subscriber(
        cortex.name("cluster-monitor"),
        (subject, registrar) -> {
            registrar.register(clusterHealth -> {
                if (clusterHealth.status() == ClusterStatus.DEGRADED) {
                    sendAlert(clusterHealth);
                }
            });
        }
    )
);
```

### Multiple Children Pattern

```java
// Parent: Raw metrics
Cell<JMXMetrics, JMXMetrics> metricsCell = circuit.cell(
    cortex.name("jmx-metrics"),
    Function.identity()  // Pass-through
);

// Child 1: Heap analysis
Cell<JMXMetrics, HeapAnalysis> heapCell = metricsCell.cell(
    cortex.name("heap"),
    metrics -> analyzeHeap(metrics)
);

// Child 2: GC analysis
Cell<JMXMetrics, GcAnalysis> gcCell = metricsCell.cell(
    cortex.name("gc"),
    metrics -> analyzeGc(metrics)
);

// Child 3: Thread analysis
Cell<JMXMetrics, ThreadAnalysis> threadCell = metricsCell.cell(
    cortex.name("threads"),
    metrics -> analyzeThreads(metrics)
);

// All children transform the same input independently
```

---

## Clock and Timing

### Schedule Periodic Tasks

```java
Clock clock = circuit.clock(cortex.name("kafka-poller"));

// Poll JMX every second
Subscription subscription = clock.consume(
    cortex.name("jmx-poll"),
    Clock.Cycle.SECOND,
    instant -> {
        BrokerStats stats = jmxClient.fetchStats();
        brokerStatsPipe.emit(stats);
    }
);

// Later, stop polling
subscription.close();
```

### Multiple Cycles

```java
Clock clock = circuit.clock(cortex.name("multi-timer"));

// Every second - collect metrics
clock.consume(
    cortex.name("collect-metrics"),
    Clock.Cycle.SECOND,
    instant -> collectMetrics()
);

// Every minute - aggregate
clock.consume(
    cortex.name("aggregate"),
    Clock.Cycle.MINUTE,
    instant -> aggregateMetrics()
);

// Every hour - report
clock.consume(
    cortex.name("report"),
    Clock.Cycle.HOUR,
    instant -> generateReport()
);
```

### Avoid Long-Running Tasks

❌ **BAD:**
```java
clock.consume(
    cortex.name("slow-task"),
    Clock.Cycle.SECOND,
    instant -> {
        // This blocks the scheduler thread!
        doSlowWork();  // Takes 5 seconds
    }
);
```

✅ **GOOD:**
```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

clock.consume(
    cortex.name("async-task"),
    Clock.Cycle.SECOND,
    instant -> {
        // Submit to separate executor
        executor.submit(() -> doSlowWork());
    }
);
```

---

## Testing Strategies

### Unit Testing Components

```java
@Test
void testPipeEmission() {
    Cortex cortex = CortexRuntime.create();
    Circuit circuit = cortex.circuit(cortex.name("test"));

    Conduit<Pipe<String>, String> conduit =
        circuit.conduit(cortex.name("messages"), Composer.pipe());

    List<String> received = new CopyOnWriteArrayList<>();

    conduit.subscribe(
        cortex.subscriber(
            cortex.name("collector"),
            (subject, registrar) -> {
                registrar.register(received::add);
            }
        )
    );

    Pipe<String> pipe = conduit.get(cortex.name("test-subject"));
    pipe.emit("Hello");
    pipe.emit("World");

    // Allow async processing
    Thread.sleep(100);

    assertThat(received).containsExactly("Hello", "World");

    circuit.close();
}
```

### Testing Transformations

```java
@Test
void testSiftTransformation() {
    Cortex cortex = CortexRuntime.create();
    Circuit circuit = cortex.circuit(cortex.name("test"));

    Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
        cortex.name("numbers"),
        Composer.pipe(flow -> flow.sift(n -> n > 0))
    );

    List<Integer> received = new CopyOnWriteArrayList<>();
    conduit.subscribe(
        cortex.subscriber(
            cortex.name("collector"),
            (subject, registrar) -> registrar.register(received::add)
        )
    );

    Pipe<Integer> pipe = conduit.get(cortex.name("test"));
    pipe.emit(-1);  // Filtered out
    pipe.emit(0);   // Filtered out
    pipe.emit(1);   // Passes
    pipe.emit(5);   // Passes

    Thread.sleep(100);

    assertThat(received).containsExactly(1, 5);

    circuit.close();
}
```

### Testing Clock Behavior

```java
@Test
void testClockTicks() throws InterruptedException {
    Cortex cortex = CortexRuntime.create();
    Circuit circuit = cortex.circuit(cortex.name("test"));
    Clock clock = circuit.clock(cortex.name("timer"));

    AtomicInteger tickCount = new AtomicInteger(0);

    clock.consume(
        cortex.name("counter"),
        Clock.Cycle.MILLISECOND.scale(100),  // Every 100ms
        instant -> tickCount.incrementAndGet()
    );

    Thread.sleep(550);  // Wait for ~5 ticks

    assertThat(tickCount.get()).isGreaterThanOrEqualTo(5);

    circuit.close();
}
```

### Testing Resource Cleanup

```java
@Test
void testScopeCleanup() {
    Cortex cortex = CortexRuntime.create();
    Scope scope = cortex.scope(cortex.name("test-scope"));

    Circuit circuit = scope.register(cortex.circuit(cortex.name("test")));
    AtomicBoolean circuitClosed = new AtomicBoolean(false);

    // Spy on close
    circuit = new Circuit() {
        // Delegate all methods to original circuit
        // Override close to set flag
        @Override
        public void close() {
            circuit.close();
            circuitClosed.set(true);
        }
    };

    scope.close();

    assertThat(circuitClosed).isTrue();
}
```

---

## Performance Tips

### 1. Reuse Components

```java
// ✅ GOOD - Create once, reuse
Pipe<MetricValue> pipe = conduit.get(name);
for (MetricValue value : metrics) {
    pipe.emit(value);
}

// ❌ BAD - Repeated creation
for (MetricValue value : metrics) {
    conduit.get(name).emit(value);
}
```

### 2. Batch When Possible

```java
// ✅ GOOD - Batch processing
List<MonitorSignal> signals = collectSignals();
Pipe<MonitorSignal> pipe = monitors.get(name);
for (MonitorSignal signal : signals) {
    pipe.emit(signal);
}

// ❌ BAD - One at a time with overhead
for (MonitorSignal signal : collectSignals()) {
    monitors.get(name).emit(signal);
}
```

### 3. Use Appropriate Transformations

```java
// ✅ GOOD - Filter early
flow.sift(expensiveCheck)    // Expensive filter first
    .sift(cheapCheck)        // Cheap filter second
    .limit(100)

// ❌ BAD - Cheap check on everything
flow.sift(cheapCheck)        // Processes everything
    .sift(expensiveCheck)    // Still processes many
    .limit(100)
```

### 4. Avoid Blocking in Callbacks

```java
// ❌ BAD - Blocking in subscriber callback
conduit.subscribe(
    cortex.subscriber(
        cortex.name("slow"),
        (subject, registrar) -> {
            registrar.register(event -> {
                Thread.sleep(1000);  // Blocks event processing!
            });
        }
    )
);

// ✅ GOOD - Async processing
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
conduit.subscribe(
    cortex.subscriber(
        cortex.name("fast"),
        (subject, registrar) -> {
            registrar.register(event -> {
                executor.submit(() -> processSlowly(event));
            });
        }
    )
);
```

---

## Common Pitfalls

### 1. Forgetting to Close Resources

❌ **PROBLEM:**
```java
public void startMonitoring() {
    Circuit circuit = cortex.circuit(cortex.name("kafka"));
    // Use circuit
} // Circuit never closed - resource leak!
```

✅ **SOLUTION:**
```java
public class MonitoringService {
    private Circuit circuit;

    public void start() {
        circuit = cortex.circuit(cortex.name("kafka"));
    }

    public void stop() {
        if (circuit != null) {
            circuit.close();
        }
    }
}
```

### 2. Mixing Signal Types

❌ **PROBLEM:**
```java
Conduit<Pipe<Object>, Object> mixed =
    circuit.conduit(cortex.name("mixed"), Composer.pipe());

mixed.get(name).emit(new MonitorSignal(/* ... */));
mixed.get(name).emit(new ServiceSignal(/* ... */));
mixed.get(name).emit("A string?");  // Type safety lost!
```

✅ **SOLUTION:**
```java
Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors =
    circuit.conduit(cortex.name("monitors"), Composer.pipe());

Conduit<Pipe<ServiceSignal>, ServiceSignal> services =
    circuit.conduit(cortex.name("services"), Composer.pipe());

monitors.get(name).emit(new MonitorSignal(/* ... */));
services.get(name).emit(new ServiceSignal(/* ... */));
```

### 3. Creating Too Many Circuits

❌ **PROBLEM:**
```java
// One circuit per metric!
for (String metric : metrics) {
    Circuit circuit = cortex.circuit(cortex.name(metric));
    // Use circuit
}
```

✅ **SOLUTION:**
```java
// One circuit per domain
Circuit kafkaCircuit = cortex.circuit(cortex.name("kafka"));

// Many conduits in one circuit
for (String brokerMetric : brokerMetrics) {
    Conduit<Pipe<MetricValue>, MetricValue> conduit =
        kafkaCircuit.conduit(cortex.name(brokerMetric), Composer.pipe());
}
```

### 4. Not Handling Async Nature

❌ **PROBLEM:**
```java
List<String> received = new ArrayList<>();

conduit.subscribe(
    cortex.subscriber(
        cortex.name("collector"),
        (subject, registrar) -> registrar.register(received::add)
    )
);

pipe.emit("Hello");
assertEquals(1, received.size());  // FAILS! Async processing not complete
```

✅ **SOLUTION:**
```java
List<String> received = new CopyOnWriteArrayList<>();

conduit.subscribe(
    cortex.subscriber(
        cortex.name("collector"),
        (subject, registrar) -> registrar.register(received::add)
    )
);

pipe.emit("Hello");
Thread.sleep(100);  // Wait for async processing
assertEquals(1, received.size());  // Now passes
```

### 5. Ignoring Hierarchical Names

❌ **PROBLEM:**
```java
cortex.name("kafka_broker_1_bytes_in")
cortex.name("kafka-broker-1-bytes-out")
cortex.name("broker.1.gc.count")
```

✅ **SOLUTION:**
```java
Name kafka = cortex.name("kafka");
Name broker1 = kafka.name("broker").name("1");
Name metrics = broker1.name("metrics");

metrics.name("bytes-in")
metrics.name("bytes-out")
metrics.name("gc-count")
```

---

## Summary

**Key Takeaways:**

1. ✅ **Cache pipes** - Reuse for repeated emissions
2. ✅ **Use hierarchical names** - Build from parent to child
3. ✅ **Close resources** - Always clean up when done
4. ✅ **One circuit per domain** - Not per metric
5. ✅ **Type-safe conduits** - One conduit per signal type
6. ✅ **Configure transformations once** - At conduit creation
7. ✅ **Handle async** - Wait for processing in tests
8. ✅ **Use Scope** - For grouped resource cleanup
9. ✅ **Avoid blocking** - In subscriber callbacks
10. ✅ **Test thoroughly** - Unit, integration, resource cleanup

**Philosophy:** Keep it simple, keep it type-safe, clean up after yourself.

---

## References

- [Architecture Guide](ARCHITECTURE.md)
- [Performance Guide](PERFORMANCE.md)
- [Core Concepts](CONCEPTS.md)
- [Async Architecture](ASYNC-ARCHITECTURE.md)
- [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)
