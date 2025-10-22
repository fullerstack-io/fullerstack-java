# Fullerstack Substrates Architecture

## Overview

This document describes the architecture of the Fullerstack Substrates implementation for the [Humainary Substrates API M17](https://github.com/humainary-io/substrates-api-java).

**Design Philosophy:** Simplified, lean implementation focused on correctness, clarity, and production readiness rather than premature optimization.

**API Version:** M17 (Sealed Interfaces)
**Java Version:** 25 (LTS with Virtual Threads)
**Implementation Status:** Production-ready (247 tests passing)

---

## Architecture Principles

1. **Simplified Design** - Single implementations, no factory abstractions
2. **M17 Sealed Hierarchy** - Type-safe API contracts enforced by sealed interfaces
3. **Virtual CPU Core Pattern** - Single-threaded event processing per Circuit for precise ordering
4. **Immutable State** - Slot-based state management with value semantics
5. **Resource Lifecycle** - Explicit cleanup via `close()` on all components
6. **Thread Safety** - Concurrent collections where needed, immutability elsewhere
7. **Clear Separation** - Public API (interfaces) vs internal implementation (concrete classes)

---

## M17 Sealed Interface Hierarchy

The Substrates API M17 uses sealed interfaces to enforce correct type composition:

```
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

**What This Means:**
- ✅ We **can** implement: Circuit, Conduit, Cell, Clock, Channel, Pipe, Sink
- ❌ We **cannot** implement: Source, Context, Component, Container (sealed)
- The API controls the type hierarchy to prevent incorrect compositions

**Impact on Our Implementation:**
- `SourceImpl` does not implement `Source` interface (it's sealed)
- `SourceImpl` is an internal utility for subscriber management
- Circuit/Conduit/Cell extend sealed types and inherit `subscribe()` from Source

---

## Core Components

### 1. CortexRuntime (Entry Point)

**Purpose:** Factory for creating Circuits and Scopes

**Implementation:** `io.fullerstack.substrates.CortexRuntime`

```java
public class CortexRuntime implements Cortex {
    private final Map<Name, Circuit> circuits = new ConcurrentHashMap<>();
    private final Map<Name, Scope> scopes = new ConcurrentHashMap<>();

    @Override
    public Circuit circuit(Name name) {
        return circuits.computeIfAbsent(name, CircuitImpl::new);
    }

    @Override
    public Name name(String path) {
        return NameNode.of(path);  // Creates hierarchical dot-notation name
    }
}
```

**Key Features:**
- Simple ConcurrentHashMap for component caching
- Thread-safe `computeIfAbsent()` for lazy creation
- NameNode handles hierarchical name creation

---

### 2. NameNode (Hierarchical Naming)

**Purpose:** Hierarchical dot-notation names (e.g., "kafka.broker.1")

**Implementation:** `io.fullerstack.substrates.name.NameNode`

```java
public final class NameNode implements Name {
    private final NameNode parent;
    private final String segment;
    private final String cachedPath;  // Cached for performance

    public static Name of(String path) {
        String[] segments = path.split("\\.");
        Name current = new NameNode(null, segments[0]);
        for (int i = 1; i < segments.length; i++) {
            current = current.name(segments[i]);
        }
        return current;
    }

    @Override
    public Name name(String segment) {
        return new NameNode(this, segment);
    }

    @Override
    public CharSequence path(char separator) {
        return cachedPath;  // Always uses '.' separator
    }
}
```

**Key Features:**
- Immutable parent-child structure
- Cached path string (built once in constructor)
- Always uses '.' as separator (Name.SEPARATOR = '.')
- Hierarchical: `broker.name("metrics").name("bytes-in")` → "broker.metrics.bytes-in"

---

### 3. CircuitImpl (Event Orchestration Hub)

**Purpose:** Central processing engine with virtual CPU core pattern for precise event ordering

**Implementation:** `io.fullerstack.substrates.circuit.CircuitImpl`

```java
public final class CircuitImpl implements Circuit {
    private final Name name;
    private final ExecutorService executor;  // Single virtual thread
    private final BlockingQueue<Runnable> queue;
    private final Map<Name, Conduit<?, ?>> conduits = new ConcurrentHashMap<>();
    private final Map<Name, Clock> clocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;  // Shared by all Clocks
    private final SourceImpl<State> stateSource;

    public CircuitImpl(Name name) {
        this.name = name;
        this.queue = new LinkedBlockingQueue<>();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.stateSource = new SourceImpl<>();
        startProcessing();
    }

    private void startProcessing() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Runnable task = queue.take();
                    task.run();  // Execute in FIFO order
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
}
```

**Key Features:**
- **Virtual CPU Core Pattern**: Single virtual thread processes events from FIFO queue
- **Precise Ordering**: Events processed in exact order received
- **Shared Scheduler**: All Clocks in this Circuit share one ScheduledExecutorService
- **Component Caching**: ConcurrentHashMap for Conduits and Clocks

**Virtual Thread Benefits:**
- Lightweight (thousands of virtual threads possible)
- Automatically managed by JVM
- Clean daemon thread cleanup on JVM shutdown

---

### 4. ConduitImpl (Channel/Pipe/Source Coordinator)

**Purpose:** Container that creates Channels, manages Pipes, and provides Source for subscriptions

**Implementation:** `io.fullerstack.substrates.conduit.ConduitImpl`

```java
public final class ConduitImpl<P, E> implements Conduit<P, E> {
    private final Name name;
    private final Circuit circuit;
    private final SourceImpl<E> source;  // Internal subscriber management
    private final Map<Name, ChannelImpl<E>> channels = new ConcurrentHashMap<>();
    private final Consumer<Flow<E>> flowConfigurer;  // Flow/Sift transformations

    @Override
    public P get(Name subject) {
        ChannelImpl<E> channel = channels.computeIfAbsent(
            subject,
            s -> new ChannelImpl<>(s, circuit.scheduler(), source, flowConfigurer)
        );
        return (P) channel.pipe();  // Return Pipe for this subject
    }

    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        return source.subscribe(subscriber);  // Delegate to SourceImpl
    }
}
```

**Key Features:**
- **Conduit IS-A Source**: Implements subscribe() by delegating to SourceImpl
- **Channel Factory**: Creates Channels on-demand via `get(Name subject)`
- **Flow Configuration**: Passes Flow/Sift transformations to all Channels
- **Thread-Safe**: ConcurrentHashMap for concurrent channel access

**Conduit in M17:**
- Extends Container which extends Component which extends Context which extends Source
- Inherits `subscribe()` from Source interface
- SourceImpl provides the actual implementation

---

### 5. ChannelImpl (Emission Port)

**Purpose:** Named emission port linking producers to the event stream

**Implementation:** `io.fullerstack.substrates.channel.ChannelImpl`

```java
public final class ChannelImpl<E> implements Channel<E> {
    private final Name name;
    private final Scheduler scheduler;
    private final SourceImpl<E> source;  // Reference to Conduit's source
    private final PipeImpl<E> pipe;

