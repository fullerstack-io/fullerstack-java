# Substrates Implementation Guide

**Last Updated:** October 16, 2025
**Version:** 1.0.0-SNAPSHOT

This guide provides recommended patterns, best practices, and common pitfalls when building with the Substrates framework.

---

## Table of Contents

1. [Quick Start Patterns](#quick-start-patterns)
2. [Factory Injection Patterns](#factory-injection-patterns)
3. [Entity Creation Patterns](#entity-creation-patterns)
4. [Caching and Lookups](#caching-and-lookups)
5. [Hierarchical Names](#hierarchical-names)
6. [Resource Management](#resource-management)
7. [Testing Patterns](#testing-patterns)
8. [Common Pitfalls](#common-pitfalls)

---

## Quick Start Patterns

### Basic Setup (Recommended)

```java
// ✅ Use defaults - optimized for production
Cortex cortex = new CortexRuntime();

// Create circuit
Circuit circuit = cortex.circuit(cortex.name("my-app"));

// Create conduit with Pipe composer
Conduit<Pipe<String>, String> conduit = circuit.conduit(
    cortex.name("events"),
    Composer.pipe()
);

// Subscribe to observe emissions
conduit.source().subscribe(
    cortex.subscriber(
        cortex.name("logger"),
        (subject, registrar) ->
            registrar.register(msg -> System.out.println("Event: " + msg))
    )
);

// Get pipe and emit
Pipe<String> pipe = conduit.get(cortex.name("producer1"));
pipe.emit("Hello, Substrates!");
```

**Why this works:**
- Default factories use InternedName + LazyTrieRegistry (optimized)
- Circuit auto-caches by name
- Conduit auto-caches by (name, composer)
- Pipe auto-caches per conduit

---

## Factory Injection Patterns

### Use Defaults (Recommended)

```java
// ✅ RECOMMENDED - Uses optimized defaults
Cortex cortex = new CortexRuntime();
```

**Defaults:**
- **NameFactory:** InternedNameFactory (identity map fast path)
- **QueueFactory:** LinkedBlockingQueueFactory (unbounded FIFO)
- **RegistryFactory:** LazyTrieRegistryFactory (best performance)

---

### Custom Name Factory

```java
// Use custom NameFactory if needed
NameFactory nameFactory = LRUCachedNameFactory.getInstance(1000);

Cortex cortex = new CortexRuntime(nameFactory);
```

**When to customize:**
- ⚠️ Special memory requirements
- ⚠️ Different caching strategy
- ⚠️ Testing with mock factories

**Warning:** Custom factories may lose identity map optimization (5× slower lookups).

---

### Full Custom Factories

```java
// Full control over all factories
NameFactory nameFactory = InternedNameFactory.getInstance();
QueueFactory queueFactory = LinkedBlockingQueueFactory.getInstance();
RegistryFactory registryFactory = FlatMapRegistryFactory.getInstance();

Cortex cortex = new CortexRuntime(nameFactory, queueFactory, registryFactory);
```

**Use FlatMapRegistry when:**
- ✅ No hierarchical query needs
- ✅ Maximum write throughput required
- ✅ Simplest implementation preferred

**Stick with LazyTrieRegistry for:**
- ✅ **Production use (recommended)**
- ✅ Hierarchical subtree queries
- ✅ Best overall performance

---

## Entity Creation Patterns

### Circuits

```java
// ✅ GOOD - Cached by name
Circuit circuit1 = cortex.circuit(cortex.name("my-circuit"));
Circuit circuit2 = cortex.circuit(cortex.name("my-circuit"));
assert circuit1 == circuit2;  // Same instance!

// ✅ GOOD - Default name
Circuit circuit = cortex.circuit();  // Uses name "circuit"

// ⚠️ SUBOPTIMAL - Repeated name lookups (still works, returns same circuit)
for (int i = 0; i < 1000; i++) {
    Circuit c = cortex.circuit(cortex.name("my-circuit"));  // Small overhead from WeakHashMap lookup
}

// ✅ BETTER - Cache name to avoid lookup overhead
Name circuitName = cortex.name("my-circuit");
for (int i = 0; i < 1000; i++) {
    Circuit c = cortex.circuit(circuitName);  // Returns cached circuit, no name lookup
}
```

**Key Insight:** CortexRuntime caches circuits by name using `computeIfAbsent`.

---

### Conduits

```java
// ✅ GOOD - Cached by (name, composer class)
Conduit<Pipe<String>, String> pipeConduit = circuit.conduit(
    cortex.name("events"),
    Composer.pipe()  // Composer that extracts Pipe
);

// Same name, SAME composer → returns SAME conduit
Conduit<Pipe<String>, String> sameConduit = circuit.conduit(
    cortex.name("events"),      // Same name
    Composer.pipe()             // Same composer class
);
assert pipeConduit == sameConduit;  // Same instance (cached)!

// Same name, DIFFERENT composer → creates DIFFERENT conduit (different instrument)
Conduit<Channel<String>, String> channelConduit = circuit.conduit(
    cortex.name("events"),      // Same name
    Composer.channel()          // Different composer class
);
assert pipeConduit != channelConduit;  // Different instruments!

// ✅ GOOD - With transformations
Conduit<Pipe<Integer>, Integer> filtered = circuit.conduit(
    cortex.name("numbers"),
    Composer.pipe(segment -> segment
        .guard(n -> n > 0)    // Filter positive
        .limit(100)            // Max 100 emissions
    )
);
```

**Key Insight:** Conduits cached by `(Name, composer.getClass())` - same name can have multiple instruments (different composers).

---

### Channels and Pipes

```java
// ✅ GOOD - Get pipe (auto-cached)
Pipe<String> pipe = conduit.get(cortex.name("producer1"));

// Same name returns same pipe
Pipe<String> pipe2 = conduit.get(cortex.name("producer1"));
assert pipe == pipe2;  // Same instance!

// ✅ BEST - Cache pipe for hot-path
Pipe<String> cachedPipe = conduit.get(name);
for (int i = 0; i < 1000000; i++) {
    cachedPipe.emit("event-" + i);  // 3.3ns per emit
}

// ⚠️ ACCEPTABLE - Lookup each time (still cached internally)
for (int i = 0; i < 1000000; i++) {
    conduit.get(name).emit("event-" + i);  // ~7ns per emit (4ns lookup + 3.3ns emit)
}

// ❌ AVOID - Full path from circuit
for (int i = 0; i < 1000000; i++) {
    circuit.conduit(name, composer).get(channelName).emit("event-" + i);  // 30ns per emit!
}
```

**Performance Impact:**
- Cached pipe: 3.3ns (best)
- With conduit.get(): ~7ns (2× slower, but still fast)
- Full circuit path: 30ns (9× slower!)

---

## Caching and Lookups

### Pattern: Cache Everything

```java
// ✅ EXCELLENT - Cache at initialization
public class MetricEmitter {
    private final Pipe<MetricValue> pipe;

    public MetricEmitter(Conduit<Pipe<MetricValue>, MetricValue> conduit, Name name) {
        this.pipe = conduit.get(name);  // Cache during construction
    }

    public void emit(MetricValue value) {
        pipe.emit(value);  // Fast path - 3.3ns
    }
}

// Usage
MetricEmitter emitter = new MetricEmitter(conduit, metricName);
emitter.emit(value);  // Always fast
```

---

### Pattern: Lazy Initialization with ComputeIfAbsent

```java
// ✅ GOOD - Thread-safe lazy init
public class PipeCache {
    private final Conduit<Pipe<String>, String> conduit;
    private final Map<String, Pipe<String>> pipes = new ConcurrentHashMap<>();

    public Pipe<String> getPipe(String key) {
        return pipes.computeIfAbsent(key, k ->
            conduit.get(cortex.name(k))
        );
    }
}
```

**Why this works:**
- `computeIfAbsent` is thread-safe
- Conduit caching happens automatically
- Double-caching is fine (minimal overhead)

---

### Pattern: Bulk Initialization

```java
// ✅ GOOD - Pre-create all pipes at startup
public class MetricSystem {
    private final Map<String, Pipe<MetricValue>> pipes = new HashMap<>();

    public void initialize(Conduit<Pipe<MetricValue>, MetricValue> conduit) {
        // Pre-create all known metric pipes
        for (String metricName : KNOWN_METRICS) {
            pipes.put(metricName, conduit.get(cortex.name(metricName)));
        }
    }

    public void emit(String metric, MetricValue value) {
        Pipe<MetricValue> pipe = pipes.get(metric);
        if (pipe != null) {
            pipe.emit(value);  // Fast path
        }
    }
}
```

**When to use:**
- ✅ Known set of metrics/channels at startup
- ✅ Want guaranteed fast emission
- ✅ Can afford initialization cost

---

## Hierarchical Names

### Pattern: Build Hierarchically

```java
// ✅ EXCELLENT - Leverage InternedName parent chain
Name kafkaName = cortex.name("kafka");
Name brokerName = kafkaName.name("broker").name("1");
Name jvmName = brokerName.name("jvm");
Name heapName = jvmName.name("heap");
Name usedName = heapName.name("used");

// Result: "kafka.broker.1.jvm.heap.used"
```

**Benefits:**
- Parent chain cached in InternedName
- Faster than recreating full path
- Natural hierarchical structure

---

### Pattern: Metric Path Builder

```java
// ✅ GOOD - Reusable path builder
public class KafkaMetricPath {
    private final Cortex cortex;
    private final Name kafkaRoot;

    public KafkaMetricPath(Cortex cortex) {
        this.cortex = cortex;
        this.kafkaRoot = cortex.name("kafka");
    }

    public Name broker(int id) {
        return kafkaRoot.name("broker").name(String.valueOf(id));
    }

    public Name partition(int brokerId, int partitionId) {
        return broker(brokerId).name("partition").name(String.valueOf(partitionId));
    }

    public Name metric(int brokerId, String metricName) {
        return broker(brokerId).name(metricName);
    }
}

// Usage
KafkaMetricPath paths = new KafkaMetricPath(cortex);
Name heapUsed = paths.metric(1, "jvm.heap.used");
```

---

### Pattern: Hierarchical Queries

```java
// ✅ EXCELLENT - Query all metrics under a name prefix
LazyTrieRegistry<Pipe<MetricValue>> registry =
    (LazyTrieRegistry<Pipe<MetricValue>>) conduit.getPercepts();

// Query all metrics for broker 1 (returns all descendants)
Name broker1 = cortex.name("kafka.broker.1");
Map<Name, Pipe<MetricValue>> broker1Metrics = registry.getSubtree(broker1);

// Iterate all metrics for broker 1
for (Map.Entry<Name, Pipe<MetricValue>> entry : broker1Metrics.entrySet()) {
    System.out.println(entry.getKey() + " = " + entry.getValue());
}
```

**Performance:** ~200-300ns per query, faster with fewer results
- Useful for dashboard queries ("show all metrics for broker-1")
- Results are live views - no copying overhead

---

## Resource Management

### Pattern: Try-With-Resources

```java
// ✅ GOOD - Auto-cleanup with try-with-resources
try (Circuit circuit = cortex.circuit(cortex.name("my-circuit"))) {
    Conduit<Pipe<String>, String> conduit = circuit.conduit(
        cortex.name("events"),
        Composer.pipe()
    );

    // Use circuit and conduit
    Pipe<String> pipe = conduit.get(cortex.name("producer"));
    pipe.emit("Hello");

}  // Circuit auto-closed, cleans up queue and resources
```

---

### Pattern: Scope-Based Lifecycle

```java
// ✅ EXCELLENT - Scope manages resource lifecycle
Scope scope = cortex.scope(cortex.name("transaction"));

// Register resources
Circuit circuit = scope.register(cortex.circuit(cortex.name("tx-circuit")));
Conduit<Pipe<String>, String> conduit = scope.register(
    circuit.conduit(cortex.name("events"), Composer.pipe())
);

try {
    // Use resources
    Pipe<String> pipe = conduit.get(cortex.name("producer"));
    pipe.emit("Transaction event");

} finally {
    scope.close();  // Cleans up all registered resources in LIFO order
}
```

---

### Pattern: Closure for Automatic Cleanup

```java
// ✅ EXCELLENT - Closure provides auto-cleanup
Scope scope = cortex.scope(cortex.name("session"));

scope.closure(cortex.circuit()).consume(circuit -> {
    // Circuit available inside closure
    Conduit<Pipe<String>, String> conduit = circuit.conduit(
        cortex.name("events"),
        Composer.pipe()
    );

    Pipe<String> pipe = conduit.get(cortex.name("producer"));
    pipe.emit("Session event");

});  // Circuit auto-closed when closure exits
```

---

## Testing Patterns

### Pattern: Test with Real Cortex

```java
@Test
public void testEmission() {
    // ✅ GOOD - Use real Cortex for integration tests
    Cortex cortex = new CortexRuntime();
    Circuit circuit = cortex.circuit(cortex.name("test-circuit"));

    Conduit<Pipe<String>, String> conduit = circuit.conduit(
        cortex.name("events"),
        Composer.pipe()
    );

    // Capture emissions
    List<String> captured = new ArrayList<>();
    conduit.source().subscribe(
        cortex.subscriber(
            cortex.name("test-subscriber"),
            (subject, registrar) ->
                registrar.register(captured::add)
        )
    );

    // Emit and verify
    Pipe<String> pipe = conduit.get(cortex.name("producer"));
    pipe.emit("test-value");

    // Give queue time to process
    Thread.sleep(10);

    assertEquals(List.of("test-value"), captured);

    circuit.close();
}
```

---

### Pattern: Test with Mock Factories

```java
@Test
public void testWithMocks() {
    // ✅ GOOD - Inject test factories
    NameFactory nameFactory = mock(NameFactory.class);
    QueueFactory queueFactory = mock(QueueFactory.class);
    RegistryFactory registryFactory = LazyTrieRegistryFactory.getInstance();

    Cortex cortex = new CortexRuntime(nameFactory, queueFactory, registryFactory);

    // Test with controlled behavior
    when(nameFactory.create("test")).thenReturn(testName);

    // ... test logic
}
```

---

### Pattern: Synchronous Testing

```java
@Test
public void testSynchronously() {
    // ✅ GOOD - Use SynchronousQueue for deterministic testing
    QueueFactory syncQueueFactory = () -> new SynchronousQueueWrapper();

    Cortex cortex = new CortexRuntime(
        InternedNameFactory.getInstance(),
        syncQueueFactory,
        LazyTrieRegistryFactory.getInstance()
    );

    // Now emissions process immediately (no async queue)
    Circuit circuit = cortex.circuit(cortex.name("test"));
    // ... test without Thread.sleep()
}
```

---

## Common Pitfalls

### Pitfall 1: Not Caching Pipes for Maximum Performance

```java
// ⚠️ ACCEPTABLE - Lookup each time (still fast with identity map)
for (int i = 0; i < 1000000; i++) {
    conduit.get(name).emit(value);  // ~7ns per emit (4ns lookup + 3.3ns emit)
}

// ✅ BEST - Cache pipe for absolute maximum performance
Pipe<T> pipe = conduit.get(name);
for (int i = 0; i < 1000000; i++) {
    pipe.emit(value);  // 3.3ns per emit
}
```

**Impact:** 2× performance difference (7ns vs 3.3ns)
- For ultra-high-frequency emissions, cache the Pipe
- For moderate frequency, `conduit.get(name)` is fine

---

### Pitfall 2: Repeated String Operations for Name Creation

```java
// ⚠️ INEFFICIENT - String concatenation every iteration
for (int i = 0; i < 1000; i++) {
    String dynamicPath = "circuit-" + i;  // ← Allocates new String each time
    Circuit c = cortex.circuit(cortex.name(dynamicPath));  // ← WeakHashMap lookup overhead
}

// ✅ BETTER - Pre-create names if set is known
Name[] circuitNames = new Name[1000];
for (int i = 0; i < 1000; i++) {
    circuitNames[i] = cortex.name("circuit-" + i);  // String concat + lookup once
}

// Later: just use the Name references
for (int i = 0; i < 1000; i++) {
    Circuit c = cortex.circuit(circuitNames[i]);  // No string ops, just use Name
}
```

**Impact:** Avoids repeated string concatenation (~20-50ns) and WeakHashMap lookups
- Names ARE cached by InternedName, but you still pay for string creation + lookup
- Pre-creating eliminates this overhead in hot loops

---

### Pitfall 3: Not Closing Resources

```java
// ❌ BAD - Resource leak
public void processData() {
    Circuit circuit = cortex.circuit(cortex.name("processor"));
    // ... use circuit
    // Oops - forgot to close!
}

// ✅ GOOD - Always close
public void processData() {
    try (Circuit circuit = cortex.circuit(cortex.name("processor"))) {
        // ... use circuit
    }  // Auto-closed
}
```

**Impact:** Virtual thread leaks, queue processing continues

---

### Pitfall 4: Blocking Queue Operations

```java
// ❌ BAD - Don't block in subscriber callbacks
conduit.source().subscribe(
    cortex.subscriber(
        cortex.name("blocker"),
        (subject, registrar) ->
            registrar.register(value -> {
                Thread.sleep(1000);  // DON'T DO THIS!
            })
    )
);

// ✅ GOOD - Async processing
ExecutorService executor = Executors.newCachedThreadPool();
conduit.source().subscribe(
    cortex.subscriber(
        cortex.name("async"),
        (subject, registrar) ->
            registrar.register(value ->
                executor.submit(() -> processAsync(value))
            )
    )
);
```

**Impact:** Blocks circuit queue, stalls all emissions

---

### Pitfall 5: Wrong Factory Choices

```java
// ❌ BAD - StringSplitTrieRegistry is 16× slower on writes
Cortex cortex = new CortexRuntime(
    InternedNameFactory.getInstance(),
    LinkedBlockingQueueFactory.getInstance(),
    StringSplitTrieRegistryFactory.getInstance()  // DON'T USE
);

// ✅ GOOD - Use defaults (already optimized)
Cortex cortex = new CortexRuntime();
```

**Impact:** 16× slower initialization, 2× slower queries

---

### Pitfall 6: Forgetting Type Parameters

```java
// ❌ BAD - Raw types lose type safety
Conduit conduit = circuit.conduit(name, Composer.pipe());
Pipe pipe = conduit.get(name);
pipe.emit("anything");  // No type checking!

// ✅ GOOD - Use type parameters
Conduit<Pipe<String>, String> conduit = circuit.conduit(
    name,
    Composer.pipe()
);
Pipe<String> pipe = conduit.get(name);
pipe.emit("type-safe");  // Compiler checks type
```

---

### Pitfall 7: Not Understanding Caching Keys

```java
// ❌ CONFUSING - Same name, different composers
Conduit<Pipe<String>, String> conduit1 = circuit.conduit(
    cortex.name("events"),
    Composer.pipe()
);

Conduit<Channel<String>, String> conduit2 = circuit.conduit(
    cortex.name("events"),  // Same name
    Composer.channel()      // Different composer
);

assert conduit1 != conduit2;  // Different instances!

// ✅ CLEAR - Understand caching key is (name, composer class)
// Same name + same composer = same conduit
// Same name + different composer = different conduit
```

---

## Summary

### Golden Rules

1. ✅ **Use defaults** - InternedName + LazyTrieRegistry optimized for production
2. ✅ **Cache aggressively** - Store Pipe instances, reuse Names
3. ✅ **Close resources** - Use try-with-resources or Scopes
4. ✅ **Don't block** - Keep subscriber callbacks fast
5. ✅ **Build hierarchically** - Leverage InternedName parent chains
6. ✅ **Test realistically** - Use real Cortex in integration tests

### Performance Checklist

- [ ] Caching Pipe instances? (30× speedup)
- [ ] Caching Name instances? (1000× speedup)
- [ ] Using InternedName? (5× lookup speedup)
- [ ] Using LazyTrieRegistry? (50% hot-path speedup)
- [ ] Not blocking in callbacks?
- [ ] Closing resources properly?

### Next Steps

- **[Performance Guide](PERFORMANCE.md)** - Detailed performance analysis
- **[Architecture Guide](ARCHITECTURE.md)** - System design and data flow
- **[Examples](examples/README.md)** - Hands-on code examples

---

**Questions or suggestions?** Open an issue or submit a PR!
