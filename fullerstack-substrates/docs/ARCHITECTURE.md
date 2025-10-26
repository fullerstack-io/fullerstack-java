# Fullerstack Substrates - Architecture & Core Concepts

**API Version:** M18 (Cell API + Flow.skip)
**Java Version:** 25 (LTS with Virtual Threads)
**Status:** Production-ready (497 tests passing)

---

## Table of Contents

1. [What is Substrates?](#what-is-substrates)
2. [Design Philosophy](#design-philosophy)
3. [M18 Sealed Hierarchy](#m17-sealed-hierarchy)
4. [Core Entities](#core-entities)
5. [Data Flow](#data-flow)
6. [Implementation Details](#implementation-details)
7. [Thread Safety](#thread-safety)
8. [Resource Lifecycle](#resource-lifecycle)

---

## What is Substrates?

**Substrates** is a framework for building event-driven observability systems based on William Louth's **semiotic observability** vision.

### The Observability Evolution

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

### Key Capabilities

- ✅ **Type-safe event routing** - From producers to consumers via Channels/Pipes
- ✅ **Transformation pipelines** - Filter, map, reduce, limit, sample emissions with JVM-style fusion
- ✅ **Dynamic subscription** - Observers subscribe/unsubscribe at runtime
- ✅ **Precise ordering** - Valve pattern (Virtual CPU core) guarantees FIFO processing
- ✅ **Event-driven synchronization** - Zero-latency await() with wait/notify (no polling)
- ✅ **Pipeline optimization** - Automatic fusion of adjacent skip/limit operations
- ✅ **Hierarchical naming** - Dot-notation organization (kafka.broker.1.metrics)
- ✅ **Resource lifecycle** - Automatic cleanup with Scope
- ✅ **Immutable state** - Thread-safe state via Slot API

---

## Design Philosophy

**Core Principle:** Simplified, lean implementation focused on correctness, clarity, and production readiness.

### Architecture Principles

1. **Simplified Design** - Single implementations, no factory abstractions
2. **M18 Sealed Hierarchy** - Type-safe API contracts enforced by sealed interfaces
3. **Valve Pattern** (William's architecture) - BlockingQueue + Virtual Thread per Circuit
4. **Event-Driven Synchronization** - Zero-latency await() using wait/notify (no polling)
5. **Pipeline Fusion** - JVM-style optimization of adjacent transformations
6. **Immutable State** - Slot-based state management with value semantics
7. **Resource Lifecycle** - Explicit cleanup via `close()` on all components
8. **Thread Safety** - Concurrent collections where needed, immutability elsewhere
9. **Clear Separation** - Public API (interfaces) vs internal implementation (concrete classes)

### What We DO Optimize

✅ **Pipeline Fusion** - Automatic optimization of adjacent skip/limit operations
✅ **Event-Driven Await** - Zero-latency synchronization (no polling)
✅ **Name Interning** - HierarchicalName identity-based caching

### What We DON'T Do

❌ **No premature optimization** - Keep it simple first
❌ **No factory abstractions** - Direct component creation
❌ **No complex caching** - Simple ConcurrentHashMap patterns
❌ **No polling loops** - Event-driven synchronization instead

**Philosophy:** Build it simple, build it correct, then optimize hot paths identified by profiling or architectural insight.

---

## M18 Sealed Hierarchy

### Sealed Interfaces (Java JEP 409)

M18 uses sealed interfaces to restrict which classes can implement them:

```java
sealed interface Source<E> permits Context
sealed interface Context<E, S> permits Component
sealed interface Component<E, S> permits Circuit, Clock, Container
sealed interface Container<P, E, S> permits Conduit, Cell

// Non-sealed extension points (we implement these)
non-sealed interface Circuit extends Component
non-sealed interface Conduit<P, E> extends Container
non-sealed interface Cell<I, E> extends Container
non-sealed interface Clock extends Component
non-sealed interface Channel<E>
non-sealed interface Pipe<E>
non-sealed interface Sink<E>
```

### What This Means

✅ **You CAN implement:** Circuit, Conduit, Cell, Clock, Channel, Pipe, Sink
❌ **You CANNOT implement:** Source, Context, Component, Container (sealed)

The API controls the type hierarchy to prevent incorrect compositions.

### Impact on Implementation

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
public class SequentialCircuit implements Circuit { }  // ✅
public class TransformingConduit<P, E> implements Conduit<P, E> { }  // ✅
public class SimpleCell<I, E> implements Cell<I, E> { }  // ✅
```

### Everything is a Subject

**Critical Architectural Insight:** The sealed hierarchy means that **every component is a Subject**.

```
Component<E, S> extends Subject<S>
    ↓
Circuit extends Component<State, Circuit>
    → Circuit IS-A Subject<Circuit>

Conduit<P, E> extends Container<P, E, Conduit<P, E>> extends Component<E, Conduit<P, E>>
    → Conduit<P, E> IS-A Subject<Conduit<P, E>>
    → Conduit<P, E> IS-A Source<E> (can be subscribed to)
```

**What This Means:**

1. **Conduit is a subscribable Subject:**
   - `Conduit<P, E>` IS-A `Source<E>` (via sealed hierarchy)
   - You can call `conduit.subscribe(subscriber)` directly
   - Subscribers receive `Subject<Channel<E>>` (the subjects of channels created within the conduit)

2. **Subscribers see channel subjects:**
   - When subscriber is registered, it's notified when new Channels are created
   - Subscriber receives the **Channel's Subject** (not the Conduit's subject)
   - Subscriber can inspect `Subject<Channel<E>>` to determine routing logic

3. **Dynamic pipe registration:**
   - Subscriber can call `conduit.get(subject.name())` to retrieve percepts
   - Subscriber registers `Pipe<E>` instances via `Registrar<E>`
   - Registered pipes receive all future emissions from that subject

**Example:**

```java
// Create conduit (which is itself a Source<Long>)
Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
    Cortex.name("sensors"),
    Composer.pipe()
);

// Subscribe to the conduit (possible because Conduit IS-A Source)
conduit.subscribe(Cortex.subscriber(
    Cortex.name("aggregator"),
    (subject, registrar) -> {
        // subject is Subject<Channel<Long>> - the channel that was created
        // We can inspect it and decide how to route

        // Get the percept for this subject (dual-key cache prevents recursion)
        Pipe<Long> pipe = conduit.get(subject.name());

        // Register our consumer pipe
        registrar.register(value -> {
            System.out.println("Received: " + value);
        });
    }
));
```

**Two-Phase Notification:**
1. **Phase 1:** Subscriber notified when `conduit.get(name)` creates a new Channel
2. **Phase 2:** Subscriber notified (lazily) on first emission from a Subject

This design enables **dynamic, hierarchical routing** where subscribers can:
- Inspect channel subjects to determine routing strategy
- Retrieve percepts to access producer channels
- Register multiple consumer pipes per subject
- Build hierarchical aggregation pipelines

---

## Core Entities

### 1. Cortex (Entry Point)

**Purpose:** Static factory for creating Circuits and Scopes

```java
import static io.humainary.substrates.api.Substrates.*;

Circuit circuit = Cortex.circuit(Cortex.name("kafka"));
Name brokerName = Cortex.name("kafka.broker.1");
```

**M18 Change:** Cortex is now accessed statically via `Substrates.Cortex`, not instantiated.

**Implementation:**

```java
public class CortexRuntime implements Cortex {
    private final Map<Name, Circuit> circuits = new ConcurrentHashMap<>();
    private final Map<Name, Scope> scopes = new ConcurrentHashMap<>();

    @Override
    public Circuit circuit(Name name) {
        return circuits.computeIfAbsent(name, SequentialCircuit::new);
    }
}
```

---

### 2. Circuit (Event Orchestration Hub)

**Purpose:** Central processing engine with virtual CPU core pattern

**Key Features:**
- Single virtual thread processes events in FIFO order
- Contains Conduits and Clocks
- Shared ScheduledExecutorService for all Clocks
- Component lifecycle management

```java
Circuit circuit = Cortex.circuit(Cortex.name("kafka.monitoring"));

Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors =
    circuit.conduit(Cortex.name("monitors"), Composer.pipe());

Clock clock = circuit.clock(Cortex.name("timer"));
```

**Virtual CPU Core Pattern:**

```
Events → BlockingQueue → Single Virtual Thread → FIFO Processing → Subscribers
```

**Guarantees:** Events processed in exact order received, no race conditions.

**Implementation:**

```java
public class SequentialCircuit implements Circuit {
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Map<Name, Conduit<?, ?>> conduits = new ConcurrentHashMap<>();
    private final Map<Name, Clock> clocks = new ConcurrentHashMap<>();

    private void startProcessing() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Runnable task = queue.take();  // Blocks until available
                task.run();  // Execute in FIFO order
            }
        });
    }
}
```

---

### 3. Name (Hierarchical Identity)

**Purpose:** Dot-notation hierarchical names (e.g., "kafka.broker.1")

**HierarchicalName Implementation:**

```java
public final class HierarchicalName implements Name {
    private final HierarchicalName parent;       // Parent in hierarchy
    private final String segment;        // This segment
    private final String cachedPath;     // Full path cached

    public static Name of(String path) {
        // Creates hierarchy from "kafka.broker.1"
    }

    @Override
    public Name name(String segment) {
        return new HierarchicalName(this, segment);  // Create child
    }
}
```

**Building Hierarchical Names:**

```java
// From string
Name name = Cortex.name("kafka.broker.1.metrics.bytes-in");

// Hierarchically
Name kafka = Cortex.name("kafka");
Name broker = kafka.name("broker").name("1");
Name metrics = broker.name("metrics");
Name bytesIn = metrics.name("bytes-in");
// Result: "kafka.broker.1.metrics.bytes-in"
```

---

### 4. Conduit (Container)

**Purpose:** Creates Channels and manages subscriber notifications

```java
Conduit<Pipe<String>, String> messages =
    circuit.conduit(Cortex.name("messages"), Composer.pipe());

// Get Pipe for specific subject
Pipe<String> pipe = messages.get(Cortex.name("user.login"));
pipe.emit("User logged in");

// Subscribe to all subjects (Conduit IS-A Source in M18)
messages.subscribe(
    Cortex.subscriber(
        Cortex.name("logger"),
        (subject, registrar) -> registrar.register(msg -> log.info(msg))
    )
);
```

**Implementation:**

```java
public class TransformingConduit<P, E> implements Conduit<P, E> {
    private final SourceImpl<E> source;  // Internal subscriber management
    private final Map<Name, EmissionChannel<E>> channels = new ConcurrentHashMap<>();
    private final Consumer<Flow<E>> flowConfigurer;  // Transformations

    @Override
    public P get(Name subject) {
        EmissionChannel<E> channel = channels.computeIfAbsent(
            subject,
            s -> new EmissionChannel<>(s, circuit.scheduler(), source, flowConfigurer)
        );
        return (P) channel.pipe();
    }

    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        return source.subscribe(subscriber);  // Delegate to SourceImpl
    }
}
```

---

### 5. Channel & Pipe (Emission)

**Channel:** Named emission port

```java
public class EmissionChannel<E> implements Channel<E> {
    private final ProducerPipe<E> cachedPipe;

    @Override
    public Pipe<E> pipe() {
        return cachedPipe;  // Returns cached ProducerPipe
    }
}
```

**ProducerPipe:** Producer-side pipe that emits INTO the conduit system

```java
public class ProducerPipe<E> implements Pipe<E> {
    private final Subject<Channel<E>> channelSubject;
    private final Consumer<Capture<E, Channel<E>>> subscriberNotifier;
    private final TransformationPipeline<E> flow;  // Optional transformations

    @Override
    public void emit(E value) {
        // Apply transformations (if configured)
        E transformed = flow != null ? flow.apply(value) : value;
        if (transformed == null) return;  // Filtered out

        // Post to Circuit queue → notifies subscribers
        scheduler.schedule(() -> {
            Capture<E, Channel<E>> capture = new CaptureImpl<>(channelSubject, transformed);
            subscriberNotifier.accept(capture);
        });
    }
}
```

**ConsumerPipe:** Consumer-side pipe that receives FROM the conduit system

```java
public class ConsumerPipe<E> implements Pipe<E> {
    private final Consumer<E> consumer;

    // Called BY Conduit when routing emissions to subscribers
    @Override
    public void emit(E emission) {
        consumer.accept(emission);  // Invoke consumer lambda
    }

    // Factory methods
    public static <E> ConsumerPipe<E> of(Consumer<E> consumer) { ... }
    public static <E> ConsumerPipe<E> of(Name name, Consumer<E> consumer) { ... }
}
```

**Producer-Consumer Pattern:**

The `Pipe<E>` interface serves **dual purposes** via a single `emit()` method:

| Pipe Type | Role | Who Calls `emit()` | What It Does |
|-----------|------|-------------------|--------------|
| **ProducerPipe** | Producer | Application code | Posts to circuit queue → notifies subscribers |
| **ConsumerPipe** | Consumer | Conduit (during dispatch) | Invokes consumer lambda |

```java
// PRODUCER SIDE
ProducerPipe<Long> producer = conduit.get("sensor1");
producer.emit(42L);  // ← Application calls emit() to produce INTO system

// CONSUMER SIDE (registered by subscriber)
registrar.register(ConsumerPipe.of(value -> {
    System.out.println(value);  // ← Conduit calls emit() to deliver FROM system
}));
```

**With Transformations (Flow/Sift):**

```java
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    Cortex.name("filtered-numbers"),
    Composer.pipe(flow -> flow
        .sift(n -> n > 0)     // Only positive
        .limit(100)           // Max 100 emissions
        .sample(10)           // Every 10th
    )
);
```

---

### 6. Cell (Hierarchical Transformation)

**Purpose:** Type transformation with parent-child hierarchy (I → E)

```java
// Level 1: JMX stats → Broker health
Cell<JMXStats, BrokerHealth> brokerCell = circuit.cell(
    Cortex.name("broker-1"),
    stats -> assessBrokerHealth(stats)
);

// Level 2: Broker health → Cluster health
Cell<BrokerHealth, ClusterHealth> clusterCell = brokerCell.cell(
    Cortex.name("cluster"),
    health -> aggregateClusterHealth(health)
);

// Subscribe to cluster health
clusterCell.subscribe(
    Cortex.subscriber(
        Cortex.name("alerting"),
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

**Implementation:**

```java
public class SimpleCell<I, E> implements Cell<I, E> {
    private final Function<I, E> transformer;  // I → E transformation
    private final SourceImpl<E> source;
    private final Map<Name, SimpleCell<E, ?>> children = new ConcurrentHashMap<>();

    @Override
    public <O> Cell<E, O> cell(Name name, Function<E, O> transformer) {
        return children.computeIfAbsent(name, n ->
            new SimpleCell<>(n, this, transformer, circuit)
        );
    }

    public void input(I value) {
        E transformed = transformer.apply(value);
        source.emit(transformed);  // Emit to subscribers
    }
}
```

---

### 7. Clock (Scheduled Events)

**Purpose:** Timer utility for time-driven behaviors

```java
Clock clock = circuit.clock(Cortex.name("poller"));

// Poll every second
clock.consume(
    Cortex.name("jmx-poll"),
    Clock.Cycle.SECOND,
    instant -> {
        BrokerStats stats = jmxClient.fetchStats();
        statsPipe.emit(stats);
    }
);
```

**Shared Scheduler Optimization:**

All Clocks in a Circuit share one ScheduledExecutorService:

```java
Circuit circuit = Cortex.circuit(Cortex.name("kafka"));
Clock clock1 = circuit.clock(Cortex.name("clock-1"));  // Uses circuit scheduler
Clock clock2 = circuit.clock(Cortex.name("clock-2"));  // Same scheduler
Clock clock3 = circuit.clock(Cortex.name("clock-3"));  // Same scheduler
```

**Benefits:** Reduced thread overhead, better resource utilization.

---

### 8. Scope (Resource Lifecycle)

**Purpose:** Automatic resource cleanup

```java
Scope scope = Cortex.scope(Cortex.name("session"));

Circuit circuit = scope.register(Cortex.circuit(Cortex.name("kafka")));
Conduit<Pipe<Event>, Event> events = scope.register(
    circuit.conduit(Cortex.name("events"), Composer.pipe())
);

// Use resources...

scope.close();  // Closes all registered resources automatically
```

---

### 9. SourceImpl (Internal Subscriber Management)

**Purpose:** Internal utility for managing subscribers (does NOT implement Source)

```java
public class SourceImpl<E> {
    private final List<Subscriber<E>> subscribers = new CopyOnWriteArrayList<>();

    public Subscription subscribe(Subscriber<E> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    public void emit(E event) {
        for (Subscriber<E> subscriber : subscribers) {
            // Notify all subscribers
        }
    }
}
```

**Why CopyOnWriteArrayList?**
- Read-heavy workload (many emits, few subscribes)
- Emissions happen millions/second
- Subscriptions happen rarely (at startup)
- No lock contention during hot path

---

### 10. State & Slot (Immutable State)

**Purpose:** Thread-safe state management

```java
// Create state
State state = Cortex.state()
    .state(Cortex.name("broker-id"), 1)
    .state(Cortex.name("heap-used"), 850_000_000L)
    .state(Cortex.name("status"), "HEALTHY");

// Access values (type-safe)
Integer brokerId = state.value(slot(Cortex.name("broker-id"), 0));
Long heapUsed = state.value(slot(Cortex.name("heap-used"), 0L));

// State is immutable - create new state to change
State newState = state.state(Cortex.name("heap-used"), 900_000_000L);
```

**Key Features:**
- Immutable - each `state()` call returns new State
- Type-safe - matches by name AND type
- Allows duplicate names with different types

---

## Data Flow

### Producer → Consumer Path

```
1. Producer:
   conduit.get(name) → Returns Pipe
   pipe.emit(value) → Applies transformations

2. Pipe:
   Transformations applied (sift, limit, sample)
   Transformed value → SourceImpl.emit()

3. SourceImpl:
   Iterates subscribers (CopyOnWriteArrayList)
   Calls subscriber callbacks

4. Subscriber:
   Receives emission via registered Pipe
   Processes event
```

### Virtual CPU Core Pattern

```
Circuit Queue (FIFO):
  [Event 1] → [Event 2] → [Event 3] → ...
      ↓
  Single Virtual Thread (daemon)
      ↓
  Process in Order (no race conditions)
      ↓
  Emit to Subscribers
```

**Critical Insight:**
- `pipe.emit(value)` returns **immediately** (async boundary)
- Subscriber callbacks execute **asynchronously** on Queue thread
- **MUST use `circuit.await()` in tests** to wait for processing

---

## Valve Pattern (Virtual CPU Core)

**Core Concept:** Each Circuit contains a Valve - a combination of BlockingQueue + Virtual Thread that processes all emissions serially.

### What is a Valve?

```java
public class Valve implements AutoCloseable {
    private final BlockingQueue<Runnable> queue;  // FIFO task queue
    private final Thread processor;                // Virtual thread
    private final Object idleLock;                 // Event-driven synchronization

    // Emissions → Tasks (submitted to valve)
    public boolean submit(Runnable task);

    // Event-driven await (zero-latency, no polling)
    public void await(String contextName);
}
```

### Architecture

```
Circuit
  └── Valve ("valve-circuit-name")
        ├── BlockingQueue<Runnable>  (FIFO task queue)
        ├── Virtual Thread           (parks when empty, unparks on task)
        └── Object idleLock          (wait/notify synchronization)

Emission Flow:
  Pipe.emit(value)
    → Valve.submit(task)              // Add to queue
      → BlockingQueue.offer(task)     // FIFO enqueue
        → Virtual Thread.take()       // Unpark and execute
          → task.run()                // Process emission
            → notifyAll()             // Wake awaiting threads
```

### Event-Driven Synchronization

**Before (Polling):**
```java
// Old approach - polling with Thread.sleep(10)
while (running && (executing || !queue.isEmpty())) {
    Thread.sleep(10);  // ❌ 0-10ms latency, CPU waste
}
```

**After (Event-Driven):**
```java
// New approach - wait/notify
synchronized (idleLock) {
    while (running && (executing || !queue.isEmpty())) {
        idleLock.wait();  // ✅ <1ms latency, zero CPU
    }
}

// Processor notifies when idle:
executing = false;
if (queue.isEmpty()) {
    synchronized (idleLock) {
        idleLock.notifyAll();  // Wake all waiters
    }
}
```

**Benefits:**
- ✅ **Zero latency** - Threads wake immediately when valve is idle
- ✅ **Zero CPU waste** - No polling loops consuming cycles
- ✅ **Scalable** - 1000 circuits don't create 1000 polling threads
- ✅ **Precise** - Deterministic notification vs random 0-10ms delay

### Virtual CPU Core Guarantees

1. **FIFO Ordering** - Tasks execute in submission order
2. **Single-Threaded** - No concurrent execution within Circuit domain
3. **Thread Isolation** - Each Circuit has independent Valve
4. **Lock-Free** - BlockingQueue handles concurrency, no locks in Circuit code
5. **Event-Driven** - Parking/unparking via BlockingQueue.take() and wait/notify

---

## Pipeline Fusion Optimization

**Core Concept:** Automatically combine adjacent identical transformations to reduce overhead.

### What Gets Fused?

**Skip Fusion:**
```java
flow.skip(3).skip(2).skip(1)  // 3 transformations

// Optimized to:
flow.skip(6)  // 1 transformation (sum: 3+2+1)
```

**Limit Fusion:**
```java
flow.limit(10).limit(5).limit(7)  // 3 transformations

// Optimized to:
flow.limit(5)  // 1 transformation (minimum)
```

### How It Works

```java
public Flow<E> skip(long n) {
    // Check if last transformation was also skip()
    if (!metadata.isEmpty() &&
        metadata.get(metadata.size() - 1).type == TransformType.SKIP) {

        // Fuse: remove last skip, add counts, recurse
        long existingSkip = (Long) lastMeta.metadata;
        transformations.remove(transformations.size() - 1);
        metadata.remove(metadata.size() - 1);

        return skip(existingSkip + n);  // Recursive fusion
    }

    // No fusion - add normal skip
    addTransformation(skipLogic);
    metadata.add(new TransformMetadata(SKIP, n));
    return this;
}
```

### When Does Fusion Happen?

**Fusion occurs when:**
- ✅ Multiple configuration sources add transformations
- ✅ Plugin systems independently add filters
- ✅ Inheritance hierarchies layer transformations
- ✅ Runtime conditions add dynamic limits

**Example - Config Composition:**
```java
// base-config.yaml → skip(1000)
// env-config.yaml → skip(500)
// user-prefs.json → skip(2000)

// Result: skip(1000).skip(500).skip(2000)
// Fused to: skip(3500) automatically

// Performance: 1 counter check instead of 3
```

**Example - Dynamic Limits:**
```java
// Multiple rate limiting policies
flow.limit(systemMax);      // 10,000
flow.limit(userTierLimit);  // 1,000
flow.limit(regionalLimit);  // 3,000
flow.limit(customLimit);    // 2,000

// Fused to: limit(1000) - single counter (minimum)
// Processing 1M requests: 1M checks instead of 4M
```

### Performance Impact

```
Scenario: 1M messages/sec with skip(100).skip(200).skip(300)

Without Fusion:
- 3 transformations
- 3M function calls/sec
- 3M counter increments
- 3M comparisons

With Fusion:
- 1 transformation (skip(600))
- 1M function calls/sec
- 1M counter increments
- 1M comparisons

Savings: 66% reduction in CPU cycles
```

### Current Limitations

**Implemented:**
- ✅ `skip(n1).skip(n2)` → `skip(n1+n2)`
- ✅ `limit(n1).limit(n2)` → `limit(min(n1,n2))`

**Not Yet Implemented:**
- ⏳ `replace(f1).replace(f2)` → `replace(f1.andThen(f2))`
- ⏳ `guard(p1).guard(p2)` → `guard(x -> p1.test(x) && p2.test(x))`
- ⏳ `sample(n).sample(m)` → `sample(n*m)`

**Non-Adjacent Don't Fuse:**
```java
flow.skip(100)
    .guard(x -> x.isValid())  // ← Breaks fusion chain
    .skip(200);

// Result: 2 skip transformations (correct - different semantics)
```

---

## Name vs Subject Distinction

**Key Concept:** Names are referents (identifiers), Subjects are temporal/contextual instances.

### The Distinction

```java
// NAME = Linguistic referent (like "Miles" the identifier)
Name milesName = Cortex.name("Miles");

// SUBJECT = Temporal/contextual instantiation
Subject<?> milesInCircuitA = HierarchicalSubject.builder()
    .id(id1)                    // Unique ID
    .name(milesName)            // Same name reference
    .state(stateA)              // Different state (context A)
    .type(Person.class)
    .build();

Subject<?> milesInCircuitB = HierarchicalSubject.builder()
    .id(id2)                    // Different ID
    .name(milesName)            // Same name reference
    .state(stateB)              // Different state (context B)
    .type(Person.class)
    .build();

// Same Name, different temporal instances:
milesInCircuitA.id() != milesInCircuitB.id()      // Different IDs
milesInCircuitA.name() == milesInCircuitB.name()  // Same Name
milesInCircuitA.state() != milesInCircuitB.state() // Different states
```

### Why This Matters

```java
// Example: "Miles" exists in multiple Circuits simultaneously

Circuit circuitA = Cortex.circuit(Cortex.name("circuit-A"));
Circuit circuitB = Cortex.circuit(Cortex.name("circuit-B"));

// Both circuits create Channels named "Miles"
Channel<Metric> milesInA = conduitA.get(Cortex.name("Miles"));
Channel<Metric> milesInB = conduitB.get(Cortex.name("Miles"));

// Same Name referent, different Subject instances:
// - milesInA.subject() → Subject with unique ID in Circuit A context
// - milesInB.subject() → Subject with unique ID in Circuit B context
```

### Analogy

- **Name** = Word in dictionary ("run")
- **Subject** = Specific usage in context ("I run marathons" vs "Water runs downhill")

---

## Implementation Details

### Caching Strategy

Simple and effective - ConcurrentHashMap everywhere:

```
CortexRuntime
├── circuits: ConcurrentHashMap<Name, Circuit>
└── scopes: ConcurrentHashMap<Name, Scope>

SequentialCircuit
├── conduits: ConcurrentHashMap<Name, Conduit>
└── clocks: ConcurrentHashMap<Name, Clock>

TransformingConduit
└── channels: ConcurrentHashMap<Name, Channel>

SimpleCell
└── children: ConcurrentHashMap<Name, Cell>
```

**Key Points:**
- `computeIfAbsent()` for thread-safe lazy creation
- No complex optimizations - standard Java collections
- Fast enough for production (100k+ metrics @ 1Hz)

---

### Performance Characteristics

**Test Suite:**
- 497 tests in ~12 seconds
- 0 failures, 0 errors
- Includes 308 tests from official Substrates testkit

**Production Target:**
- 100k+ metrics @ 1Hz
- ~2% CPU usage (estimated)
- ~200-300MB memory

**Per-Operation Costs:**
- Component lookup: ~5-10ns (ConcurrentHashMap)
- Pipe emission: ~100-300ns (with transformations)
- Subscriber notification: ~20-50ns per subscriber

---

## Thread Safety

### Concurrent Components

- **ConcurrentHashMap** - All component caches
- **CopyOnWriteArrayList** - Subscriber lists (read-heavy)
- **BlockingQueue** - Circuit event queue

### Immutable Components

- **HierarchicalName** - Immutable parent-child structure
- **State/Slot** - Immutable state management
- **Signal Types** - Immutable records (Serventis)

### Synchronization Points

- **Circuit Queue** - Single thread, FIFO ordering
- **Component Creation** - `computeIfAbsent()` handles races
- **Subscriber Registration** - CopyOnWriteArrayList handles concurrent adds

---

## Resource Lifecycle

All components implement `Resource` with `close()`:

```
Scope.close()
  → Circuit.close()
    → Conduit.close()
      → Channel.close()
    → Clock.close()
      → Cancel scheduled tasks
    → Shutdown executor
    → Shutdown scheduler
```

**Best Practices:**

```java
// 1. Try-with-resources
try (Circuit circuit = Cortex.circuit(Cortex.name("test"))) {
    // Use circuit
}

// 2. Scope for grouped cleanup
Scope scope = Cortex.scope(Cortex.name("session"));
Circuit circuit = scope.register(Cortex.circuit(Cortex.name("kafka")));
scope.close();  // Closes all registered resources

// 3. Manual cleanup
Circuit circuit = Cortex.circuit(Cortex.name("kafka"));
try {
    // Use circuit
} finally {
    circuit.close();
}
```

---

## Integration with Serventis

**Example: Kafka Broker Monitoring**

```java
// Create Circuit
Circuit circuit = Cortex.circuit(Cortex.name("kafka.broker.health"));

// Create Conduit for MonitorSignals
Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors =
    circuit.conduit(Cortex.name("monitors"), Composer.pipe());

// Get Pipe for specific subject
Pipe<MonitorSignal> heapPipe =
    monitors.get(Cortex.name("broker-1.jvm.heap"));

// Emit MonitorSignal
MonitorSignal signal = new MonitorSignal(
    UUID.randomUUID(),
    "kafka.broker.health",        // circuit
    "broker-1.jvm.heap",          // subject
    Instant.now(),
    new VectorClock(Map.of("broker-1", 42L)),
    MonitorStatus.DEGRADED,
    Map.of("heapUsed", "85%")
);
heapPipe.emit(signal);

// Subscribe to observe signals
monitors.subscribe(
    Cortex.subscriber(
        Cortex.name("health-aggregator"),
        (subject, registrar) -> {
            registrar.register(s -> {
                if (s.status() == MonitorStatus.DEGRADED) {
                    // Take action
                }
            });
        }
    )
);
```

---

## Summary

**Fullerstack Substrates:**

✅ **Simple** - No complex optimizations, easy to understand
✅ **Correct** - 247 tests passing, proper M18 sealed interface usage
✅ **Fast Enough** - Handles 100k+ metrics @ 1Hz
✅ **Thread-Safe** - Proper concurrent collections
✅ **Clean** - Explicit resource lifecycle management
✅ **Maintainable** - Clear architecture, good documentation

**Philosophy:** Build it simple, build it correct, optimize if needed.

---

## References

- [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)
- [Observability X Blog Series](https://humainary.io/blog/category/observability-x/)
- [M18 Migration Guide](../../API-ANALYSIS.md)
- [Developer Guide](DEVELOPER-GUIDE.md)
- [Async Architecture](ASYNC-ARCHITECTURE.md)