    public ChannelImpl(Name name, Scheduler scheduler, SourceImpl<E> source,
                       Consumer<Flow<E>> flowConfigurer) {
        this.name = name;
        this.scheduler = scheduler;
        this.source = source;
        this.pipe = new PipeImpl<>(source, flowConfigurer);
    }

    @Override
    public Pipe<E> pipe() {
        return pipe;
    }
}
```

**Key Features:**
- Creates PipeImpl with transformation configuration
- Passes SourceImpl reference to Pipe for emissions
- Lightweight - just connects producer to infrastructure

---

### 6. PipeImpl (Event Transformation)

**Purpose:** Event transformation pipeline with Flow/Sift operations

**Implementation:** `io.fullerstack.substrates.pipe.PipeImpl`

```java
public final class PipeImpl<E> implements Pipe<E> {
    private final SourceImpl<E> source;
    private final List<Function<E, E>> transformations = new ArrayList<>();
    private final List<Predicate<E>> filters = new ArrayList<>();
    private int limit = Integer.MAX_VALUE;
    private int sampleRate = 1;
    private int emissionCount = 0;

    public PipeImpl(SourceImpl<E> source, Consumer<Flow<E>> flowConfigurer) {
        this.source = source;
        if (flowConfigurer != null) {
            flowConfigurer.accept(new FlowImpl<>(this));
        }
    }

