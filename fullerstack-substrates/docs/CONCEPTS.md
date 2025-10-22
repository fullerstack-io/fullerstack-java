# Fullerstack Substrates - Core Concepts

## Overview

This document explains the fundamental concepts in Fullerstack Substrates and how they work together to build event-driven observability systems.

**Prerequisites:** Basic familiarity with event-driven architectures and reactive patterns

---

## Table of Contents

1. [What is Substrates?](#what-is-substrates)
2. [Core Entities](#core-entities)
3. [M17 Sealed Hierarchy](#m17-sealed-hierarchy)
4. [Naming and Hierarchy](#naming-and-hierarchy)
5. [Event Flow](#event-flow)
6. [Transformations](#transformations)
7. [Subscription Pattern](#subscription-pattern)
8. [Resource Lifecycle](#resource-lifecycle)
9. [State Management](#state-management)
10. [Timing and Scheduling](#timing-and-scheduling)

---

## What is Substrates?

**Substrates** is a framework for building event-driven observability systems based on William Louth's vision of **semiotic observability**.

### Key Capabilities

1. **Type-safe event routing** - From producers to consumers via Channels/Pipes
2. **Transformation pipelines** - Filter, map, reduce, limit, sample emissions
3. **Dynamic subscription** - Observers subscribe/unsubscribe at runtime
4. **Resource lifecycle** - Automatic cleanup with Scope
5. **Precise ordering** - Virtual CPU core pattern guarantees FIFO event processing
6. **Hierarchical naming** - Dot-notation names organize components
7. **Immutable state** - Slot-based state management

### Semiotic Observability

Substrates supports the observability evolution:

```
Metrics (traditional numbers)
    ↓
Signs (observations with meaning)
    ↓
Symptoms (patterns in signs)
    ↓
Syndromes (correlated symptoms)
    ↓
Situations (system states)
    ↓
Steering (automated responses)
```

**Substrates** provides the infrastructure layer, **Serventis** provides the semantic signal types.

---

## Core Entities

### Cortex

**Purpose:** Entry point and factory for creating Circuits and Scopes

**Interface:**
```java
public interface Cortex {
    Circuit circuit(Name name);
    Scope scope(Name name);
    Name name(String path);
}
```

**Usage:**
```java
Cortex cortex = CortexRuntime.create();
Circuit circuit = cortex.circuit(cortex.name("kafka"));
Name brokerName = cortex.name("kafka.broker.1");
```

**Responsibilities:**
- Create and cache Circuits
- Create and cache Scopes
- Create hierarchical Names

---

### Circuit

**Purpose:** Central event orchestration hub with virtual CPU core pattern

**Key Features:**
- Contains Conduits and Clocks
- Single virtual thread processes events in FIFO order
- Shared ScheduledExecutorService for all Clocks
- Component lifecycle management

**Interface:**
```java
public interface Circuit extends Component {
    <P, E> Conduit<P, E> conduit(Name name, Composer<P, E> composer);
    Clock clock(Name name);
    void close();
}
```

**Usage:**
```java
Circuit circuit = cortex.circuit(cortex.name("kafka.monitoring"));

Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors =
    circuit.conduit(cortex.name("monitors"), Composer.pipe());

Clock clock = circuit.clock(cortex.name("timer"));
```

**Virtual CPU Core Pattern:**
```
Events → BlockingQueue → Single Virtual Thread → FIFO Processing
```

Guarantees precise ordering - events processed in exact order received.

---

### Conduit

**Purpose:** Container that creates Channels and manages subscriber notifications

**Key Features:**
- Creates Channels on-demand via `get(Name subject)`
- IS-A Source (can subscribe to it directly)
- Configures Flow/Sift transformations for all Channels
- Thread-safe component caching

**Interface:**
```java
public interface Conduit<P, E> extends Container<P, E> {
    P get(Name subject);  // Get Pipe for subject
    Subscription subscribe(Subscriber<E> subscriber);
}
```

**Usage:**
```java
Conduit<Pipe<String>, String> messages =
    circuit.conduit(cortex.name("messages"), Composer.pipe());

// Get Pipe for specific subject
Pipe<String> pipe = messages.get(cortex.name("user.login"));
pipe.emit("User logged in");

// Subscribe to all subjects
messages.subscribe(
    cortex.subscriber(
        cortex.name("logger"),
        (subject, registrar) -> registrar.register(msg -> log.info(msg))
    )
);
```

**Note:** In M17, Conduit extends Container → Component → Context → Source, so you call `subscribe()` directly on the Conduit, not `conduit.source().subscribe()`.

---

### Channel

**Purpose:** Named emission port linking producers to the event stream

**Key Features:**
- Created by Conduit for a specific subject
- Provides access to Pipe for emissions
- Lightweight connector (just name + pipe reference)

**Interface:**
```java
public interface Channel<E> {
    Name name();
    Pipe<E> pipe();
}
```

**Usage:**
```java
// Channel created internally when you call conduit.get()
Pipe<Event> pipe = conduit.get(cortex.name("subject"));
// pipe belongs to a Channel with name "subject"
```

**Typical Pattern:**
```java
// Cache the Pipe, not the Channel
Pipe<MetricValue> metricPipe = metrics.get(cortex.name("bytes-in"));

// Emit repeatedly
for (MetricValue value : values) {
    metricPipe.emit(value);
}
```

---

### Pipe

**Purpose:** Event transformation and emission

**Key Features:**
- Applies transformations (filter, limit, sample)
- Emits to subscribers via SourceImpl
- Configured by Flow/Sift at Conduit creation

**Interface:**
```java
public interface Pipe<E> {
    void emit(E event);
}
```

**Usage:**
```java
Pipe<Integer> pipe = conduit.get(cortex.name("numbers"));
pipe.emit(42);
pipe.emit(100);
```

**With Transformations:**
```java
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("filtered-numbers"),
    Composer.pipe(flow -> flow
        .sift(n -> n > 0)     // Only positive
        .limit(100)           // Max 100 emissions
        .sample(10)           // Every 10th
    )
);

Pipe<Integer> pipe = conduit.get(cortex.name("input"));
for (int i = -50; i <= 150; i++) {
    pipe.emit(i);  // Transformations applied automatically
}
```

---

### Cell

**Purpose:** Hierarchical container with type transformation (I → E)

**Key Features:**
- Transforms input type I to output type E
- Can have child Cells with different transformations
- Observable - can subscribe to transformed outputs
- Builds transformation trees

**Interface:**
```java
public interface Cell<I, E> extends Container {
    <O> Cell<E, O> cell(Name name, Function<E, O> transformer);
    void input(I value);
}
```

**Usage:**
```java
// Level 1: JMX stats → Broker health
Cell<JMXStats, BrokerHealth> brokerCell = circuit.cell(
    cortex.name("broker-1"),
    stats -> assessBrokerHealth(stats)
);

// Level 2: Broker health → Cluster health
Cell<BrokerHealth, ClusterHealth> clusterCell = brokerCell.cell(
    cortex.name("cluster"),
    health -> aggregateClusterHealth(health)
);

// Subscribe to cluster health
clusterCell.subscribe(
    cortex.subscriber(
        cortex.name("alerting"),
        (subject, registrar) -> registrar.register(health -> {
            if (health.status() == ClusterStatus.CRITICAL) {
                sendAlert(health);
            }
        })
    )
);

// Input at top level
brokerCell.input(jmxClient.fetchStats());
// Transformed through hierarchy → cluster health emitted
```

---

### Clock

**Purpose:** Scheduled event emission for time-driven behaviors

**Key Features:**
- Shared ScheduledExecutorService (one per Circuit)
- Multiple cycles (SECOND, MINUTE, HOUR, etc.)
- Observable - emits Instant on each tick

**Interface:**
```java
public interface Clock extends Component {
    Subscription consume(Name name, Cycle cycle, Consumer<Instant> consumer);
}
```

**Usage:**
```java
Clock clock = circuit.clock(cortex.name("poller"));

// Poll every second
clock.consume(
    cortex.name("jmx-poll"),
    Clock.Cycle.SECOND,
    instant -> {
        BrokerStats stats = jmxClient.fetchStats();
        statsPipe.emit(stats);
    }
);

// Aggregate every minute
clock.consume(
    cortex.name("aggregate"),
    Clock.Cycle.MINUTE,
    instant -> aggregateMetrics()
);
```

---

### Scope

**Purpose:** Hierarchical resource lifecycle management

**Key Features:**
- Register Resources for automatic cleanup
- Close all resources in one call
- Exception-safe (continues closing even if one fails)

**Interface:**
```java
public interface Scope extends Resource {
    <R extends Resource> R register(R resource);
    void close();
}
```

**Usage:**
```java
Scope scope = cortex.scope(cortex.name("session"));

Circuit circuit = scope.register(cortex.circuit(cortex.name("kafka")));
Conduit<Pipe<Event>, Event> events = scope.register(
    circuit.conduit(cortex.name("events"), Composer.pipe())
);
Clock clock = scope.register(circuit.clock(cortex.name("timer")));

// Use resources...

scope.close();  // Closes all three resources automatically
```

---

### Sink

**Purpose:** Event capture and storage for testing/debugging

**Key Features:**
- Collects emissions for inspection
- Thread-safe (CopyOnWriteArrayList)
- Useful for testing

**Interface:**
```java
public interface Sink<E> extends Resource {
    List<E> events();
    void clear();
}
```

**Usage:**
```java
Sink<String> sink = cortex.sink(cortex.name("test-sink"));

conduit.subscribe(
    cortex.subscriber(
        cortex.name("collector"),
        (subject, registrar) -> registrar.register(sink::capture)
    )
);

pipe.emit("Event 1");
pipe.emit("Event 2");

assertThat(sink.events()).containsExactly("Event 1", "Event 2");
```

---

## M17 Sealed Hierarchy

### What Are Sealed Interfaces?

Java sealed interfaces (JEP 409) restrict which classes can implement them.

**M17 Sealed Types:**
```java
sealed interface Source<E> permits Context
sealed interface Context<E, S> permits Component
sealed interface Component<E, S> permits Circuit, Clock, Container
sealed interface Container<P, E, S> permits Conduit, Cell
```

### Why Sealed?

1. **Type Safety** - API controls valid type compositions
2. **Evolution Safety** - Humainary can change internals without breaking users
3. **Clear Contracts** - `permits` clause documents all valid implementations

### What You Can Implement

✅ **Non-sealed (you can implement):**
- Circuit, Conduit, Cell, Clock
- Channel, Pipe, Subscriber, Sink

❌ **Sealed (you cannot implement):**
- Source, Context, Component, Container

### Impact on Our Implementation

**SourceImpl doesn't implement Source:**
```java
// Source is sealed, so this won't compile:
public class SourceImpl<E> implements Source<E> { }  // ❌

// Instead, SourceImpl is an internal utility:
public class SourceImpl<E> {
    public Subscription subscribe(Subscriber<E> subscriber) { }  // ✅
}
```

**Circuit/Conduit/Cell extend sealed types:**
```java
// These extend Context (which extends Source), so they inherit subscribe()
public class CircuitImpl implements Circuit { }  // ✅
public class ConduitImpl<P, E> implements Conduit<P, E> { }  // ✅
public class CellNode<I, E> implements Cell<I, E> { }  // ✅
```

---

## Naming and Hierarchy

### Name Interface

**Purpose:** Hierarchical semantic identity using dot notation

**Features:**
- Immutable parent-child structure
- Cached path string (built once)
- Always uses '.' as separator

**Interface:**
```java
public interface Name extends Extent<Name> {
    Name name(String segment);
    CharSequence path();
    String toString();
}
```

### NameNode Implementation

```java
public final class NameNode implements Name {
    private final NameNode parent;       // Parent in hierarchy
    private final String segment;        // This segment
    private final String cachedPath;     // Full path cached

    public static Name of(String path) {
        // Creates hierarchy from "kafka.broker.1"
    }

    @Override
    public Name name(String segment) {
        return new NameNode(this, segment);  // Create child
    }
}
```

### Building Hierarchical Names

**From String:**
```java
Name name = cortex.name("kafka.broker.1.metrics.bytes-in");
// Creates: kafka → broker → 1 → metrics → bytes-in
```

**Hierarchically:**
```java
Name kafka = cortex.name("kafka");
Name broker = kafka.name("broker");
Name broker1 = broker.name("1");
Name metrics = broker1.name("metrics");
Name bytesIn = metrics.name("bytes-in");

// Result: "kafka.broker.1.metrics.bytes-in"
```

### Why Hierarchical Names?

1. **Organization** - Natural tree structure for domains
2. **Discovery** - Can query all children of a parent
3. **Semantic** - Names convey meaning ("kafka.broker.1" vs "metric-12345")
4. **Caching** - Used as keys in component maps

---

## Event Flow

### Producer → Consumer Path

```
1. Producer:
   conduit.get(name) → Returns Pipe
   pipe.emit(value) → Applies transformations

2. Pipe:
   Transformations applied (sift, limit, sample)
   Transformed value → SourceImpl.emit()

3. SourceImpl:
   Iterates subscribers
   Calls subscriber callbacks

4. Subscriber:
   Receives emission
   Processes event
```

### Virtual CPU Core Pattern

```
Circuit maintains FIFO queue:

[Event 1] → [Event 2] → [Event 3] → ...
                ↓
        Single Virtual Thread
                ↓
        Process in Order
                ↓
        Emit to Subscribers
```

**Guarantees:**
- Events processed in exact order emitted
- No race conditions within Circuit
- Deterministic, predictable behavior

---

## Transformations

### Flow and Sift

**Flow:** Transformation configuration interface
**Sift:** Filtering/transformation operations

**Available Operations:**
- `sift(Predicate<E>)` - Filter events
- `limit(int)` - Max number of emissions
- `sample(int)` - Emit every Nth event
- (More operations defined by API)

### Configuring Transformations

**At Conduit Creation:**
```java
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("numbers"),
    Composer.pipe(flow -> flow
        .sift(n -> n > 0)      // Only positive
        .limit(1000)           // Max 1000 total
        .sample(10)            // Every 10th
    )
);

// All Pipes from this Conduit inherit transformations
Pipe<Integer> pipe = conduit.get(cortex.name("input"));
```

### Transformation Order

Order matters! Transformations apply left-to-right:

```java
// Filter first, then sample
flow.sift(n -> n > 100).sample(10)
// Out of 1000: ~500 pass filter → ~50 sampled

// Sample first, then filter
flow.sample(10).sift(n -> n > 100)
// Out of 1000: ~100 sampled → ~50 pass filter
```

---

## Subscription Pattern

### Subscriber Interface

```java
@FunctionalInterface
public interface Subscriber<E> {
    void accept(Name subject, Registrar<E> registrar);
}
```

**Two Parameters:**
1. **subject** - The Name being observed
2. **registrar** - Used to register consumer Pipe

### Subscribing to Events

```java
conduit.subscribe(
    cortex.subscriber(
        cortex.name("my-subscriber"),  // Subscriber name
        (subject, registrar) -> {       // Callback
            registrar.register(event -> {
                // Process event
                System.out.println("Received: " + event);
            });
        }
    )
);
```

### Registrar

**Purpose:** Register consumer Pipe within subscriber callback

**Interface:**
```java
public interface Registrar<E> {
    void register(Pipe<E> pipe);
}
```

**Usage:**
```java
(subject, registrar) -> {
    // Create consumer Pipe
    Pipe<Event> consumerPipe = event -> handleEvent(event);

    // Register it
    registrar.register(consumerPipe);
}
```

**Simplified (Lambda):**
```java
(subject, registrar) -> registrar.register(event -> handleEvent(event))
```

### Multiple Subscribers

```java
// Each subscriber receives all emissions
conduit.subscribe(loggingSubscriber);
conduit.subscribe(metricsSubscriber);
conduit.subscribe(alertingSubscriber);
```

### Unsubscribing

```java
Subscription sub = conduit.subscribe(subscriber);

// Later, unsubscribe
sub.close();
```

---

## Resource Lifecycle

### Resource Interface

All components implement Resource:

```java
public interface Resource extends AutoCloseable {
    void close();
}
```

### Cleanup Pattern

**Manual:**
```java
Circuit circuit = cortex.circuit(cortex.name("test"));
try {
    // Use circuit
} finally {
    circuit.close();
}
```

**Try-With-Resources:**
```java
try (Circuit circuit = cortex.circuit(cortex.name("test"))) {
    // Use circuit
} // Automatically closed
```

**Scope:**
```java
Scope scope = cortex.scope(cortex.name("session"));
Circuit circuit = scope.register(cortex.circuit(cortex.name("test")));
Conduit<Pipe<Event>, Event> conduit = scope.register(
    circuit.conduit(cortex.name("events"), Composer.pipe())
);

scope.close();  // Closes both circuit and conduit
```

### Why Close Resources?

- **Shutdown executors** - Virtual threads, schedulers
- **Cancel scheduled tasks** - Clock timers
- **Clear caches** - Component maps
- **Prevent memory leaks** - Release references

---

## State Management

### State and Slot

**Immutable state management:**

```java
// Create state
State state = State.of(
    Slot.of("broker-id", 1),
    Slot.of("heap-used", 850_000_000L),
    Slot.of("status", "HEALTHY")
);

// Access values (type-safe)
Optional<Integer> brokerId = state.get(Slot.of("broker-id"));

// State is immutable - create new state to change
State newState = state.set(Slot.of("heap-used", 900_000_000L));
```

### Slot Interface

```java
public interface Slot<T> {
    String key();
    T value();

    static <T> Slot<T> of(String key, T value) { }
}
```

### Type Safety

Slots are generically typed:

```java
Slot<Integer> brokerId = Slot.of("broker-id", 1);
Slot<Long> heapUsed = Slot.of("heap-used", 850_000_000L);
Slot<String> status = Slot.of("status", "HEALTHY");

State state = State.of(brokerId, heapUsed, status);

Optional<Integer> id = state.get(brokerId);  // Type-safe!
```

---

## Timing and Scheduling

### Clock Cycles

```java
public enum Cycle {
    MILLISECOND,
    SECOND,
    MINUTE,
    HOUR,
    DAY
}
```

### Scheduling Tasks

```java
Clock clock = circuit.clock(cortex.name("scheduler"));

// Every second
Subscription secondly = clock.consume(
    cortex.name("metrics-collect"),
    Clock.Cycle.SECOND,
    instant -> collectMetrics()
);

// Every minute
Subscription minutely = clock.consume(
    cortex.name("metrics-aggregate"),
    Clock.Cycle.MINUTE,
    instant -> aggregateMetrics()
);

// Stop tasks
secondly.close();
minutely.close();
```

### Shared Scheduler

All Clocks in a Circuit share one ScheduledExecutorService:

```java
Circuit circuit = cortex.circuit(cortex.name("kafka"));

Clock clock1 = circuit.clock(cortex.name("clock-1"));  // Uses circuit scheduler
Clock clock2 = circuit.clock(cortex.name("clock-2"));  // Same scheduler
Clock clock3 = circuit.clock(cortex.name("clock-3"));  // Same scheduler

// Only 1 scheduler thread for entire Circuit
```

**Benefits:**
- Reduced thread overhead
- Better resource utilization
- Simpler lifecycle management

---

## Summary

**Fullerstack Substrates Core Concepts:**

1. ✅ **Cortex** - Entry point, creates Circuits and Scopes
2. ✅ **Circuit** - Event orchestration with virtual CPU core
3. ✅ **Conduit** - Container for Channels, provides Source
4. ✅ **Channel** - Named emission port
5. ✅ **Pipe** - Transformation and emission
6. ✅ **Cell** - Hierarchical type transformation
7. ✅ **Clock** - Scheduled event emission
8. ✅ **Scope** - Resource lifecycle management
9. ✅ **Name** - Hierarchical dot-notation identity
10. ✅ **State/Slot** - Immutable state management

**Key Patterns:**
- Virtual CPU Core - Precise event ordering
- Sealed Hierarchy - M17 type safety
- Hierarchical Naming - Organized components
- Flow/Sift - Transformation pipelines
- Subscribe/Emit - Observer pattern
- Resource Cleanup - Explicit lifecycle

---

## References

- [Architecture Guide](ARCHITECTURE.md)
- [Best Practices](BEST-PRACTICES.md)
- [Performance Guide](PERFORMANCE.md)
- [Async Architecture](ASYNC-ARCHITECTURE.md)
- [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)
- [Observability X Blog Series](https://humainary.io/blog/category/observability-x/)
