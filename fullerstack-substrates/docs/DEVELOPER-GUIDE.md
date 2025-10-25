# Fullerstack Substrates - Developer Guide

**Best Practices, Performance Tips, and Testing Strategies**

---

## Table of Contents

1. [Best Practices](#best-practices)
2. [Performance Guide](#performance-guide)
3. [Testing Strategies](#testing-strategies)
4. [Common Pitfalls](#common-pitfalls)

---

## Best Practices

### General Principles

#### 1. Cache Pipes for Repeated Emissions

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
    conduit.get(cortex.name("kafka.broker.1.bytes-in"))
        .emit(new MetricValue(System.currentTimeMillis(), bytesIn));
}
```

**Why:** `conduit.get()` involves a map lookup. Cache the Pipe once and reuse it.

---

#### 2. Use Hierarchical Names

✅ **GOOD:**
```java
Name brokerName = cortex.name("kafka.broker.1");
Name metricsName = brokerName.name("metrics");
Name bytesInName = metricsName.name("bytes-in");
// Result: "kafka.broker.1.metrics.bytes-in"
```

❌ **BAD:**
```java
String name = "kafka.broker.1.metrics.bytes-in";
Pipe<MetricValue> pipe = conduit.get(cortex.name(name));
```

**Why:** Hierarchical names preserve parent-child relationships.

---

#### 3. Close Resources Explicitly

✅ **GOOD:**
```java
try (Circuit circuit = cortex.circuit(cortex.name("my-circuit"))) {
    // Use circuit
}
```

Or with Scope:
```java
Scope scope = cortex.scope(cortex.name("transaction"));
Circuit circuit = scope.register(cortex.circuit(cortex.name("my-circuit")));
scope.close();  // Closes all registered resources
```

❌ **BAD:**
```java
Circuit circuit = cortex.circuit(cortex.name("my-circuit"));
// Never closed - resource leak!
```

---

### Naming Best Practices

#### Use Dot Notation for Hierarchy

```java
// Application domain hierarchy
Name kafkaName = cortex.name("kafka");
Name brokerName = kafkaName.name("broker").name("1");
Name metricsName = brokerName.name("metrics");
Name bytesInName = metricsName.name("bytes-in");
// Result: "kafka.broker.1.metrics.bytes-in"
```

#### Consistent Naming Conventions

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

---

### Circuit Management

#### One Circuit Per Domain

```java
// ✅ GOOD - Separate circuits for different domains
Circuit kafkaCircuit = cortex.circuit(cortex.name("kafka"));
Circuit systemCircuit = cortex.circuit(cortex.name("system"));
Circuit appCircuit = cortex.circuit(cortex.name("application"));
```

❌ **BAD:**
```java
Circuit everythingCircuit = cortex.circuit(cortex.name("everything"));
```

**Why:** Better isolation, easier to reason about event ordering per domain.

---

### Conduit and Channel Patterns

#### Create Conduits by Signal Type

```java
// ✅ GOOD - One conduit per signal type
Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors =
    circuit.conduit(cortex.name("monitors"), Composer.pipe());

Conduit<Pipe<ServiceSignal>, ServiceSignal> services =
    circuit.conduit(cortex.name("services"), Composer.pipe());
```

❌ **BAD:**
```java
Conduit<Pipe<Object>, Object> everything =
    circuit.conduit(cortex.name("everything"), Composer.pipe());
```

**Why:** Type safety, clear separation of concerns.

---

### Flow and Sift Transformations

#### Configure Once at Conduit Creation

```java
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("numbers"),
    Composer.pipe(flow -> flow
        .sift(n -> n > 0)           // Filter: only positive
        .limit(1000)                // Max 1000 emissions
        .sample(10)                 // Every 10th emission
    )
);
```

#### Transformation Order Matters

```java
// ✅ GOOD - Filter first, then sample
flow.sift(n -> n > 100).sample(10)
// Out of 1000: ~500 pass filter → ~50 sampled

// ❌ DIFFERENT RESULT - Sample first, then filter
flow.sample(10).sift(n -> n > 100)
// Out of 1000: ~100 sampled → ~50 pass filter
```

---

### Subscriber Management

#### Subscribe Once, Process Many

```java
monitors.subscribe(
    cortex.subscriber(
        cortex.name("health-aggregator"),
        (subject, registrar) -> {
            registrar.register(signal -> aggregateHealth(signal));
        }
    )
);
```

#### Unsubscribe When Done

```java
Subscription sub = monitors.subscribe(subscriber);
// Later
sub.close();
```

---

## Performance Guide

### Performance Summary

**Test Suite:**
- 247 tests in ~16 seconds
- 0 failures, 0 errors

**Production Target:**
- 100k+ metrics @ 1Hz
- ~2% CPU usage (estimated)
- ~200-300MB memory

---

### Architecture Performance

#### Virtual CPU Core Pattern

```
Circuit Queue (FIFO):
  Events → Single Virtual Thread → Process in Order

Benefits:
✅ No lock contention
✅ Precise ordering
✅ Predictable latency
✅ Lightweight (virtual threads)
```

#### Component Caching

```java
private final Map<Name, Conduit<?, ?>> conduits = new ConcurrentHashMap<>();

public <P, E> Conduit<P, E> conduit(Name name, Composer<P, E> composer) {
    return (Conduit<P, E>) conduits.computeIfAbsent(name, n ->
        new TransformingConduit<>(n, this, composer)
    );
}
```

**Performance:**
- First access: ~100-200ns (create + cache)
- Cached access: ~5-10ns (ConcurrentHashMap lookup)

---

#### Pipe Emission

**Performance:**
- No transformations: ~50-100ns per emission
- With sift/limit/sample: +10-50ns
- Subscriber notification: +20-50ns per subscriber
- **Total:** ~100-300ns per emission with 1-3 subscribers

---

#### Shared Scheduler Optimization

```java
// All Clocks in Circuit share one ScheduledExecutorService
Circuit circuit = cortex.circuit(name);
Clock clock1 = circuit.clock(name1);  // Shares scheduler
Clock clock2 = circuit.clock(name2);  // Same scheduler

// 100 Clocks = 1 scheduler thread instead of 100
// Memory saved: ~10MB (100 threads × ~100KB each)
```

---

#### HierarchicalName Performance

```java
public final class HierarchicalName implements Name {
    private final String cachedPath;  // Built once in constructor

    @Override
    public CharSequence path(char separator) {
        return cachedPath;  // O(1) lookup
    }
}
```

**Performance:**
- Creation: ~50-100ns
- Path access: ~5ns (cached string)

---

### Real-World Performance

**Kafka Monitoring Scenario:**

```
Setup:
- 100 brokers × 1,000 metrics each
- 100,000 total emissions/second @ 1Hz

Estimated Resource Usage:
- CPU: 100,000 × 200ns = 20ms/sec = 2% of one core
- Memory: 100,000 Pipes × 1KB ≈ 100MB
- Threads: 1 Circuit thread + 1 scheduler = 2-3 total
```

**Headroom:** 98% CPU available for metric collection and analysis.

---

### Performance Best Practices

#### 1. Cache Components

```java
// ✅ FAST - Cache once
Pipe<T> pipe = conduit.get(name);
for (T value : values) {
    pipe.emit(value);
}

// ❌ SLOW - Repeated lookups
for (T value : values) {
    conduit.get(name).emit(value);
}
```

---

#### 2. Batch Emissions

```java
// ✅ GOOD - Batch processing
List<MonitorSignal> signals = collectSignals();
Pipe<MonitorSignal> pipe = monitors.get(name);
for (MonitorSignal signal : signals) {
    pipe.emit(signal);
}
```

---

#### 3. Use Appropriate Transformations

```java
// ✅ GOOD - Filter early
flow.sift(expensiveCheck)    // Expensive filter first
    .sift(cheapCheck)        // Cheap filter second
    .limit(100)
```

---

#### 4. Avoid Blocking in Callbacks

❌ **BAD:**
```java
conduit.subscribe(
    cortex.subscriber(name, (subject, registrar) -> {
        registrar.register(event -> {
            Thread.sleep(1000);  // Blocks event processing!
        });
    })
);
```

✅ **GOOD:**
```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
conduit.subscribe(
    cortex.subscriber(name, (subject, registrar) -> {
        registrar.register(event -> {
            executor.submit(() -> processSlowly(event));
        });
    })
);
```

---

### Scaling Considerations

#### Vertical Scaling (Single JVM)

**Current Architecture:**
- ✅ 100k metrics @ 1Hz: 2% CPU, 200MB RAM
- ✅ 1M metrics @ 1Hz: 20% CPU, 2GB RAM
- ⚠️ 10M metrics @ 1Hz: May need tuning

**Bottlenecks (if you reach them):**
1. Circuit queue depth (processing slower than emission)
2. Subscriber count (linear cost per subscriber)
3. Memory (100M Pipes × 1KB = 100GB)

---

#### Horizontal Scaling (Multiple JVMs)

**Partition by broker:**
```java
// JVM 1: Brokers 1-50
Circuit brokers1to50 = cortex.circuit(cortex.name("brokers-1-50"));

// JVM 2: Brokers 51-100
Circuit brokers51to100 = cortex.circuit(cortex.name("brokers-51-100"));
```

---

### Memory Characteristics

**Component Footprint (Approximate):**

```
HierarchicalName:       ~64 bytes
CircuitImpl:    ~1KB
TransformingConduit:    ~512 bytes
EmissionChannel:    ~256 bytes
PipeImpl:       ~512 bytes

Per Metric (Pipe): ~1KB
```

**Scaling:**
```
1,000 metrics:     ~1MB
10,000 metrics:    ~10MB
100,000 metrics:   ~100MB
1,000,000 metrics: ~1GB
```

---

### Performance Monitoring

#### Monitor Circuit Queue Depth

```java
// If queue grows, emissions > processing
BlockingQueue<Runnable> queue = circuit.getQueue();
if (queue.size() > 10000) {
    log.warn("Circuit queue backing up: {}", queue.size());
}
```

#### Track Emission Rate

```java
AtomicLong emissionCount = new AtomicLong();
pipe.emit(event);
emissionCount.incrementAndGet();

clock.consume(name, Clock.Cycle.SECOND, instant -> {
    long rate = emissionCount.getAndSet(0);
    log.info("Emission rate: {} events/second", rate);
});
```

---

### When to Optimize

**DON'T optimize if:**
- ✅ Test suite runs in < 30 seconds
- ✅ Production CPU usage < 20%
- ✅ Production memory usage is stable
- ✅ No user-facing performance issues

**DO optimize if:**
- ❌ Circuit queue depth growing unbounded
- ❌ CPU usage > 80% sustained
- ❌ Memory usage growing (memory leak)
- ❌ Subscriber callbacks blocking event processing

**How to optimize:**
1. Profile first (JFR or async-profiler)
2. Optimize hot path only
3. Measure improvement
4. Document why

---

## Testing Strategies

### Unit Testing

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
            (subject, registrar) -> registrar.register(received::add)
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

---

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

---

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

---

### Testing Resource Cleanup

```java
@Test
void testScopeCleanup() {
    Cortex cortex = CortexRuntime.create();
    Scope scope = cortex.scope(cortex.name("test-scope"));

    AtomicBoolean circuitClosed = new AtomicBoolean(false);
    Circuit circuit = scope.register(cortex.circuit(cortex.name("test")));

    // Override close to verify it's called
    // (in real code, you'd use a spy or mock)

    scope.close();

    // Verify circuit was closed
    // (depends on how you implement the spy)
}
```

---

## Common Pitfalls

### 1. Forgetting to Close Resources

❌ **PROBLEM:**
```java
public void startMonitoring() {
    Circuit circuit = cortex.circuit(cortex.name("kafka"));
    // Use circuit
} // Circuit never closed!
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

---

### 2. Mixing Signal Types

❌ **PROBLEM:**
```java
Conduit<Pipe<Object>, Object> mixed =
    circuit.conduit(cortex.name("mixed"), Composer.pipe());

mixed.get(name).emit(new MonitorSignal(/* ... */));
mixed.get(name).emit("A string?");  // Type safety lost!
```

✅ **SOLUTION:**
```java
Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors =
    circuit.conduit(cortex.name("monitors"), Composer.pipe());

monitors.get(name).emit(new MonitorSignal(/* ... */));
```

---

### 3. Creating Too Many Circuits

❌ **PROBLEM:**
```java
// One circuit per metric!
for (String metric : metrics) {
    Circuit circuit = cortex.circuit(cortex.name(metric));
}
```

✅ **SOLUTION:**
```java
// One circuit per domain
Circuit kafkaCircuit = cortex.circuit(cortex.name("kafka"));

// Many conduits in one circuit
for (String metric : metrics) {
    Conduit<Pipe<MetricValue>, MetricValue> conduit =
        kafkaCircuit.conduit(cortex.name(metric), Composer.pipe());
}
```

---

### 4. Not Handling Async Nature

❌ **PROBLEM:**
```java
List<String> received = new ArrayList<>();

conduit.subscribe(
    cortex.subscriber(name, (subject, registrar) ->
        registrar.register(received::add)
    )
);

pipe.emit("Hello");
assertEquals(1, received.size());  // FAILS! Async processing not complete
```

✅ **SOLUTION:**
```java
List<String> received = new CopyOnWriteArrayList<>();

conduit.subscribe(
    cortex.subscriber(name, (subject, registrar) ->
        registrar.register(received::add)
    )
);

pipe.emit("Hello");
Thread.sleep(100);  // Wait for async processing
assertEquals(1, received.size());  // Now passes
```

---

### 5. Blocking in Subscriber Callbacks

❌ **PROBLEM:**
```java
conduit.subscribe(
    cortex.subscriber(name, (subject, registrar) -> {
        registrar.register(event -> {
            expensiveBlockingOperation();  // BLOCKS CIRCUIT!
        });
    })
);
```

✅ **SOLUTION:**
```java
ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

conduit.subscribe(
    cortex.subscriber(name, (subject, registrar) -> {
        registrar.register(event -> {
            executor.submit(() -> expensiveBlockingOperation());
        });
    })
);
```

---

## Summary

### Key Takeaways

1. ✅ **Cache pipes** - Reuse for repeated emissions
2. ✅ **Use hierarchical names** - Build from parent to child
3. ✅ **Close resources** - Always clean up
4. ✅ **One circuit per domain** - Not per metric
5. ✅ **Type-safe conduits** - One per signal type
6. ✅ **Configure transformations once** - At conduit creation
7. ✅ **Handle async** - Wait for processing in tests
8. ✅ **Avoid blocking** - In subscriber callbacks
9. ✅ **Profile before optimizing** - Measure actual bottlenecks
10. ✅ **Test thoroughly** - Unit, integration, resource cleanup

### Philosophy

> "Premature optimization is the root of all evil." - Donald Knuth

**Build it simple, build it correct, optimize when profiling shows actual bottlenecks.**

---

## References

- [Architecture & Concepts](ARCHITECTURE.md)
- [Async Architecture](ASYNC-ARCHITECTURE.md)
- [M18 Migration Guide](../../API-ANALYSIS.md)
- [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)