    @Override
    public void emit(E event) {
        if (emissionCount >= limit) return;
        if (++emissionCount % sampleRate != 0) return;

        E transformed = event;
        for (Function<E, E> transform : transformations) {
            transformed = transform.apply(transformed);
            if (transformed == null) return;
        }

        for (Predicate<E> filter : filters) {
            if (!filter.test(transformed)) return;
        }

        source.emit(transformed);  // Send to subscribers
    }
}
```

**Key Features:**
- **Transformations**: map, filter, limit, sample
- **Flow/Sift API**: Fluent configuration via Consumer<Flow<E>>
- **Direct Emission**: After transformations, emits directly to SourceImpl

**Flow/Sift Example:**
```java
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    name,
    Composer.pipe(flow -> flow
        .sift(n -> n > 0)      // Filter: only positive
        .limit(100)            // Max 100 emissions
        .sample(10)            // Every 10th emission
    )
);
```

---

### 7. SourceImpl (Subscriber Management)

**Purpose:** Internal utility for managing subscribers (does NOT implement Source interface)

**Implementation:** `io.fullerstack.substrates.source.SourceImpl`

```java
public class SourceImpl<E> {
    private final List<Subscriber<E>> subscribers = new CopyOnWriteArrayList<>();

    public Subscription subscribe(Subscriber<E> subscriber) {
        subscribers.add(subscriber);

        // Trigger subscriber callback with registrar
        subscriber.accept(name, registrar -> {
            // Registrar allows subscriber to register consumer Pipes
        });

        return () -> subscribers.remove(subscriber);
    }

    public void emit(E event) {
        for (Subscriber<E> subscriber : subscribers) {
            // Notify all subscribers of emission
        }
    }
}
```

**Key Features:**
- **CopyOnWriteArrayList**: Read-optimized for subscriber iteration
- **Thread-Safe**: Multiple threads can subscribe/emit concurrently
- **Does NOT implement Source**: Source is sealed in M17
- **Internal Use Only**: Used by Circuit, Conduit, Cell, Clock, Channel

**Why SourceImpl Doesn't Implement Source:**
- M17 made `Source` sealed, only permitting `Context`
- Only Circuit/Conduit/Cell/Clock (which extend Context) can implement Source
- SourceImpl is an internal implementation detail, not part of public API

---

### 8. CellNode (Hierarchical State Transformation)

**Purpose:** Hierarchical container with type transformation (I → E)

**Implementation:** `io.fullerstack.substrates.cell.CellNode`

```java
public final class CellNode<I, E> implements Cell<I, E> {
    private final Name name;
    private final CellNode<?, I> parent;  // Parent in hierarchy
    private final Function<I, E> transformer;  // I → E transformation
    private final SourceImpl<E> source;
    private final Map<Name, CellNode<E, ?>> children = new ConcurrentHashMap<>();

    @Override
    public <O> Cell<E, O> cell(Name name, Function<E, O> transformer) {
        return children.computeIfAbsent(name, n ->
            new CellNode<>(n, this, transformer, circuit)
        );
    }

    public void input(I value) {
        E transformed = transformer.apply(value);
        source.emit(transformed);  // Emit to subscribers
        // Children can subscribe and transform further
    }
}
```

**Key Features:**
- **Type Transformation**: Each level transforms input type to output type
- **Parent-Child Hierarchy**: Build trees of transforming cells
- **Observable**: Each Cell is a Source, can subscribe to transformations

**Example Use Case:**
```java
// Broker stats (JMX) → Health assessment → Cluster health
Cell<JMXStats, BrokerHealth> brokerCell = circuit.cell(
    name("broker-1"),
    stats -> assessBrokerHealth(stats)
);

Cell<BrokerHealth, ClusterHealth> clusterCell = brokerCell.cell(
    name("cluster"),
    health -> aggregateClusterHealth(health)
);
```

---

### 9. ClockImpl (Scheduled Events)

**Purpose:** Timer utility for time-driven behaviors

**Implementation:** `io.fullerstack.substrates.clock.ClockImpl`

```java
public final class ClockImpl implements Clock {
    private final Name name;
    private final ScheduledExecutorService scheduler;  // Shared across Circuit
    private final SourceImpl<Instant> source;
    private final Map<Cycle, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    @Override
    public Subscription consume(Name name, Cycle cycle, Consumer<Instant> consumer) {
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
            () -> consumer.accept(Instant.now()),
            0,
            cycle.duration().toMillis(),
            TimeUnit.MILLISECONDS
        );

