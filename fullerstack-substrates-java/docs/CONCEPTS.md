# Core Concepts

This document explains the fundamental concepts in Substrates and how they work together.

## Table of Contents

- [What is Substrates?](#what-is-substrates)
- [Core Abstractions](#core-abstractions)
- [Producer-Consumer Model](#producer-consumer-model)
- [Transformation Pipelines](#transformation-pipelines)
- [Resource Management](#resource-management)
- [Observability Model](#observability-model)

## What is Substrates?

Substrates is a framework for building **event-driven observability systems**. It provides:

1. **Type-safe event routing** - from producers to consumers
2. **Transformation pipelines** - filter, map, reduce, sample emissions
3. **Dynamic subscription** - observers can subscribe/unsubscribe at runtime
4. **Resource lifecycle** - automatic cleanup and hierarchical scoping
5. **Precise ordering** - guaranteed event ordering per circuit

### Semiotic Observability

Substrates supports William Louth's vision of **semiotic observability**:

```
Metrics (traditional)
  ↓
Signs (observations)
  ↓
Symptoms (patterns)
  ↓
Syndromes (correlated symptoms)
  ↓
Situations (system states)
  ↓
Steering (actions)
```

Substrates provides the **runtime substrate** for this observability stack.

## Core Abstractions

### Subject

**What:** Hierarchical reference system for identifying entities.

**Components:**
- **Id** - Unique instance identifier (UUID-based)
- **Name** - Hierarchical semantic identity (like a namespace)
- **State** - Immutable key-value data
- **Type** - Category (CHANNEL, CIRCUIT, CLOCK, etc.)

**Example:**
```java
Subject subject = channel.subject();
System.out.println(subject.name());  // e.g., "circuit.conduit.channel1"
System.out.println(subject.type());  // CHANNEL
System.out.println(subject.id());    // Unique UUID
```

### Id and @Identity

Substrates defines **two related identity concepts**:

#### 1. `Id` Interface - Unique Instance Identity

**What:** Marker interface for unique identifier **values** (like UUIDs).

**Purpose:** Distinguishes between different instances of the same type.

**Example:**
```java
Id id1 = IdImpl.generate();  // UUID: a1b2c3d4-...
Id id2 = IdImpl.generate();  // UUID: e5f6g7h8-...

assert !id1.equals(id2);  // Different IDs
```

**Usage in Subject:**
```java
Subject subject1 = circuit1.subject();
Subject subject2 = circuit2.subject();

// Even if both circuits have name "default", they have unique IDs
assert !subject1.id().equals(subject2.id());
```

#### 2. `@Identity` Annotation - Reference Equality Semantics

**What:** Type-level annotation indicating instances should be compared by **reference** (==), not value.

**Applied to:** `Id`, `Name`, `Subject` interfaces

**Purpose:** Signals these are singletons/flyweights - don't copy them, compare by reference.

**Example:**
```java
@Identity
@Provided
interface Id {
}

@Identity
@Provided
interface Name extends Extent<Name> {
}
```

**Meaning:** When you have a `Name` or `Id`, comparing `name1 == name2` is meaningful because they're meant to be unique references.

**Key Insight:** Every component has **two identities**:
1. **`Id`** - Unique instance identity (UUID)
2. **`Name`** - Semantic identity for pooling/caching (see below)

**Hierarchy:**
```java
Name name = cortex.name("app.service.endpoint");
name.enclosure().ifPresent(parent -> {
    System.out.println(parent);  // "app.service"
});
```

### Name

**What:** Hierarchical semantic identity used for both naming and caching.

**Features:**
- Dot-separated parts: `"app.service.endpoint"`
- Implements `Extent<Name>` for hierarchical navigation
- Immutable and comparable
- **Used as cache key** in Circuit/Cortex component pools

**Dual Purpose:**
1. **Semantic Identity** - Human-readable hierarchical names
2. **Cache Key** - Enables singleton pattern for named components

**Usage:**
```java
Name root = cortex.name("app");
Name service = root.name("service");
Name endpoint = service.name("endpoint");

System.out.println(endpoint.path());  // "app.service.endpoint"
System.out.println(endpoint.depth()); // 3
```

**Name as Cache Key:**
```java
Cortex cortex = new CortexRuntime();

// First call creates circuit with name "kafka-cluster"
Circuit c1 = cortex.circuit(cortex.name("kafka-cluster"));

// Second call returns THE SAME circuit (cached by name)
Circuit c2 = cortex.circuit(cortex.name("kafka-cluster"));

assert c1 == c2;  // ✅ Singleton pattern via Name caching
```

**Default Names Create Singletons:**
```java
// No-arg methods use static "default" name for singleton behavior
Circuit c1 = cortex.circuit();  // Uses name "default"
Circuit c2 = cortex.circuit();  // Returns same circuit

Clock clk1 = circuit.clock();   // Uses name "default"
Clock clk2 = circuit.clock();   // Returns same clock

assert c1 == c2;   // ✅ Same circuit
assert clk1 == clk2;  // ✅ Same clock
```

**Correct vs Incorrect Usage Pattern:**

⚠️ **CRITICAL:** Always use Cortex/Circuit factory methods, never direct construction.

```java
// ✅ CORRECT - Uses cache via Circuit.clock()
Clock clock1 = circuit.clock(cortex.name("timer"));
Clock clock2 = circuit.clock(cortex.name("timer"));
assert clock1 == clock2;  // Same instance (cached)

// ❌ WRONG - Bypasses cache via direct construction
Clock clock3 = new ClockImpl(cortex.name("timer"));
Clock clock4 = new ClockImpl(cortex.name("timer"));
assert clock3 != clock4;  // Different instances! (breaks singleton)
```

**Why constructors generate new Ids:**

Component constructors (ClockImpl, ConduitImpl, CircuitImpl) always generate a new Id because they're only called once per Name by the cache:

```java
// ClockImpl constructor - called ONLY by cache miss
public ClockImpl(Name name) {
    Id id = IdImpl.generate();  // New ID each time
    this.clockSubject = new SubjectImpl(id, name, ...);
}

// CircuitImpl.clock() - ensures constructor called once per Name
public Clock clock(Name name) {
    return clocks.computeIfAbsent(name, ClockImpl::new);
    //                             ^^^^ Only called if Name absent
}
```

**The computeIfAbsent() guarantee:**
- First call with Name → constructor called → Id generated → stored in cache
- Second call with same Name → cached instance returned → no new Id generated
- Direct construction → bypasses cache → breaks singleton pattern

See [Architecture Guide - Factory Method + Flyweight Pattern](ARCHITECTURE.md#1-name-based-component-caching) for detailed explanation.

### State

**What:** Immutable collection of named slots (key-value pairs).

**Immutability:** Each `state()` call returns a **NEW State** instance. The original State is never modified.

**Types Supported:**
- Primitives: `int`, `long`, `float`, `double`, `boolean`
- Objects: `String`, `Name`, `State` (nested)

**Basic Usage:**
```java
State s1 = cortex.state();
State s2 = s1.state(cortex.name("count"), 42);
State s3 = s2.state(cortex.name("name"), "sensor1");

// Each state() returns a NEW State
assert s1 != s2;  // ✅ Different objects
assert s2 != s3;  // ✅ Different objects

// Fluent API (method chaining)
State state = cortex.state()
    .state(cortex.name("count"), 42)
    .state(cortex.name("name"), "sensor1")
    .state(cortex.name("active"), true);
```

**Duplicate Handling:**

State uses a **List internally**, allowing duplicate names (override pattern):

```java
State config = cortex.state()
    .state(cortex.name("timeout"), 30)   // Default value
    .state(cortex.name("retries"), 3)
    .state(cortex.name("timeout"), 60);  // Override (both exist!)

// State has 3 slots (2 have name "timeout")
assert config.stream().count() == 3;

// compact() removes duplicates, keeping LAST occurrence
State compacted = config.compact();
assert compacted.stream().count() == 2;  // Only 1 "timeout" remains (value: 60)
```

**Why Allow Duplicates?**

This supports the **configuration override pattern**:
- Start with defaults
- Apply environment-specific overrides
- Call `compact()` when ready to finalize

**Retrieving Values:**

```java
State state = cortex.state()
    .state(cortex.name("timeout"), 30)
    .state(cortex.name("timeout"), 60);

// value() returns LAST occurrence
Integer timeout = state.value(cortex.slot(cortex.name("timeout"), 0));
assert timeout == 60;  // Last value wins

// values() returns ALL occurrences
var allTimeouts = state.values(cortex.slot(cortex.name("timeout"), 0)).toList();
assert allTimeouts.equals(List.of(30, 60));  // Both values
```

### Substrate

**What:** Base interface for all components with an associated Subject.

**Purpose:** Everything in Substrates has a Subject for identification.

```java
interface Substrate {
    Subject subject();
}
```

**Implementations:**
- Channel, Circuit, Clock, Conduit, Container
- Scope, Sink, Source, Subscription, Subscriber

## Producer-Consumer Model

### Terminology

| Term | Role | Description |
|------|------|-------------|
| **Channel** | Producer entry point | Where data enters the system |
| **Pipe** | Transport | Mechanism with `emit()` method |
| **Source** | Observable stream | Provides `subscribe()` for observation |
| **Subscriber** | Consumer connector | Links consumer Pipes to Source |
| **Conduit** | Router | Routes from Channels to Pipes |

### The Flow

```
Producer creates data
  ↓
Channel (entry point)
  ↓
Pipe.emit(value)
  ↓
Conduit's Queue
  ↓
Queue Processor
  ↓
Source (via Pipe interface)
  ↓
Subscribers notified
  ↓
Registrar registers consumer Pipes
  ↓
Consumer Pipes receive data
  ↓
Consumer processes data
```

### Example: Simple Producer-Consumer

```java
// Create circuit and conduit
Circuit circuit = cortex.circuit();
Conduit<Pipe<String>, String> conduit = circuit.conduit(
    cortex.name("messages"),
    Composer.pipe()
);

// CONSUMER SIDE: Subscribe to observe
conduit.source().subscribe(
    cortex.subscriber(
        cortex.name("logger"),
        (subject, registrar) -> {
            // Register consumer Pipe
            registrar.register(msg -> {
                System.out.println("Consumer received: " + msg);
            });
        }
    )
);

// PRODUCER SIDE: Get pipe and emit
Pipe<String> pipe = conduit.get(cortex.name("producer1"));
pipe.emit("Hello!");  // Consumer will receive this

circuit.close();
```

### Multiple Consumers

```java
Source<String> source = conduit.source();

// Consumer 1: Logs to console
source.subscribe(
    cortex.subscriber(
        cortex.name("console"),
        (subject, registrar) ->
            registrar.register(msg -> System.out.println(msg))
    )
);

// Consumer 2: Writes to file
source.subscribe(
    cortex.subscriber(
        cortex.name("file-writer"),
        (subject, registrar) ->
            registrar.register(msg -> writeToFile(msg))
    )
);

// One emission reaches both consumers
pipe.emit("Broadcast message");
```

### Conditional Subscription

```java
source.subscribe(
    cortex.subscriber(
        cortex.name("filter"),
        (subject, registrar) -> {
            // Only subscribe to channels named "important"
            if (subject.name().value().contains("important")) {
                registrar.register(msg -> processImportant(msg));
            }
        }
    )
);
```

## Transformation Pipelines

### Sequencer & Segment

**Sequencer** configures a **Segment** (transformation pipeline).

**Available Transformations:**

| Transformation | Purpose | Example |
|---------------|---------|---------|
| `guard(Predicate)` | Filter emissions | `guard(n -> n > 0)` |
| `limit(long)` | Max emission count | `limit(100)` |
| `reduce(init, BinaryOp)` | Stateful aggregation | `reduce(0, Integer::sum)` |
| `replace(UnaryOp)` | Transform values | `replace(n -> n * 2)` |
| `diff()` | Emit only changes | `diff()` |
| `sample(int)` | Every Nth emission | `sample(10)` |
| `sift(Comparator, Seq)` | Complex filtering | `sift(Integer::compareTo, s -> s.above(0))` |

### Example: Filter and Limit

```java
// Transformation configured at CONDUIT level
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("numbers"),            // ← Conduit name
    Composer.pipe(segment -> segment   // ← Transformation applies to ALL channels
        .guard(n -> n > 0)      // Only positive
        .guard(n -> n % 2 == 0) // Only even
        .limit(50)              // Max 50 emissions
    )
);

// Get a Channel (Pipe) from the Conduit
Pipe<Integer> pipe = conduit.get(cortex.name("counter"));  // ← Channel name

// Emit 200 numbers
for (int i = -100; i < 100; i++) {
    pipe.emit(i);
}
// Only 50 positive even numbers will be emitted (2, 4, 6, ..., 100)
```

**Key Insight:** The transformation pipeline is configured once at the **Conduit level**. All Channels created from `conduit.get()` share the same transformation, regardless of their individual names.

**Example showing shared transformations:**
```java
// Same conduit with transformation
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("numbers"),
    Composer.pipe(segment -> segment.guard(n -> n > 0))  // Only positive
);

// Two different channels from the SAME conduit
Pipe<Integer> channel1 = conduit.get(cortex.name("producer1"));
Pipe<Integer> channel2 = conduit.get(cortex.name("producer2"));

channel1.emit(-5);  // ❌ Filtered out (negative)
channel1.emit(10);  // ✅ Passes through (positive)

channel2.emit(-3);  // ❌ Filtered out (negative)
channel2.emit(7);   // ✅ Passes through (positive)

// BOTH channels apply the same guard(n -> n > 0) transformation
```

### Example: Sampling

```java
segment -> segment.sample(10)  // Every 10th emission

// Emit 100 values → only 10 reach consumers (10, 20, 30, ..., 100)
```

### Example: Reduction (Running Total)

```java
segment -> segment.reduce(
    0,                    // Initial value
    Integer::sum          // Accumulator
)

// Input:  1, 2, 3, 4, 5
// Output: 1, 3, 6, 10, 15 (running total)
```

### Example: Change Detection

```java
segment -> segment.diff()

// Input:  1, 1, 2, 2, 2, 3, 1
// Output: 1, 2, 3, 1 (only when value changes)
```

### Example: Sift (Comparator-based Filtering)

```java
segment -> segment.sift(
    Integer::compareTo,
    sift -> sift
        .above(0)      // Greater than 0
        .max(100)      // Less than or equal to 100
        .high()        // Only new highs
)

// Input:  -5, 10, 5, 20, 15, 30, 25, 110
// Output: 10, 20, 30 (positives, ≤100, new highs only)
```

### Chaining Transformations

```java
segment -> segment
    .guard(n -> n > 0)                    // 1. Filter positives
    .replace(n -> n * 2)                  // 2. Double values
    .sift(Integer::compareTo, s -> s.high()) // 3. Only new highs
    .limit(10)                            // 4. Max 10 emissions

// Input:  -1, 5, 3, 10, 8, 20, 15
// After guard:   5, 3, 10, 8, 20, 15
// After replace: 10, 6, 20, 16, 40, 30
// After sift:    10, 20, 40
// After limit:   10, 20, 40 (under limit)
```

## Resource Management

### Resource Interface

All major components implement `Resource`:

```java
interface Resource {
    @Idempotent
    void close();  // Cleanup and release resources
}
```

**Implementations:**
- **Component** (Circuit, Clock, Container)
- **Subscription**
- **Sink**

**@Idempotent Annotation:**

The `@Idempotent` annotation on `close()` indicates it's safe to call multiple times with the same effect:

```java
Circuit circuit = cortex.circuit();
circuit.close();
circuit.close();  // ✅ Safe - idempotent (no-op on second call)
```

This is a method-level annotation (unrelated to `Id` or `@Identity`) that documents safe repeated execution.

### Manual Lifecycle

```java
Circuit circuit = cortex.circuit();
Clock clock = circuit.clock();

// Use resources...

// Manual cleanup
clock.close();
circuit.close();
```

### Scope-based Lifecycle

**Scope** manages resource lifecycle hierarchically:

```java
Scope scope = cortex.scope(cortex.name("transaction"));

// Register resources with scope
Circuit circuit = scope.register(cortex.circuit());
Clock clock = scope.register(circuit.clock());

// All registered resources closed when scope closes
scope.close();
```

### Closure Pattern (ARM)

**Closure** provides Automatic Resource Management:

```java
Scope scope = cortex.scope();
Circuit circuit = cortex.circuit();

scope.closure(circuit).consume(c -> {
    // Use circuit
    Pipe<String> pipe = c.conduit(name, Composer.pipe())
        .get(cortex.name("producer"));
    pipe.emit("message");

    // Circuit automatically closed when block exits
});
```

### Hierarchical Scopes

```java
Scope parent = cortex.scope(cortex.name("parent"));
Scope child1 = parent.scope(cortex.name("child1"));
Scope child2 = parent.scope(cortex.name("child2"));

Circuit c1 = child1.register(cortex.circuit());
Circuit c2 = child2.register(cortex.circuit());

// Close parent → closes child1, child2, c1, c2 in order
parent.close();
```

### Try-with-Resources

```java
try (Scope scope = cortex.scope()) {
    Circuit circuit = scope.register(cortex.circuit());
    // Use circuit
} // Scope auto-closes, cleaning up circuit
```

## Observability Model

### Subject-based Observation

Every component has a **Subject** that identifies it:

```java
Pipe<String> pipe = conduit.get(cortex.name("sensor1"));
Subject subject = pipe.subject();

System.out.println("Name: " + subject.name());
System.out.println("Type: " + subject.type());
System.out.println("ID: " + subject.id());
```

### Dynamic Subscription

Subscribers are notified when Subjects emit:

```java
source.subscribe(
    cortex.subscriber(
        cortex.name("observer"),
        (subject, registrar) -> {
            System.out.println("Subject emitting: " + subject.name());
            registrar.register(value -> {
                System.out.println("Value: " + value);
            });
        }
    )
);
```

### Hierarchical Names for Routing

```java
source.subscribe(
    cortex.subscriber(
        cortex.name("router"),
        (subject, registrar) -> {
            // Route based on name hierarchy
            subject.name().enclosure().ifPresent(parent -> {
                Pipe<?> targetPipe = getTargetPipe(parent);
                registrar.register(targetPipe);
            });
        }
    )
);
```

### Clock-based Observation

```java
Clock clock = circuit.clock(cortex.name("metrics-collector"));

clock.consume(
    cortex.name("collector"),
    Clock.Cycle.SECOND,
    instant -> {
        // Collect metrics every second
        collectMetrics(instant);
    }
);
```

### Sink for Capture

**Sink** captures emissions for later analysis:

```java
Sink<String> sink = cortex.sink(conduit.source());

// Emit some data
pipe.emit("event1");
pipe.emit("event2");

// Drain captured events
sink.drain().forEach(capture -> {
    System.out.println("Subject: " + capture.subject());
    System.out.println("Emission: " + capture.emission());
});

sink.close();
```

## Advanced Patterns

### Fan-out (One Producer, Multiple Consumers)

```java
Source<String> source = conduit.source();

// Consumer 1
source.subscribe(subscriber1);

// Consumer 2
source.subscribe(subscriber2);

// Consumer 3
source.subscribe(subscriber3);

// One emit reaches all three
pipe.emit("broadcast");
```

### Fan-in (Multiple Producers, One Consumer)

```java
Conduit<Pipe<String>, String> conduit = circuit.conduit(
    cortex.name("aggregator"),
    Composer.pipe()
);

// One consumer
conduit.source().subscribe(subscriber);

// Multiple producers
Pipe<String> producer1 = conduit.get(cortex.name("p1"));
Pipe<String> producer2 = conduit.get(cortex.name("p2"));
Pipe<String> producer3 = conduit.get(cortex.name("p3"));

producer1.emit("from p1");
producer2.emit("from p2");
producer3.emit("from p3");
// All reach same consumer
```

### Relay Pattern

```java
// Source conduit
Conduit<Pipe<String>, String> source = circuit.conduit(
    cortex.name("source"),
    Composer.pipe()
);

// Target conduit
Conduit<Pipe<String>, String> target = circuit.conduit(
    cortex.name("target"),
    Composer.pipe()
);

// Relay: subscribe to source, emit to target
source.source().subscribe(
    cortex.subscriber(
        cortex.name("relay"),
        (subject, registrar) -> {
            Pipe<String> targetPipe = target.get(subject.name());
            registrar.register(targetPipe);
        }
    )
);
```

### Transform Pattern

```java
Conduit<Pipe<Integer>, Integer> input = circuit.conduit(
    cortex.name("input"),
    Composer.pipe()
);

Conduit<Pipe<String>, String> output = circuit.conduit(
    cortex.name("output"),
    Composer.pipe()
);

// Transform integers to strings
input.source().subscribe(
    cortex.subscriber(
        cortex.name("transformer"),
        (subject, registrar) -> {
            Pipe<String> outputPipe = output.get(subject.name());
            registrar.register(n ->
                outputPipe.emit("Number: " + n)
            );
        }
    )
);
```

## Key Insights

### 1. Everything Has a Subject

All components implement `Substrate`, providing a `Subject` for identification and observation.

### 2. Pipes Are Dual-Purpose

Pipes have `emit()` method and are used:
- By producers (Channel provides Pipe)
- By consumers (Subscriber registers Pipe)
- Internally (Source implements Pipe)

### 3. Source ≠ Producer

**Source** is not a producer - it's an **observable stream**:
- You subscribe TO a Source
- Conduit emits INTO Source (via Pipe interface)
- Source dispatches to Subscribers

### 4. @Temporal = Don't Retain

Types marked `@Temporal` are transient:
- **Registrar** - Used only during subscriber callback
- **Sift** - Used only during sequencer configuration
- **Closure** - Used only for ARM pattern execution

### 5. Segment Is Mutable

Unlike most Substrates types, **Segment is mutable**:
- Returns `this` for fluent chaining
- Required because `Sequencer.apply()` is void
- Transformations accumulate on same object

### 6. Virtual Threads = Daemon

All background threads are virtual and daemon:
- Lightweight (millions possible)
- Auto-cleanup on JVM exit
- No explicit shutdown needed (but Circuit.close() is cleaner)

## Next Steps

- Read [Architecture Guide](ARCHITECTURE.md) for implementation details
- See [Examples](examples/) for complete working examples
- Review [Substrates API JavaDoc](https://github.com/humainary-io/substrates-api-java)
- Explore [William Louth's Blog](https://humainary.io/blog)
