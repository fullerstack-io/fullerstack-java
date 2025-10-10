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

Substrates implements an event-driven architecture for observability, based on William Louth's vision of **semiotic observability**. The system routes emissions from producers (Channels) to consumers (Subscriber Pipes) through a central processing engine (Circuit).

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Circuit                                 │
│  (Central Processing Engine - Precise Ordering Guarantees)      │
│                                                                 │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                    Conduit                                  │ │
│  │                                                             │ │
│  │  Channel 1 ──┐                        ┌── Subscriber 1     │ │
│  │  Channel 2 ──┤→ Queue → Processor → Source ── Subscriber 2 │ │
│  │  Channel 3 ──┘                        └── Subscriber 3     │ │
│  │                                                             │ │
│  │  (Percepts created via Composer)                           │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                     │
│  │  Clock   │  │  Clock   │  │ Container│                     │
│  └──────────┘  └──────────┘  └──────────┘                     │
└─────────────────────────────────────────────────────────────────┘
```

## Core Components

### Circuit

**Role:** Central processing engine that coordinates all components.

**Responsibilities:**
- Manages Conduits, Clocks, Containers, and Queue
- Provides precise ordering guarantees for emitted events
- Coordinates resource lifecycle
- Caches components by name for reuse

**Key Methods:**
- `conduit(Name, Composer)` - Creates/retrieves Conduit
- `clock(Name)` - Creates/retrieves Clock
- `container(Name, Composer)` - Creates Container
- `queue()` - Returns coordination Queue
- `close()` - Closes all managed resources

**Implementation Details:**
- Uses `ConcurrentHashMap` for thread-safe component caching
- Implements lazy initialization
- Daemon virtual threads auto-cleanup on JVM shutdown

### Conduit

**Role:** Routes emissions from Channels (producers) to Pipes (consumers).

**Data Flow:**
```
Channel.emit(value)
  ↓
Conduit's BlockingQueue
  ↓
Queue Processor Thread (daemon virtual thread)
  ↓
processEmission(value)
  ↓
Source.emit(value) [via Pipe interface]
  ↓
All Subscribers notified
  ↓
Subscriber registers consumer Pipes
  ↓
Consumer Pipes receive emission
```

**Key Components:**
- **Composer** - Transforms Channel into Percept (e.g., Pipe, custom domain object)
- **BlockingQueue** - Shared queue (default 10,000 capacity)
- **Source** - Observable stream for subscriptions
- **Queue Processor** - Background thread processing emissions

**Implementation Details:**
- Percepts cached by Name in `ConcurrentHashMap`
- Queue processor is daemon virtual thread
- Source is also a Pipe (dual interface pattern)

### Channel

**Role:** Entry point where producers emit data into a Conduit.

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

### Source

**Role:** Observable event stream that Subscribers can subscribe to.

**Characteristics:**
- Provides `subscribe(Subscriber)` method
- Notifies subscribers when emissions occur
- Returns Subscription for lifecycle control

**Implementation (SourceImpl):**
- Implements both `Source` and `Pipe` interfaces
- Source interface: external code subscribes
- Pipe interface: Conduit emits to it
- Acts as event dispatcher

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
1. Producer Side (Channel)
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
   │   → PipeImpl checks transformations  │
   │   → Puts value on Conduit's queue    │
   └──────────────────────────────────────┘

2. Conduit Processing (Async)
   ┌──────────────────────────────────────┐
   │ Queue Processor Thread               │
   │   → queue.take() (blocking)          │
   │   → processEmission(value)           │
   │   → emitter.emit(value)              │
   │      [SourceImpl via Pipe interface] │
   └──────────────────────────────────────┘

3. Source Dispatch
   ┌──────────────────────────────────────┐
   │ SourceImpl.emit(emission)            │
   │   → Iterate all Subscribers          │
   │   → For each Subscriber:             │
   │     - Create Registrar               │
   │     - subscriber.accept(subject, reg)│
   │     - Collect registered Pipes       │
   │     - Emit to each consumer Pipe     │
   └──────────────────────────────────────┘

4. Consumer Side
   ┌──────────────────────────────────────┐
   │ subscriber.accept(subject, registrar)│
   │   → registrar.register(consumerPipe) │
   │   → consumerPipe.emit(emission)      │
   │   → Consumer processes emission      │
   └──────────────────────────────────────┘
```

### Transformation Flow (with Sequencer)

```
Channel.pipe(sequencer)
  ↓
Sequencer.apply(segment)
  [User configures: segment.guard(...).limit(...).sample(...)]
  ↓
PipeImpl created with SegmentImpl
  ↓
PipeImpl.emit(value)
  ↓
segment.apply(value)
  [Applies all transformations in order]
  ↓
Transformed value → queue (or filtered/limited)
```

## Design Principles

### 1. Interface Types Over Implementation Types

All fields use interface types, not implementations:

```java
// Good
private final Source<E> source;
private final Pipe<E> emitter;

// Avoid
private final SourceImpl<E> source;
```

**Rationale:** Aligns with William Louth's precise architectural vision, enables flexibility.

### 2. @Temporal Types Are Not Retained

Types marked `@Temporal` are transient and should not be stored:

- **Registrar** - Created inline during emission, not stored
- **Sift** - Used only during Sequencer.apply(), predicate captured
- **Closure** - Used for ARM pattern, executes and closes

### 3. Virtual Threads Are Daemon Threads

- Queue processors use daemon virtual threads
- Auto-cleanup on JVM shutdown
- No explicit shutdown needed in most cases
- Circuit.close() interrupts threads for clean shutdown

### 4. Component Extends Resource

All major components implement Resource interface:
- Circuit extends Component extends Resource
- Clock extends Component extends Resource
- Container extends Component extends Resource

All have lifecycle management via `close()`.

### 5. Precise Ordering Guarantees

Circuit provides precise ordering for emitted events:
- Single queue per Conduit
- Sequential processing by queue processor
- FIFO ordering maintained

### 6. Immutable State Pattern

State is immutable:
```java
State state = cortex.state()
    .state(name1, value1)
    .state(name2, value2)
    .compact();
```

Each `state()` call returns new State instance.

### 7. Segment Mutability

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

- **ConcurrentHashMap** for component caches
- **CopyOnWriteArrayList** for subscriber lists
- **BlockingQueue** for emission queuing
- **volatile** for closed flags

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

### Subscriber Performance

From William Louth's blog:
- 29 ns per leaf emit call (Mac M4)
- 6 ns per Pipe emit
- Efficient multi-dispatch

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