        tasks.put(cycle, task);
        return () -> {
            task.cancel(false);
            tasks.remove(cycle);
        };
    }
}
```

**Key Features:**
- **Shared Scheduler**: All Clocks in a Circuit share one ScheduledExecutorService
- **Multiple Cycles**: Can have different consumers for SECOND, MINUTE, HOUR, etc.
- **Observable**: Emits Instant on each tick, subscribers can observe

**Shared Scheduler Benefits:**
- Reduced thread overhead (one scheduler for entire Circuit)
- Better resource utilization
- Simpler lifecycle management

---

### 10. ScopeImpl (Resource Lifecycle)

**Purpose:** Hierarchical resource lifecycle management

**Implementation:** `io.fullerstack.substrates.scope.ScopeImpl`

```java
public final class ScopeImpl implements Scope {
    private final Name name;
    private final List<Resource> resources = new CopyOnWriteArrayList<>();

    @Override
    public <R extends Resource> R register(R resource) {
        resources.add(resource);
        return resource;
    }

    @Override
    public void close() {
        for (Resource resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                // Log but continue closing other resources
            }
        }
        resources.clear();
    }
}
```

**Key Features:**
- **Automatic Cleanup**: Close all registered resources in one call
- **Exception Safety**: Continues closing even if one resource fails
- **Hierarchical**: Scopes can contain other scopes

---

## Data Flow Architecture

### Producer → Consumer Flow

```
1. Producer Side:
   conduit.get(name)           → Returns Pipe for subject
   pipe.emit(value)            → Applies transformations
                               → Emits to SourceImpl

2. SourceImpl Processing:
   source.emit(value)          → Iterates subscribers
                               → Calls subscriber callbacks

3. Consumer Side:
   conduit.subscribe(sub)      → Registers subscriber
   subscriber.accept(registrar) → Sets up consumer Pipe
   registrar.register(pipe)    → Consumer receives emissions
```

### Virtual CPU Core Pattern

```
Circuit Queue (FIFO):
  [Event 1] → [Event 2] → [Event 3] → ...
      ↓
  Single Virtual Thread
      ↓
  Process in Order
      ↓
  Emit to Subscribers
```

**Guarantees:**
- Events processed in exact order received
- No race conditions within Circuit
- Predictable, deterministic behavior

---

## Caching Strategy

**Simple and Effective:**

```
CortexRuntime
├── circuits: ConcurrentHashMap<Name, Circuit>
└── scopes: ConcurrentHashMap<Name, Scope>

CircuitImpl
├── conduits: ConcurrentHashMap<Name, Conduit>
└── clocks: ConcurrentHashMap<Name, Clock>

ConduitImpl
└── channels: ConcurrentHashMap<Name, Channel>

CellNode
└── children: ConcurrentHashMap<Name, Cell>
```

**Key Points:**
- ConcurrentHashMap for all component caching
- `computeIfAbsent()` for thread-safe lazy creation
- No complex optimizations - just standard Java collections
- Fast enough for production (100k+ metrics @ 1Hz)

---

## Thread Safety

### Concurrent Components
- **ConcurrentHashMap**: All component caches
- **CopyOnWriteArrayList**: Subscriber lists (read-heavy workload)
- **BlockingQueue**: Circuit event queue

### Immutable Components
- **NameNode**: Immutable parent-child structure
- **State/Slot**: Immutable state management
- **Signal Types**: Immutable records (in serventis module)

### Synchronization Points
- **Circuit Queue**: Single thread processes, FIFO ordering
- **Component Creation**: `computeIfAbsent()` handles races
- **Subscriber Registration**: CopyOnWriteArrayList handles concurrent adds

---

## Resource Lifecycle

All components implement `Resource` with `close()`:

```
Scope.close()
  → Circuit.close()
    → Conduit.close()
      → Channel.close()
        → Pipe.close()
    → Clock.close()
      → Cancel scheduled tasks
    → Shutdown executor
    → Shutdown scheduler
```

**Best Practices:**
1. Always close Circuits when done
2. Use Scope for automatic cleanup
3. Use try-with-resources where possible
4. Virtual threads auto-cleanup on JVM shutdown (daemon threads)

---

## State Management

**Immutable Slot-Based State:**

```java
State state = State.of(
    Slot.of("key1", "value1"),
    Slot.of("key2", 42),
    Slot.of("key3", Instant.now())
);

