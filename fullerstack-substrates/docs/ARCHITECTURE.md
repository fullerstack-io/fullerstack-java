# Architecture Guide

This document explains the architecture and design principles of the Fullerstack Substrates implementation.

## Table of Contents

- [Overview](#overview)
- [Core Components](#core-components)
- [Data Flow](#data-flow)
- [Design Principles](#design-principles)
- [Threading Model](#threading-model)
- [Resource Lifecycle](#resource-lifecycle)

## Overview

Substrates implements an event-driven architecture for observability, based on William Louth's vision of **semiotic observability**. The system routes emissions from producer code (using Pipes) to consumer code (using registered Pipes) through Conduits, Channels, and Sources, all coordinated by a central Circuit.

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Circuit                                 │
│  (Central Processing Engine - Precise Ordering Guarantees)      │
│                                                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                    Conduit                                 │ │
│  │                                                            │ │
│  │  Channel 1 ──┐                        ┌── Subscriber 1     │ │
│  │  Channel 2 ──┤→ Queue → Processor → Source ── Subscriber 2 │ │
│  │  Channel 3 ──┘                        └── Subscriber 3     │ │
│  │                                                            │ │
│  │  (Percepts created via Composer)                           │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                       │
│  │  Clock   │  │  Clock   │  │ Container│                       │
│  └──────────┘  └──────────┘  └──────────┘                       │
└─────────────────────────────────────────────────────────────────┘
```

## Core Components

### Circuit

**Role:** Central processing engine that coordinates all components.

**Responsibilities:**
- Manages Conduits, Clocks, Containers, and Queue
- Provides precise ordering guarantees for emitted events
- Coordinates resource lifecycle
- **Caches components by Name for singleton behavior**

**Key Methods:**
- `conduit(Name, Composer)` - Creates/retrieves Conduit (cached by Name)
- `clock(Name)` - Creates/retrieves Clock (cached by Name)
- `container(Name, Composer)` - Creates Container
- `queue()` - Returns single shared Queue
- `close()` - Closes all managed resources

**Implementation Details:**
- Uses `LazyTrieRegistry` (implements Map) for component caching with identity map fast path
- Implements lazy initialization via `computeIfAbsent()`
- **Name is the cache key** - same Name returns same instance
- **RegistryFactory injected** for pluggable registry implementations
- Daemon virtual threads auto-cleanup on JVM shutdown

**Caching Pattern:**
```java
// CircuitImpl.java
@SuppressWarnings("unchecked")
public CircuitImpl(Name name, NameFactory nameFactory, QueueFactory queueFactory, RegistryFactory registryFactory) {
    // ...
    this.clocks = (Map<Name, Clock>) registryFactory.create();  // LazyTrieRegistry by default
}

public Clock clock(Name name) {
    return clocks.computeIfAbsent(name, ClockImpl::new);  // ← Singleton per Name, identity map fast path
}

public Clock clock() {
    return clock(nameFactory.createRoot("clock"));  // ← Uses type-based default name
}
```

**Example:**
```java
Circuit circuit = cortex.circuit();

// First call creates clock "clock"
Clock c1 = circuit.clock();

// Second call returns THE SAME clock instance
Clock c2 = circuit.clock();

assert c1 == c2;  // ✅ Singleton behavior

// Different name creates new clock
Clock c3 = circuit.clock(cortex.name("ticker"));
assert c1 != c3;  // ✅ Different instance
```

### Conduit

**Role:** Creates named Channels and routes emissions from producer Pipes to consumer Pipes via Source subscription.

**Data Flow:**
```
Producer code: conduit.get(name) → returns Pipe (backed by Channel)
  ↓
Producer code: pipe.emit(value)
  ↓
Pipe applies transformations (if configured via Sequencer)
  ↓
Early exit if no subscribers (optimization)
  ↓
Synchronous emission to Source
  ↓
Source notifies all Subscribers
  ↓
Subscriber.accept() registers consumer Pipes (lazy, first emission)
  ↓
Consumer Pipes receive emission
  ↓
Consumer code processes value
```

**Performance Optimization:** Pipe emission is synchronous (no queue posting) for sub-nanosecond hot-path performance. This means subscriber callbacks execute in the emitter's thread. See [Performance Guide](PERFORMANCE.md) for benchmarks.

**Key Components:**
- **Composer** - Transforms Channel into Percept (e.g., Pipe, custom domain object)
- **Source** - Observable stream for subscriptions
- **SourceImpl** - Manages subscribers and pipes emission to registered Pipes
- **Circuit Queue** - Available for async coordination via queue.post(Script)

**Implementation Details:**
- Percepts (instruments wrapping Channels) cached by Name in LazyTrieRegistry (identity map fast path)
- Source caches registered Pipes per Channel Subject (keyed by Subject Name) per Subscriber
- Early subscriber check optimization (hasSubscribers() → 3.3ns hot path with identity map)
- Synchronous emission handler for minimal latency
- RegistryFactory propagated from Circuit for consistent registry implementation

### Channel

**Role:** Named connector that links producers and consumers; provides access to a Pipe for emission.

**Characteristics:**
- Named entry point (has Subject)
- Provides `pipe()` method to get Pipe for emission
- Supports transformations via `pipe(Sequencer)`
- Shares queue with parent Conduit

**Usage:**
```java
Conduit<Pipe<String>, String> conduit = circuit.conduit(name, Composer.pipe());
Pipe<String> pipe = conduit.get(cortex.name("producer1"));
pipe.emit("message");
```

### Container

**Role:** Manages a collection of Conduits - implements the "Pool of Pools" pattern.

**Type Signature:**
```java
interface Container<P, E> extends Pool<P>, Component<E>

// When created by Circuit:
Container<Pool<P>, Source<E>> container = circuit.container(name, composer);
```

**Key Characteristics:**
- Container IS-A `Pool<Pool<P>>` - get() returns a Pool (Conduit)
- Container IS-A `Component<Source<E>>` - source() returns Source<Source<E>>
- Each unique name creates its own Conduit on-demand
- All Conduits in Container share same Composer and transformations
- Hierarchical naming: `container-name.conduit-name`

**Architecture:**
```
Container
  ├── conduits: Map<Name, Conduit> ← Conduit cache by name
  ├── containerSource: Source<Source<E>> ← Emits Conduit Sources
  └── circuit: Circuit ← Reference for Conduit creation

When container.get(newName):
  1. Check conduits cache
  2. If absent: create new Conduit via Circuit
  3. Emit Conduit's Source to containerSource
  4. Cache and return Conduit (as Pool<P>)
  5. Next get(sameName) returns cached Conduit (no emission)
```

**Implementation Pattern:**
```java
// ContainerImpl.java
private final Map<Name, Conduit<P, E>> conduits;  // LazyTrieRegistry via RegistryFactory
private final SourceImpl<Source<E>> containerSource;

public Pool<P> get(Name name) {
    final boolean[] isNewConduit = {false};

    // Get or create Conduit
    Conduit<P, E> conduit = conduits.computeIfAbsent(name, n -> {
        // Build hierarchical name
        Name conduitName = containerName.name(n);

        // Create via Circuit
        Conduit<P, E> newConduit = sequencer != null
            ? circuit.conduit(conduitName, composer, sequencer)
            : circuit.conduit(conduitName, composer);

        isNewConduit[0] = true;
        return newConduit;
    });

    // Emit Conduit's Source on first creation
    if (isNewConduit[0]) {
        Capture<Source<E>> capture = new CaptureImpl<>(
            conduit.subject(),
            conduit.source()
        );
        containerSource.emissionHandler().accept(capture);
    }

    return conduit;  // Conduit implements Pool<P>
}
```

**Hierarchical Subscription Pattern:**

Container enables hierarchical subscription - it emits Conduit Sources when new Conduits are created:

```
Subscribe to Container
  ↓
Container.source() returns Source<Source<E>>
  ↓
First container.get("AAPL") creates Conduit
  ↓
Container emits Capture(conduit.subject(), conduit.source())
  ↓
Subscriber receives conduit.source()
  ↓
Subscriber can subscribe to Conduit's emissions
```

**Usage Example:**
```java
// Create container
Container<Pool<Pipe<Order>>, Source<Order>> stockOrders = circuit.container(
    cortex.name("stock-market"),
    Composer.pipe()
);

// Subscribe to Container - receives Conduit Sources
stockOrders.source().subscribe(
    cortex.subscriber(
        cortex.name("stock-observer"),
        (conduitSubject, registrar) -> {
            System.out.println("New stock: " + conduitSubject.name());

            // Register to receive Conduit's Source
            registrar.register(conduitSource -> {
                // Subscribe to this stock's orders
                conduitSource.subscribe(orderSubscriber);
            });
        }
    )
);

// First access creates Conduit + emits
Pool<Pipe<Order>> applePool = stockOrders.get(cortex.name("AAPL"));
// ← stock-observer receives AAPL Conduit's Source

// Second access returns cached Conduit (no emission)
Pool<Pipe<Order>> applePool2 = stockOrders.get(cortex.name("AAPL"));
// ← No emission (cached)
```

**When to Use Container:**
- Dynamic collections of similar entities (stocks, devices, users, etc.)
- Need to observe creation of new entity types
- All entities share same processing logic
- Want automatic Conduit creation on-demand

**Implementation Notes:**
- Container does NOT cache Conduits by Circuit - it creates them via circuit.conduit()
- Circuit's Conduit cache handles singleton behavior per Circuit
- Container's conduits map is purely for emission tracking (first access vs subsequent)
- Conduits created with hierarchical names for proper Subject hierarchy

### Source

**Role:** Observable context that Subscribers can subscribe to for dynamic observation of Subjects/Channels.

**Characteristics:**
- Obtained from Context (Conduit provides Source via `source()`)
- Provides `subscribe(Subscriber)` method for dynamic observation
- Notifies subscribers when Subjects emit
- Returns Subscription for lifecycle control
- Enables conditional Pipe registration based on Subject characteristics

**Implementation (SourceImpl):**
- Implements `Source` interface for subscription management
- Manages subscribers with thread-safe CopyOnWriteArrayList
- Caches registered Pipes per Channel Subject per Subscriber
- Provides emission handler callback for sibling Channels to dispatch emissions
- Acts as observable context coordinating emission to all registered consumer Pipes

### Subscriber

**Role:** Connects consumer Pipes to a Source.

**Signature:**
```java
interface Subscriber<E> extends BiConsumer<Subject, Registrar<E>>, Substrate {
    void accept(Subject subject, Registrar<E> registrar);
}
```

**Usage Pattern:**
```java
source.subscribe(
    cortex.subscriber(
        cortex.name("my-subscriber"),
        (subject, registrar) -> {
            // Register consumer Pipe
            registrar.register(emission -> {
                // Process emission
            });
        }
    )
);
```

**Key Points:**
- Called for each emission
- Registers Pipes via Registrar (@Temporal - not retained)
- Can conditionally register based on Subject

### Sequencer & Segment

**Role:** Define transformation pipelines for emissions.

**Transformations:**
- `guard(Predicate)` - Filter emissions
- `limit(long)` - Maximum emission count
- `reduce(initial, BinaryOperator)` - Stateful aggregation
- `replace(UnaryOperator)` - Value transformation
- `diff()` - Change detection
- `sample(int)` - Sampling rate
- `sift(Comparator, Sequencer<Sift>)` - Complex filtering

**Usage:**
```java
circuit.conduit(
    name,
    Composer.pipe(segment -> segment
        .guard(n -> n > 0)
        .limit(100)
        .sample(10)
    )
);
```

**Implementation:**
- Segment is **mutable** (returns `this` for fluent chaining)
- Required because `Sequencer.apply()` is `void`
- Transformations executed during `Pipe.emit()`

### Sift

**Role:** Comparator-based filtering for Segment transformations.

**Operations:**
- `above(E)` - Values above threshold (exclusive)
- `below(E)` - Values below threshold (exclusive)
- `min(E)` - Minimum value (inclusive)
- `max(E)` - Maximum value (inclusive)
- `range(E, E)` - Range (inclusive)
- `high()` - New high values
- `low()` - New low values

**Usage:**
```java
segment.sift(
    Integer::compareTo,
    sift -> sift.above(0).max(100)
)
```

**Implementation:**
- `@Temporal` - Not retained after configuration
- Used only during `Sequencer.apply()` callback
- Predicate logic captured, not Sift object itself

### Clock

**Role:** Timer utility for time-driven behaviors.

**Cycles:**
- `MILLISECOND` - 1ms
- `SECOND` - 1000ms
- `MINUTE` - 60,000ms

**Usage:**
```java
Clock clock = circuit.clock(cortex.name("timer"));
clock.consume(
    cortex.name("handler"),
    Clock.Cycle.SECOND,
    instant -> System.out.println("Tick: " + instant)
);
```

**Implementation:**
- Uses `ScheduledExecutorService` with virtual threads
- Emits `Instant` on each cycle
- Managed by Circuit lifecycle

### Scope

**Role:** Hierarchical resource lifecycle management.

**Features:**
- `register(Resource)` - Register for lifecycle management
- `closure(Resource)` - ARM (Automatic Resource Management) pattern
- `scope(Name)` - Create named child scope
- Hierarchical navigation via `enclosure()` (Extent interface)

**Usage:**
```java
Scope scope = cortex.scope(cortex.name("transaction"));

// Register resources
Circuit circuit = scope.register(cortex.circuit());

// Use closure for automatic cleanup
scope.closure(circuit).consume(c -> {
    // Use circuit
    // Automatically closed when block exits
});

// Or close scope to cleanup all
scope.close();
```

**Implementation:**
- Implements `Extent<Scope>` for hierarchical navigation
- Closes child scopes before resources
- Thread-safe resource management

## Data Flow

### Complete Emission Flow

```
1. Producer Side
   ┌──────────────────────────────────────┐
   │ conduit.get(name)                    │
   │   → Creates/retrieves Channel        │
   │   → Composer creates Percept         │
   │   → Returns Percept (e.g., Pipe)     │
   └──────────────────────────────────────┘
                    ↓
   ┌──────────────────────────────────────┐
   │ pipe.emit(value)                     │
   │   → channel.pipe().emit(value)       │
   │   → PipeImpl applies transformations │
   │   → Early exit if no subscribers     │
   │   → Synchronous emission handler     │
   └──────────────────────────────────────┘

2. Source Dispatch (Synchronous)
   ┌──────────────────────────────────────┐
   │ Source.emissionHandler()             │
   │   → Checks hasSubscribers() (fast)   │
   │   → Creates Capture(subject, value)  │
   │   → Iterates all Subscribers         │
   │   → For each Subscriber:             │
   │     - Lazy pipe registration         │
   │       (first emission per Subject)   │
   │     - Pipes cached for reuse         │
   │     - Emit to each consumer Pipe     │
   └──────────────────────────────────────┘

3. Consumer Side (Synchronous)
   ┌──────────────────────────────────────┐
   │ subscriber.accept(subject, registrar)│
   │   → Called on FIRST emission         │
   │   → registrar.register(consumerPipe) │
   │   → Pipes cached per Subject         │
   │                                      │
   │ cachedPipe.emit(emission)            │
   │   → Subsequent emissions use cache   │
   │   → Consumer processes emission      │
   └──────────────────────────────────────┘
```

**Performance:** All operations are synchronous for minimal latency with identity map fast path:
- Pipe emission: ~3.3ns (hot path with identity map + early subscriber check)
- Cached pipe lookup: ~4ns (identity map fast path)
- Full path (lookup + emit): ~101ns
- Multi-threaded (4 threads): ~27ns per thread

**Performance Improvement from LazyTrieRegistry Integration:**
- Hot-path emission: 6.6ns → 3.3ns (2× faster)
- Cached lookups: 23-28ns → 4-5ns (5× faster via identity map)

See [Performance Guide](PERFORMANCE.md) for comprehensive benchmarks and analysis.

### Transformation Flow (with Sequencer)

```
Conduit creation with Sequencer:
  circuit.conduit(name, Composer.pipe(segment -> segment...))
    ↓
  Sequencer.apply(segment)
    [User configures: segment.guard(...).limit(...).sample(...)]
    ↓
  Conduit stores Sequencer, passed to all Channels

Channel.pipe() execution:
  ↓
PipeImpl created with SegmentImpl from Conduit's Sequencer
  ↓
PipeImpl.emit(value)
  ↓
segment.apply(value)
  [Applies all transformations in order]
  ↓
If value passes filters → Create Capture → Synchronous emission
If value filtered out → Early return (no allocation)
```

**Transformation Performance:** ~15ns overhead for 3-stage pipeline (filter + map + limit), or ~5ns per transformation stage.

## Design Principles

### 1. Name-Based Component Caching with Factory Injection

**Pattern:** Components are cached by Name using `Map.computeIfAbsent()` with pluggable RegistryFactory.

**Factory Architecture:**
```
CortexRuntime (Entry Point)
    ├── NameFactory (creates interned Names)
    ├── QueueFactory (creates Queues)
    ├── RegistryFactory (creates LazyTrieRegistry)  ← NEW: Pluggable registry
    │
    └── Propagates factories down the hierarchy
            ├── Circuit (receives all 3 factories)
            ├── Conduit (receives registryFactory)
            └── Scope (receives registryFactory)
```

**Component Caching with LazyTrieRegistry:**
```
CortexRuntime (Global)
    ├── circuits: LazyTrieRegistry<Circuit>     ← Level 1: Circuit cache with identity map
    ├── scopes: LazyTrieRegistry<Scope>         ← Level 1: Scope cache with identity map
    │
    └── Each Circuit contains:
            ├── clocks: LazyTrieRegistry<Clock>      ← Level 2: Clock cache with identity map
            └── conduits: LazyTrieRegistry<Conduit>  ← Level 2: Conduit cache with identity map
```

**Level 1 - Cortex caches Circuits and Scopes:**
```java
// CortexRuntime.java
@SuppressWarnings("unchecked")
public CortexRuntime(NameFactory nameFactory, QueueFactory queueFactory, RegistryFactory registryFactory) {
    this.nameFactory = nameFactory;
    this.queueFactory = queueFactory;
    this.registryFactory = registryFactory;

    // LazyTrieRegistry provides identity map fast path
    this.circuits = (Map<Name, Circuit>) registryFactory.create();
    this.scopes = (Map<Name, Scope>) registryFactory.create();

    Name cortexName = nameFactory.createRoot("cortex");
    this.defaultScope = new ScopeImpl(cortexName, registryFactory);
}

public Circuit circuit(Name name) {
    return circuits.computeIfAbsent(name, this::createCircuit);  // Identity map: 2-5ns
}

public Circuit circuit() {
    return circuit(nameFactory.createRoot("circuit"));  // Type-based default name
}
```

**Level 2 - Circuit caches Clocks and Conduits:**
```java
// CircuitImpl.java
@SuppressWarnings("unchecked")
public CircuitImpl(Name name, NameFactory nameFactory, QueueFactory queueFactory, RegistryFactory registryFactory) {
    this.nameFactory = nameFactory;
    this.queueFactory = queueFactory;
    this.registryFactory = registryFactory;

    // LazyTrieRegistry with identity map fast path
    this.clocks = (Map<Name, Clock>) registryFactory.create();
    this.conduits = (Map<ConduitKey, Conduit<?, ?>>) registryFactory.create();
}

// Composite key for Conduit caching (Name + Composer type)
private record ConduitKey(Name name, Class<?> composerClass) {}

public Clock clock(Name name) {
    return clocks.computeIfAbsent(name, ClockImpl::new);  // Identity map fast path
}

public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer) {
    // Build hierarchical name: circuit.conduit
    Name hierarchicalName = circuitSubject.name().name(name);

    ConduitKey key = new ConduitKey(name, composer.getClass());
    return conduits.computeIfAbsent(key, k ->
        new ConduitImpl<>(hierarchicalName, composer, queue, registryFactory, sequencer)
    );
}
```

**Identity Map Fast Path Performance:**

The combination of **InternedName** (via NameFactory) + **LazyTrieRegistry** (via RegistryFactory) provides dramatic performance improvement:

```java
// InternedName ensures pointer equality
Name name1 = nameFactory.createRoot("kafka.broker.1");
Name name2 = nameFactory.createRoot("kafka.broker.1");
assert name1 == name2;  // ✅ Same instance (interned)

// LazyTrieRegistry uses identity map for O(1) lookup
Circuit c = circuits.computeIfAbsent(name1, this::createCircuit);
// → identityMap.get(name1)  // 2ns via pointer equality (==)
// → Fallback to hash map if identity miss: 15-20ns
```

**Performance Results:**
- Cached circuit lookups: 28ns → 5ns (5× faster)
- Cached pipe lookups: 23ns → 4ns (5× faster)
- Hot-path emission: 6.6ns → 3.3ns (2× faster)

**Name = External Cache Key, Id = Component Identity:**

**Key Design Principle:** Name is **external infrastructure**, not a component property:

1. **`Name`** - External key in the registry/namespace
   - Name is the KEY, component is the VALUE in `Map<Name, Component>`
   - Component doesn't store its own Name - Name identifies the component externally
   - Used as cache key in `Map.computeIfAbsent(name, ...)`
   - Type-based default names like `"circuit"`, `"conduit"`, `"clock"` create singletons
   - Custom names allow multiple instances per Circuit
   - **Conduits keyed by (Name, Composer type)** - different Composers create different Conduits

2. **`Id`** - Component's actual identity
   - Each component instance gets a unique UUID
   - Generated once during construction
   - Stored in component's Subject for instance tracking
   - This is the component's **internal identity**, not the external registry key

**Example:**
```java
Cortex cortex = new CortexRuntime();

// Same name → Same Circuit instance
Circuit c1 = cortex.circuit(cortex.name("kafka-cluster"));
Circuit c2 = cortex.circuit(cortex.name("kafka-cluster"));
assert c1 == c2;  // ✅ Cached by Name

// But each has unique ID
assert !c1.subject().id().equals(c2.subject().id());  // ❌ Would fail - same instance!

// Different Circuit instance
Circuit c3 = cortex.circuit(cortex.name("other-cluster"));
assert c1 != c3;  // ✅ Different Name = Different instance
assert !c1.subject().id().equals(c3.subject().id());  // ✅ Different IDs
```

**Default Names Pattern:**

No-arg factory methods use type-based default names for singleton behavior:

```java
// Uses type-based default names internally
Circuit c1 = cortex.circuit();   // circuit(NameImpl.of("circuit"))
Circuit c2 = cortex.circuit();   // Returns same instance

Clock clk1 = circuit.clock();    // clock(NameImpl.of("clock"))
Clock clk2 = circuit.clock();    // Returns same instance

// Conduits cached by BOTH name AND composer type
Conduit cd1 = circuit.conduit(Composer.pipe());     // conduit(NameImpl.of("conduit"), Composer.pipe())
Conduit cd2 = circuit.conduit(Composer.pipe());     // Returns same instance (same name + same composer)

Conduit cd3 = circuit.conduit(Composer.channel());  // conduit(NameImpl.of("conduit"), Composer.channel())
// cd3 is DIFFERENT from cd1/cd2 - different Composer type!
```

**Factory Method + Flyweight Pattern:**

The caching architecture uses two distinct patterns:

1. **Constructor = Factory Method** (always creates new instance)
   - ClockImpl, ConduitImpl constructors generate new Id each time
   - Constructors don't handle caching logic
   - Pure factory - create instance, initialize state

2. **Circuit/Cortex methods = Flyweight Pool** (manages caching)
   - `circuit.clock(name)` checks cache first via `computeIfAbsent()`
   - Only calls constructor if Name not in cache
   - Manages singleton lifecycle

**Why constructors generate new Ids:**

```java
// ClockImpl constructor - called ONLY by cache miss
public ClockImpl(Name name) {
    Id id = IdImpl.generate();  // New ID - but only called once per Name!
    this.clockSubject = new SubjectImpl(id, name, ...);
}

// CircuitImpl.clock() - manages caching
public Clock clock(Name name) {
    return clocks.computeIfAbsent(name, ClockImpl::new);
    //                             ^^^^ Constructor called once per Name
}
```

**First call:** Cache miss → calls `ClockImpl::new` → generates new Id → stores in cache
**Second call:** Cache hit → returns existing Clock → no new Id generated

**CRITICAL: Always use Circuit/Cortex factory methods, not direct construction:**

```java
// ✅ Correct - uses cache
Clock clock1 = circuit.clock(name);
Clock clock2 = circuit.clock(name);
assert clock1 == clock2;  // Same instance

// ❌ WRONG - bypasses cache
Clock clock3 = new ClockImpl(name);  // Different instance!
Clock clock4 = new ClockImpl(name);  // Another different instance!
assert clock3 != clock4;  // Breaks singleton pattern
```

**The `computeIfAbsent()` guarantee:**

```java
// CircuitImpl.java line 87
return clocks.computeIfAbsent(name, ClockImpl::new);
//                                   ^^^^^^^^^^^^^^
//                                   Only called if Name absent from map
//                                   Otherwise returns cached Clock
```

This pattern ensures:
- Same Name → Same instance (singleton per Name)
- Different Name → Different instance
- Constructor called exactly once per Name
- Thread-safe atomic cache lookup/creation

### 2. Interface Types Over Implementation Types

All fields use interface types, not implementations:

```java
// Good
private final Source<E> source;
private final Pipe<E> emitter;

// Avoid
private final SourceImpl<E> source;
```


### 3. @Temporal Types Are Not Retained

Types marked `@Temporal` are transient and should not be stored:

- **Registrar** - Created inline during emission, not stored
- **Sift** - Used only during Sequencer.apply(), predicate captured
- **Closure** - Used for ARM pattern, executes and closes

### 4. Virtual Threads Are Daemon Threads

- Queue processors use daemon virtual threads
- Auto-cleanup on JVM shutdown
- No explicit shutdown needed in most cases
- Circuit.close() interrupts threads for clean shutdown

### 5. Component Extends Resource

All major components implement Resource interface:
- Circuit extends Component extends Resource
- Clock extends Component extends Resource
- Container extends Component extends Resource

All have lifecycle management via `close()`.

### 6. Precise Ordering Guarantees

Circuit provides precise ordering for emitted events:
- Single queue per Conduit
- Sequential processing by queue processor
- FIFO ordering maintained

### 7. Immutable State with Override Pattern

State is immutable - each `state()` call returns a **NEW** State instance:

```java
State s1 = cortex.state();
State s2 = s1.state(name1, value1);  // s2 is NEW State
State s3 = s2.state(name2, value2);  // s3 is NEW State

assert s1 != s2 != s3;  // All different objects
```

**Override Pattern:** State uses a List internally, allowing duplicates:

```java
State config = cortex.state()
    .state(name("timeout"), 30)    // Default
    .state(name("timeout"), 60)    // Override (both exist!)
    .compact();                    // Remove duplicates (keeps last: 60)
```

This enables configuration layering (defaults → environment → user overrides).

### 8. Segment Mutability

Segment is mutable (returns `this`) because:
- `Sequencer.apply(Segment)` is `void`
- Must mutate same object during configuration
- Fluent API still works: `segment.guard(...).limit(...)`

## Threading Model

### Virtual Threads

All background processing uses virtual threads:

```java
Thread processor = Thread.ofVirtual()
    .name("conduit-" + name)
    .start(() -> {
        // Processing loop
    });
```

**Benefits:**
- Lightweight (millions of virtual threads possible)
- Daemon by default (auto-cleanup)
- Structured concurrency support

### Queue Processing

Each Conduit has its own queue processor:

```java
while (!Thread.currentThread().isInterrupted()) {
    E emission = queue.take(); // Blocking
    processEmission(emission);
}
```

**Characteristics:**
- Blocking take (yields virtual thread)
- Sequential processing (ordering guarantee)
- Exception handling (log and continue)

### Thread Safety

- **LazyTrieRegistry** (implements Map) for component caches - dual-index with ConcurrentHashMap + IdentityHashMap
- **CopyOnWriteArrayList** for subscriber lists
- **BlockingQueue** for emission queuing
- **volatile** for closed flags and trie root

**Registry Thread Safety:**
```java
// LazyTrieRegistry uses thread-safe structures
private final Map<Name, T> identityMap = new IdentityHashMap<>();  // Synchronized access
private final Map<Name, T> registry = new ConcurrentHashMap<>();   // Concurrent access
private volatile TrieNode<T> trieRoot = null;                       // Volatile for lazy construction
```

## Resource Lifecycle

### Component Hierarchy

```
Resource (interface)
  ├── Component (abstract)
  │     ├── Circuit
  │     ├── Clock
  │     └── Container
  ├── Sink
  └── Subscription
```

### Lifecycle Management

**Circuit:**
```java
Circuit circuit = cortex.circuit();
// Use circuit
circuit.close();
// → Closes all clocks
// → Clears conduits
// → Virtual threads auto-cleanup
```

**Scope:**
```java
Scope scope = cortex.scope();
Circuit circuit = scope.register(cortex.circuit());
Clock clock = scope.register(circuit.clock());

scope.close();
// → Closes all child scopes
// → Closes all registered resources
```

### Subscription Lifecycle

```java
Subscription sub = source.subscribe(subscriber);
// Receive emissions
sub.close();
// → Removes subscriber from source
// → No more emissions received
```

## Performance Considerations

### Emission Performance

Fullerstack implementation benchmarks (with identity map optimization):
- **Hot-path emission:** 3.3ns (cached pipe, early subscriber check)
- **Cached pipe lookup:** 4ns (identity map fast path)
- **Full path (lookup + emit):** 101ns (includes circuit/conduit/channel traversal)
- **Multi-threaded (4 threads):** ~27ns per thread (concurrent emissions)

See [Performance Guide](PERFORMANCE.md) for comprehensive benchmarks and methodology.

### Queue Sizing

Default queue capacity: 10,000
```java
private final BlockingQueue<E> queue = new LinkedBlockingQueue<>(10000);
```

**Tuning:**
- Increase for bursty workloads
- Decrease for memory constraints
- Monitor queue.size() for backpressure

### Transformation Overhead

Transformations execute inline during emit():
- guard: Predicate test
- limit: Counter check
- sample: Modulo calculation
- reduce: Binary operation
- sift: Comparator test

Keep transformation chains short for best performance.

## Best Practices

1. **Use try-with-resources for Scope:**
   ```java
   try (Scope scope = cortex.scope()) {
       // Use scope
   } // Auto-closes
   ```

2. **Close Subscriptions when done:**
   ```java
   Subscription sub = source.subscribe(subscriber);
   try {
       // Use subscription
   } finally {
       sub.close();
   }
   ```

3. **Reuse Circuits and Conduits:**
   ```java
   // Good: Circuit caches by name
   Conduit<Pipe<String>, String> conduit = circuit.conduit(name, composer);

   // Calling again with same name returns cached instance
   Conduit<Pipe<String>, String> same = circuit.conduit(name, composer);
   ```

4. **Use Composer factory methods:**
   ```java
   Composer.pipe()           // For Pipe<E>
   Composer.channel()        // For Channel<E>
   Composer.pipe(sequencer)  // With transformations
   ```

5. **Monitor Queue depth:**
   ```java
   // In production, monitor for backpressure
   int queueSize = ((LinkedBlockingQueue<?>) conduitQueue).size();
   if (queueSize > threshold) {
       // Handle backpressure
   }
   ```

## References

- [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)
- [Observability X Blog Series](https://humainary.io/blog/category/observability-x/)
- [William Louth on Semiotic Observability](https://humainary.io)