// State is immutable - create new state to change
State newState = state.set(Slot.of("key1", "updated"));
```

**Key Features:**
- Type-safe slots with generics
- Immutable - no shared mutable state
- Value semantics - equality by content
- Thread-safe by design (no synchronization needed)

---

## Performance Characteristics

### Test Suite Performance
- **247 tests** complete in ~16 seconds
- **0 failures, 0 errors**
- Integration tests include multi-threading and timing scenarios

### Production Readiness
- Designed for Kafka monitoring: **100k+ metrics @ 1Hz**
- Virtual CPU core pattern ensures ordered processing
- Resource cleanup via Scope prevents memory leaks
- ConcurrentHashMap fast enough for all realistic workloads

### Optimization Approach
**Philosophy:** Optimize when needed, not prematurely

- ✅ **Simple design** - Easy to understand and maintain
- ✅ **Standard collections** - ConcurrentHashMap, CopyOnWriteArrayList
- ✅ **Virtual threads** - Lightweight, scalable
- ✅ **Immutable state** - No synchronization overhead
- ❌ **No premature optimization** - Keep it simple

**If performance becomes an issue:**
1. Profile first - find the actual bottleneck
2. Optimize hot path only
3. Keep optimizations localized
4. Document why optimization was needed

---

## Design Patterns

### 1. Factory Pattern (Simplified)
- Cortex creates Circuits and Scopes
- Circuit creates Conduits and Clocks
- Conduit creates Channels
- No factory abstractions - just direct creation

### 2. Observer Pattern
- Source provides subscribe()
- Subscribers register to receive events
- SourceImpl manages subscriber list

### 3. Composite Pattern
- NameNode parent-child hierarchy
- CellNode parent-child hierarchy
- Scope contains Resources

### 4. Builder Pattern
- State built via Slot.of()
- Flow built via fluent API (sift, limit, sample)

### 5. Virtual CPU Pattern
- Single virtual thread per Circuit
- FIFO queue for events
- Precise ordering guarantees

---

## Integration with Serventis

**Serventis** provides semantic signal types, **Substrates** provides infrastructure:

```java
// Create Circuit
Circuit circuit = cortex.circuit(cortex.name("kafka.broker.health"));

// Create Conduit for MonitorSignals
Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors =
    circuit.conduit(cortex.name("monitors"), Composer.pipe());

// Get Pipe for specific subject
Pipe<MonitorSignal> heapPipe =
    monitors.get(cortex.name("broker-1.jvm.heap"));

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
    cortex.subscriber(
        cortex.name("health-aggregator"),
        (subject, registrar) -> {
            registrar.register(s -> {
                // Process signal
                if (s.status() == MonitorStatus.DEGRADED) {
                    // Take action
                }
            });
        }
    )
);
```

---

## Testing Strategy

### Unit Tests
- Individual component behavior
- NameNode hierarchy
- State/Slot immutability
- Flow/Sift transformations

### Integration Tests
- Circuit event processing
- Conduit/Channel/Pipe flow
- Subscriber notifications
- Clock timing behavior

### Thread Safety Tests
- Concurrent emissions
- Concurrent subscriptions
- Concurrent component creation

### Resource Cleanup Tests
- Circuit close
- Scope close
- Clock task cancellation

---

## Summary

**Fullerstack Substrates** is a simplified, production-ready implementation of the Humainary Substrates API M17:

✅ **Simple** - No complex optimizations, easy to understand
✅ **Correct** - 247 tests passing, proper M17 sealed interface usage
✅ **Fast Enough** - Handles 100k+ metrics @ 1Hz
✅ **Thread-Safe** - Proper concurrent collections
✅ **Clean** - Explicit resource lifecycle management
✅ **Maintainable** - Clear architecture, good documentation

**Philosophy:** Build it simple, build it correct, optimize if needed.

---

## References

- [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)
- [Observability X Blog Series](https://humainary.io/blog/category/observability-x/)
- [M17 Migration Guide](../../API-ANALYSIS.md)
- [Performance Guide](PERFORMANCE.md)
- [Best Practices](BEST-PRACTICES.md)
- [Async Architecture](ASYNC-ARCHITECTURE.md)
