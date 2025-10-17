# Advanced Concepts

This document covers advanced Substrates features and subsystems that go beyond basic usage.

## Table of Contents

- [Queue & Script Subsystem](#queue--script-subsystem)
- [Advanced Segment Operations](#advanced-segment-operations)
- [Extent Hierarchy Operations](#extent-hierarchy-operations)
- [Composer Transformations](#composer-transformations)
- [State Querying](#state-querying)
- [Digital Twin Pattern](#digital-twin-pattern)
- [Tap Pattern](#tap-pattern)
- [Pool Access Patterns](#pool-access-patterns)
- [Container Type System](#container-type-system)
- [Performance Optimization](#performance-optimization)

## Queue & Script Subsystem

The Queue and Script subsystem provides a mechanism for **coordinating execution** and **scheduling work** within a Circuit's processing pipeline.

### Queue Interface

Queue coordinates the processing of queued events:

```java
interface Queue {
    void await();                           // Wait until queue is empty
    void post(Script script);               // Post executable unit
    void post(Name name, Script script);    // Post named script
}
```

### Script Interface

Script represents an executable unit of work:

```java
interface Script {
    void exec(Current current);  // Execute with circuit context
}
```

### Current Interface

Current provides efficient access to the circuit's work queue during script execution:

```java
@Temporal
interface Current extends Substrate {
    void post(Runnable runnable);  // Post async work
}
```

### Example: Coordinated Execution

```java
Circuit circuit = cortex.circuit(cortex.name("coordinated"));
Queue queue = circuit.queue();

// Post script for execution within circuit
queue.post(
    cortex.name("initialization"),
    current -> {
        System.out.println("Initializing in circuit context");

        // Post additional async work
        current.post(() -> {
            System.out.println("Async work within circuit");
        });
    }
);

// Wait for all queued work to complete
queue.await();
System.out.println("All work completed");
```

### Example: Batch Processing

```java
Queue queue = circuit.queue();

// Post multiple scripts
for (int i = 0; i < 10; i++) {
    int taskId = i;
    queue.post(
        cortex.name("task-" + taskId),
        current -> {
            processTask(taskId);

            // Chain more work if needed
            if (needsFollowup(taskId)) {
                current.post(() -> followupTask(taskId));
            }
        }
    );
}

// Wait for batch completion
queue.await();
```

### Key Points

- **Current is @Temporal** - Don't retain references to it
- **Queue.await() blocks** - Useful for testing and coordination
- **Scripts execute in circuit context** - Proper ordering guarantees
- **Current.post() for chaining** - Schedule follow-up work during execution

### QoS Architecture

Queue uses simple **FIFO ordering** (LinkedBlockingQueue) with no priority or reordering within the Queue. Quality of Service is handled at the **Circuit/Conduit level**, not at the Script level:

**Architecture Principle:** From [Observability X - Circuits](https://humainary.io/blog/observability-x-circuits/):
- **Different priorities** → Create separate Circuits (each has its own Queue)
- **Different processing characteristics** → Use separate Conduits
- **Keep Queue simple** → Strict FIFO processing, no Script-level priorities

**Example:**
```java
// High-priority circuit with dedicated resources
Circuit highPriority = cortex.circuit(cortex.name("high-priority"));

// Normal-priority circuit
Circuit normalPriority = cortex.circuit(cortex.name("normal-priority"));

// Each Circuit has its own Queue with FIFO ordering
// QoS achieved through separate resource allocation
```

**Why Not Priority Queues?**
- Complexity: Priority logic complicates ordering guarantees
- Observability: Hard to reason about execution order
- Scalability: Circuit-level separation scales better
- Architecture: Aligns with Substrates' hierarchical design

**Implementation Note:** QueueImpl uses `LinkedBlockingQueue` (not `LinkedBlockingDeque`) for pure FIFO semantics. The `post(Name, Script)` method accepts a Name for **tagging/tracking** Scripts, not for priority selection.

## Advanced Segment Operations

Beyond the basic transformations, Segment provides advanced operations for complex pipelines.

### forward(Pipe) - Tee/Split Pattern

Forward emissions to another pipe while continuing through the pipeline:

```java
Pipe<String> auditLog = createAuditPipe();

segment -> segment
    .forward(auditLog)  // Tee to audit log
    .guard(s -> s.startsWith("IMPORTANT"))  // Continue with filter
    .replace(String::toUpperCase)

// Input: "important message", "other", "IMPORTANT alert"
// auditLog receives: ALL three
// Pipeline output: "IMPORTANT MESSAGE", "IMPORTANT ALERT"
```

### peek(Consumer) - Inspection Without Modification

Inspect values as they pass through without modifying them:

```java
AtomicInteger count = new AtomicInteger(0);

segment -> segment
    .peek(value -> count.incrementAndGet())  // Count emissions
    .peek(value -> logger.debug("Saw: {}", value))  // Log
    .guard(value -> value > threshold)

// peek() doesn't modify values, only observes
```

### guard(initial, BiPredicate) - Stateful Filtering

Compare current value against initial or previous value:

```java
// Only emit when value increases
segment.guard(
    0,  // Initial value
    (prev, curr) -> curr > prev
);

// Input:  0, 5, 3, 10, 8, 15
// Output: 5, 10, 15 (only increases from previous)
```

### Combining Advanced Operations

```java
Pipe<Integer> mirror = createMirrorPipe();
AtomicInteger throughput = new AtomicInteger(0);

segment -> segment
    .peek(v -> throughput.incrementAndGet())  // 1. Count all
    .forward(mirror)                          // 2. Mirror all
    .guard(0, (prev, curr) -> curr > prev)    // 3. Only increases
    .peek(v -> System.out.println("Inc: " + v))  // 4. Log increases
    .limit(100)                               // 5. Max 100
```

## Extent Hierarchy Operations

Name, Subject, and Scope implement Extent, providing powerful hierarchy operations.

### fold() - Aggregate from Leaf to Root

Accumulate values moving from current extent to root:

```java
Name name = cortex.name("app.service.endpoint.handler");

// Count depth
int depth = name.fold(
    first -> 1,                    // Initialize with 1
    (count, next) -> count + 1     // Increment for each level
);
// depth = 4

// Build path manually
String path = name.fold(
    first -> first.value(),
    (result, next) -> result + "/" + next.value()
);
// path = "handler/endpoint/service/app" (reverse order!)
```

### foldTo() - Aggregate from Root to Leaf

Accumulate values moving from root to current extent:

```java
Name name = cortex.name("app.service.endpoint");

// Build path from root to leaf
String path = name.foldTo(
    first -> first.value(),
    (result, next) -> result + "." + next.value()
);
// path = "app.service.endpoint" (correct order)
```

### stream() - Stream Hierarchy

Process hierarchy as a Stream:

```java
Name name = cortex.name("app.service.endpoint.handler");

// Stream from leaf to root
name.stream()
    .map(Name::value)
    .forEach(System.out::println);
// Output:
// handler
// endpoint
// service
// app

// Find parent service
Optional<Name> service = name.stream()
    .filter(n -> n.value().equals("service"))
    .findFirst();
```

### within() - Hierarchy Checking

Check if extent is within another:

```java
Name app = cortex.name("app");
Name service = cortex.name("app.service");
Name endpoint = cortex.name("app.service.endpoint");

boolean isWithin = endpoint.within(service);  // true
boolean isWithin2 = endpoint.within(app);     // true
boolean isWithin3 = service.within(endpoint); // false
```

### extremity() - Find Root

Navigate to the root of the hierarchy:

```java
Name leaf = cortex.name("a.b.c.d.e");
Name root = leaf.extremity();
System.out.println(root.value());  // "a"
```

## Composer Transformations

Composers transform Channels into Percepts. A **Composer wraps around a Channel**, not around a Pipe. The Composer's `compose(Channel)` method takes a Channel and returns a Percept (which might be a Pipe, the Channel itself, or a custom type).

### How Composer Works

```java
// Composer interface
interface Composer<P, E> {
    P compose(Channel<E> channel);  // Takes Channel, returns Percept
}

// Composer.pipe() returns channel's pipe
Composer<Pipe<String>, String> pipeComposer = Composer.pipe();
// Equivalent to: channel -> channel.pipe()

// Composer.channel() returns the channel itself
Composer<Channel<String>, String> channelComposer = Composer.channel();
// Equivalent to: channel -> channel
```

### Composer.map() - Transform Results

Apply a function to transform the percept returned by a composer:

```java
// Base composer returns Pipe
Composer<Pipe<String>, String> pipeComposer = Composer.pipe();

// Transform to wrap in monitoring
Composer<MonitoredPipe<String>, String> monitored = pipeComposer.map(
    pipe -> new MonitoredPipe<>(pipe, metrics)
);

// Transform to add logging
Composer<LoggingPipe<String>, String> logged = pipeComposer.map(
    pipe -> new LoggingPipe<>(pipe, logger)
);
```

### Chaining Transformations

```java
Composer<Channel<Integer>, Integer> base = Composer.channel();

Composer<CustomPercept, Integer> custom = base
    .map(channel -> new ChannelWrapper(channel))
    .map(wrapper -> new CustomPercept(wrapper, config));
```

## State Querying

State supports querying with fallback values and multiple matches.

### value(Slot) - Single Value with Fallback

```java
Slot<Integer> count = cortex.slot(cortex.name("count"), 0);

State state = cortex.state()
    .state(cortex.name("count"), 42)
    .state(cortex.name("name"), "sensor");

// Get value, fallback to slot's value if not found
int value = state.value(count);  // 42

// If slot not in state, uses slot's value
Slot<Integer> missing = cortex.slot(cortex.name("missing"), 100);
int fallback = state.value(missing);  // 100 (from slot)
```

### values(Slot) - Stream of Matching Values

State can have **duplicate slot names** - `values()` returns all matches:

```java
State state = cortex.state()
    .state(cortex.name("tag"), "important")
    .state(cortex.name("tag"), "urgent")
    .state(cortex.name("tag"), "critical");

Slot<String> tag = cortex.slot(cortex.name("tag"), "");

// Stream all matching values
state.values(tag).forEach(System.out::println);
// Output:
// important
// urgent
// critical

// Collect to list
List<String> tags = state.values(tag)
    .collect(Collectors.toList());
```

### State is Iterable

```java
State state = cortex.state()
    .state(cortex.name("x"), 10)
    .state(cortex.name("y"), 20);

for (Slot<?> slot : state) {
    System.out.println(slot.name() + " = " + slot.value());
}
```

### compact() - Remove Duplicates

```java
State state = cortex.state()
    .state(cortex.name("x"), 10)
    .state(cortex.name("x"), 20)  // Duplicate name
    .state(cortex.name("y"), 30);

State compacted = state.compact();
// Only one 'x' slot remains (last one wins)
```

## Digital Twin Pattern

Substrates is well-suited for **Digital Twin** implementations - mirrored state and computational processing units.

### What is a Digital Twin?

A digital twin is a virtual representation of a physical system that:
- Mirrors real-world state
- Processes updates in real-time
- Enables simulation and analysis
- Maintains observable history

### Implementing Digital Twins with Substrates

```java
// Circuit represents the digital twin runtime
Circuit twin = cortex.circuit(cortex.name("iot-device-twin"));

// Container manages pool of device shadows
Container<Pool<DeviceShadow>, DeviceEvent> devices = twin.container(
    cortex.name("devices"),
    channel -> new DeviceShadow(channel.subject().name())
);

// Subscribe to observe all device events
devices.source().subscribe(
    cortex.subscriber(
        cortex.name("event-logger"),
        (subject, registrar) -> {
            registrar.register(event -> {
                logDeviceEvent(subject.name(), event);
            });
        }
    )
);

// Physical device sends update
DeviceShadow shadow = devices.get(cortex.name("sensor-123"));
shadow.updateTemperature(72.5);
shadow.updateBattery(85);

// Digital twin maintains current state
System.out.println("Twin state: " + shadow.getCurrentState());
```

### Mirror State with Subject.state()

```java
class DeviceShadow {
    private final Subject subject;
    private State currentState;

    public void updateTemperature(double temp) {
        // Update state immutably
        currentState = currentState
            .state(cortex.name("temperature"), temp)
            .state(cortex.name("updated"), Instant.now());

        // Subject carries state
        Subject updated = new SubjectImpl(
            subject.id(),
            subject.name(),
            currentState,
            Subject.Type.SOURCE
        );

        // Emit event with updated state
        emit(new DeviceEvent(updated, "temperature", temp));
    }
}
```

### Computational Processing Units

Use Conduits as computational units:

```java
// Raw sensor data conduit
Conduit<Pipe<SensorReading>, SensorReading> rawData = twin.conduit(
    cortex.name("raw-sensors"),
    Composer.pipe()
);

// Processed data conduit with analytics
Conduit<Pipe<AnalyzedReading>, AnalyzedReading> analytics = twin.conduit(
    cortex.name("analytics"),
    Composer.pipe()
);

// Connect: raw data -> analytics pipeline
rawData.source().subscribe(
    cortex.subscriber(
        cortex.name("analyzer"),
        (subject, registrar) -> {
            Pipe<AnalyzedReading> outputPipe = analytics.get(subject.name());
            registrar.register(rawReading -> {
                AnalyzedReading analyzed = analyzeReading(rawReading);
                outputPipe.emit(analyzed);
            });
        }
    )
);
```

## Tap Pattern

Circuit and Conduit implement Tap for fluent method chaining.

### Tap Interface

```java
interface Tap<T extends Tap<T>> {
    T tap(Consumer<? super T> consumer);
}
```

### Usage: Fluent Configuration

```java
Circuit circuit = cortex.circuit()
    .tap(c -> {
        // Configure circuit
        c.clock(cortex.name("heartbeat"));
        c.clock(cortex.name("metrics"));
    })
    .tap(c -> {
        // Create conduits
        c.conduit(cortex.name("events"), Composer.pipe());
        c.conduit(cortex.name("metrics"), Composer.pipe());
    });
```

### Usage: Conditional Setup

```java
Conduit<Pipe<String>, String> conduit = circuit.conduit(name, Composer.pipe())
    .tap(c -> {
        if (enableMonitoring) {
            c.source().subscribe(monitoringSubscriber);
        }
    })
    .tap(c -> {
        if (enableAudit) {
            c.source().subscribe(auditSubscriber);
        }
    });
```

## Pool Access Patterns

Pool provides multiple ways to access pooled instances.

### get(Name) - Direct Access

```java
Pool<Pipe<String>> pool = conduit;  // Conduit implements Pool
Pipe<String> pipe = pool.get(cortex.name("producer1"));
```

### get(Subject) - Subject-based Access

```java
Subject subject = someChannel.subject();
Pipe<String> pipe = pool.get(subject);
// Uses subject.name() internally
```

### get(Substrate) - Substrate-based Access

```java
Substrate substrate = someComponent;
Pipe<String> pipe = pool.get(substrate);
// Uses substrate.subject().name() internally
```

### Example: Routing by Subject

```java
source.subscribe(
    cortex.subscriber(
        cortex.name("router"),
        (subject, registrar) -> {
            // Route to different conduit based on subject
            Pipe<String> targetPipe = targetConduit.get(subject);
            registrar.register(targetPipe);
        }
    )
);
```

## Container Type System

Understanding Container's complex type parameterization.

### Container Type Parameters

```java
interface Container<P, E> extends Pool<P>, Component<E>
```

- **P** - What Pool.get() returns (percept type)
- **E** - What Component.source() emits (emission type)

### Circuit.container() Return Type

```java
<P, E> Container<Pool<P>, Source<E>> container(Composer<P, E> composer)
```

This means:
- Container IS-A `Pool<Pool<P>>`
- Container IS-A `Component<Source<E>>`
- `container.get(name)` returns `Pool<P>`
- `container.source()` returns `Source<Source<E>>`

### Example: Understanding the Types

```java
// Create container
Container<Pool<Pipe<String>>, Source<String>> container = circuit.container(
    cortex.name("messages"),
    Composer.pipe()
);

// get(Name) returns Pool<Pipe<String>>
Pool<Pipe<String>> pool = container.get(cortex.name("group1"));

// From that pool, get a Pipe
Pipe<String> pipe = pool.get(cortex.name("producer1"));

// source() returns Source<Source<String>>
Source<Source<String>> nestedSource = container.source();

// Subscribe to nested source
nestedSource.subscribe(
    cortex.subscriber(
        cortex.name("observer"),
        (subject, registrar) -> {
            registrar.register(source -> {
                // source is Source<String>
                source.subscribe(innerSubscriber);
            });
        }
    )
);
```

### Nested Subscription Pattern

Container's hierarchical subscription pattern emits Conduit Sources when NEW Conduits are created (not on cached access).

**Emission Timing:**
- Container emits **only on FIRST** `container.get(name)` call for new name
- Subsequent calls return cached Conduit (no emission)
- Emission contains `Capture<Source<E>>` pairing Conduit Subject with its Source

**Complete Example:**
```java
// Create container
Container<Pool<Pipe<String>>, Source<String>> messages = circuit.container(
    cortex.name("messaging"),
    Composer.pipe()
);

// Subscribe to Container - receives Conduit Sources
messages.source().subscribe(
    cortex.subscriber(
        cortex.name("conduit-observer"),
        (conduitSubject, registrar) -> {
            System.out.println("New Conduit created: " + conduitSubject.name());
            // conduitSubject.name() = "messaging.team-A" (for example)

            // Register to receive the Conduit's Source
            registrar.register(conduitSource -> {
                // conduitSource is Source<String> for this Conduit
                System.out.println("Subscribing to Conduit: " + conduitSubject.name());

                // Subscribe to all Channels in this Conduit
                conduitSource.subscribe(
                    cortex.subscriber(
                        cortex.name("message-logger"),
                        (channelSubject, innerRegistrar) -> {
                            // channelSubject.name() = "messaging.team-A.chat" (for example)
                            innerRegistrar.register(message -> {
                                System.out.println("Message: " + message);
                            });
                        }
                    )
                );
            });
        }
    )
);

// FIRST access to "team-A" creates Conduit AND emits its Source
Pool<Pipe<String>> teamA = messages.get(cortex.name("team-A"));
// ← Output: "New Conduit created: messaging.team-A"
// ← Output: "Subscribing to Conduit: messaging.team-A"
// ← conduit-observer receives team-A Conduit's Source and subscribes

// SECOND access to "team-A" returns cached Conduit (no emission)
Pool<Pipe<String>> teamA2 = messages.get(cortex.name("team-A"));
// ← No output (teamA == teamA2, cached)

// Different name creates NEW Conduit (emits again)
Pool<Pipe<String>> teamB = messages.get(cortex.name("team-B"));
// ← Output: "New Conduit created: messaging.team-B"
// ← Output: "Subscribing to Conduit: messaging.team-B"
// ← conduit-observer receives team-B Conduit's Source and subscribes

// Now emit messages
Pipe<String> teamAChatPipe = teamA.get(cortex.name("chat"));
teamAChatPipe.emit("Hello from team A!");
// ← Output: "Message: Hello from team A!"
```

**Key Points:**
- Container tracks first-access vs cached-access via `computeIfAbsent()` pattern
- Emission happens synchronously during `container.get(newName)`
- Cached Conduits (subsequent `get(sameName)`) do not re-emit
- Enables dynamic subscription to entities as they're created
- Hierarchical names maintain Subject tree: container → conduit → channel
```

## Performance Optimization

### Factory Pattern Selection

Substrates uses three pluggable factories for performance optimization. Understanding when to use defaults vs custom implementations is crucial for optimal performance.

#### Default Factories (Recommended for Production)

```java
// ✅ Use defaults - optimized for production
Cortex cortex = new CortexRuntime();
// Equivalent to:
Cortex cortex = new CortexRuntime(
    InternedNameFactory.getInstance(),      // Identity map fast path
    LinkedBlockingQueueFactory.getInstance(), // Simple FIFO
    LazyTrieRegistryFactory.getInstance()    // Dual-index with identity map
);
```

**Performance Characteristics:**
- **InternedName**: Weak reference interning enables pointer equality (==) for identity map lookups
- **LazyTrieRegistry**: Identity map fast path (2-5ns) + fallback hash lookup (15-20ns) + lazy trie for hierarchical queries
- **LinkedBlockingQueue**: Simple FIFO with blocking take for virtual threads

#### Name Implementation Selection

| Implementation | Use Case | Lookup Speed | Memory | Best For |
|---------------|----------|--------------|--------|----------|
| **InternedName** (default) | Production | 2-5ns (identity map) | Low (weak refs) | ✅ 99% of use cases |
| LinkedName | Legacy | 15-20ns (hash) | Low | Compatibility |
| SegmentArrayName | Specific | 15-20ns (hash) | Medium | Array operations |
| LRUCachedName | High churn | Variable | High (cache) | Dynamic names |

**Performance Impact:**
```java
// InternedName + LazyTrieRegistry = Identity map fast path
Name name = nameFactory.createRoot("kafka.broker.1");
Circuit circuit = cortex.circuit(name);  // 2-5ns lookup (pointer equality)

// LinkedName + ConcurrentHashMap = Hash lookup
Name name = linkedFactory.createRoot("kafka.broker.1");
Circuit circuit = cortex.circuit(name);  // 15-20ns lookup (hashCode + equals)
```

#### Registry Implementation Selection

| Implementation | Use Case | Lookup | Hierarchical Queries | Memory | Trie Construction |
|---------------|----------|--------|---------------------|--------|------------------|
| **LazyTrieRegistry** (default) | Production | 2-5ns (identity), 15-20ns (hash) | ✅ Lazy (on-demand) | 2× (dual index) | On first subtree query |
| **FlatMapRegistry** | Simple cases | 15-20ns (hash only) | ❌ No | 1× | None |
| **EagerTrieRegistry** | Frequent hierarchical | 15-20ns (hash) + trie | ✅ Eager (upfront) | 2.5× | On every insertion |
| **StringSplitTrieRegistry** | Testing/compatibility | 15-20ns (hash) + trie | ✅ Eager (upfront) | 2× | String split per insert |

**When to Use LazyTrieRegistry (Default):**
- ✅ Hot-path performance critical (emission/lookups)
- ✅ InternedName in use (identity map fast path enables 2-5ns lookups)
- ✅ Occasional hierarchical queries needed
- ✅ Production deployments
- ❌ Memory constrained (uses 2× memory for dual index)

**When to Use FlatMapRegistry:**
- ✅ Memory constrained (single ConcurrentHashMap)
- ✅ No hierarchical queries needed
- ✅ Simple use cases without identity map optimization
- ❌ Don't need identity map fast path
- ❌ Don't need subtree queries

**When to Use EagerTrieRegistry:**
- ✅ Frequent hierarchical queries (trie pre-built)
- ✅ Query performance more important than write performance
- ✅ InternedName with parent chain (uses parent navigation)
- ❌ Don't mind upfront trie construction cost on every insertion
- ❌ Write-heavy workloads (trie rebuild overhead)

**When to Use StringSplitTrieRegistry:**
- ✅ Testing with mixed Name implementations
- ✅ Compatibility when InternedName not available
- ❌ **NOT recommended for production** (high string split overhead)
- ❌ Performance-critical paths (slow writes)

### Caching and Lookup Optimization

#### Caching Pipes for Maximum Performance

```java
// ⚠️ ACCEPTABLE: Lookup each time (still fast with identity map)
for (int i = 0; i < 1000000; i++) {
    conduit.get(name).emit(value);  // ~7ns: lookup (4ns) + emit (3.3ns)
}

// ✅ BEST: Cache pipe for maximum performance
Pipe<T> pipe = conduit.get(name);  // One-time lookup: 4ns
for (int i = 0; i < 1000000; i++) {
    pipe.emit(value);  // 3.3ns per emit
}
```

**Performance Gain:** 2× faster (7ns → 3.3ns) - For ultra-high-frequency emissions, caching provides the absolute best performance

#### Problem: Circuit/Conduit Creation in Loops

```java
// ❌ BAD: Creates/looks up circuit on every iteration
for (BrokerMetric metric : metrics) {
    Circuit circuit = cortex.circuit(cortex.name("kafka-cluster"));  // 5ns lookup each time
    Conduit conduit = circuit.conduit(name, composer);  // 4ns lookup each time
    // ...
}

// ✅ GOOD: Create once, reuse
Circuit circuit = cortex.circuit(cortex.name("kafka-cluster"));  // One-time: 5ns
Conduit conduit = circuit.conduit(name, composer);  // One-time: 4ns
for (BrokerMetric metric : metrics) {
    Pipe pipe = conduit.get(metric.name());
    pipe.emit(metric.value());
}
```

#### Hierarchical Query Optimization

LazyTrieRegistry builds trie lazily on first hierarchical query:

```java
// First subtree query triggers trie construction
LazyTrieRegistry<Circuit> registry = (LazyTrieRegistry<Circuit>) cortex.circuits;
Map<Name, Circuit> brokers = registry.getSubtree(cortex.name("kafka.brokers"));
// ← One-time cost: ~100-200μs for 1000 entries

// Subsequent subtree queries are fast
Map<Name, Circuit> consumers = registry.getSubtree(cortex.name("kafka.consumers"));
// ← Fast: ~50-100ns (trie already built)
```

**Trade-off:**
- ✅ Hot-path lookups stay fast (no trie overhead until needed)
- ✅ Hierarchical queries available when needed
- ❌ First hierarchical query pays construction cost

### Performance Budget for Kafka Monitoring

Real-world example: Monitoring 100 Kafka brokers with 1000 metrics each at 1Hz.

**Scenario:**
- 100 brokers × 1000 metrics = 100,000 metrics
- 1Hz emission rate = 100,000 emissions/sec
- Per-metric operations: lookup + emit

**Cost Analysis:**

| Operation | Time | Cost per Metric | Total/sec |
|-----------|------|-----------------|-----------|
| Cached pipe lookup | 4ns | 4ns | 400μs |
| Pipe emission | 3.3ns | 3.3ns | 330μs |
| **Total** | **7.3ns** | **7.3ns** | **730μs** |

**CPU Utilization:** 730μs / 1000ms = **0.073% of one CPU core**

**With 4 CPU cores:** 0.073% / 4 = **0.018% total CPU**

**Performance Headroom:**
- Current: 100k metrics @ 1Hz = 0.073% CPU
- 10Hz rate: 1M emissions/sec = 0.73% CPU
- 100Hz rate: 10M emissions/sec = 7.3% CPU
- **100× headroom** available

### Memory Considerations

#### LazyTrieRegistry Memory Overhead

```java
// LazyTrieRegistry has 2× memory overhead (dual index)
Map<Name, Circuit> circuits = new LazyTrieRegistry<>();
circuits.put(name, circuit);
// Stored in:
// 1. identityMap (IdentityHashMap) - for fast path
// 2. registry (ConcurrentHashMap) - for fallback
// Total: 2 map entries per item
```

**When Memory is Constrained:**
```java
// Use FlatMapRegistry (single hash map)
Cortex cortex = new CortexRuntime(
    InternedNameFactory.getInstance(),
    LinkedBlockingQueueFactory.getInstance(),
    FlatMapRegistryFactory.getInstance()  // 1× memory, no identity map
);
```

**Trade-off:** Lookups become 15-20ns (hash only) instead of 2-5ns (identity map)

#### InternedName Memory Management

```java
// InternedName uses weak references - GC automatically cleans up
Name name1 = nameFactory.createRoot("temporary.metric");
name1 = null;  // Weak reference allows GC when no strong refs remain
```

**Memory Safety:**
- Weak reference interning prevents memory leaks
- Names GC'd when no longer referenced
- Automatic cleanup, no manual management needed

### Benchmarking Your Use Case

```java
// Run SubstratesLoadBenchmark to measure your workload
@Benchmark
public void yourWorkload(Blackhole bh) {
    // Your code here
    Pipe<T> pipe = conduit.get(cachedName);
    pipe.emit(value);
    bh.consume(pipe);
}
```

**Key Metrics to Track:**
1. **Cached lookups:** Should be 2-5ns with identity map
2. **Hot-path emission:** Should be ~3.3ns
3. **Full path (lookup + emit):** Should be <10ns for cached names
4. **Memory usage:** Monitor heap for LazyTrieRegistry overhead

See [Performance Guide](PERFORMANCE.md) for comprehensive benchmark results and analysis.

## Best Practices

### Queue & Script

1. **Use Queue.await() in tests** - Ensure all async work completes
2. **Don't retain Current** - It's @Temporal, use only during exec()
3. **Name your scripts** - Makes debugging easier

### Segment Operations

1. **Use peek() for debugging** - Non-invasive observation
2. **forward() for tee patterns** - Send to multiple destinations
3. **guard(BiPredicate) for deltas** - Compare against previous values

### Extent Operations

1. **Use foldTo() for paths** - Aggregates root-to-leaf
2. **Use stream() for filtering** - Leverage Stream API
3. **Check within() for routing** - Hierarchical routing decisions

### State

1. **Use values() for tags** - Multiple values per name
2. **compact() before comparison** - Remove duplicates
3. **Immutability** - Always create new State, never mutate

### Digital Twins

1. **Subject carries state** - Use Subject.state() for mirrored state
2. **Conduits as processing units** - Each conduit = computational step
3. **Container for pools** - Manage multiple device shadows

### Performance Optimization

1. **Use default factories** - InternedName + LazyTrieRegistry optimized for production
2. **Cache pipes** - Get once, emit many times (30× faster)
3. **Monitor hot paths** - Profile with JMH benchmarks
4. **Choose registry wisely** - LazyTrie for performance, FlatMap for memory
5. **Measure your workload** - Run SubstratesLoadBenchmark with your patterns

### Factory Selection

1. **InternedName (default)** - Best for 99% of use cases, enables identity map fast path
2. **LazyTrieRegistry (default)** - Best overall performance, 2-5ns lookups
3. **FlatMapRegistry** - Use when memory constrained, 15-20ns lookups
4. **EagerTrieRegistry** - Use when frequent hierarchical queries

## See Also

- [Core Concepts](CONCEPTS.md) - Fundamental abstractions
- [Architecture Guide](ARCHITECTURE.md) - Design and implementation
- [Performance Guide](PERFORMANCE.md) - Comprehensive performance analysis
- [Examples](examples/README.md) - Practical code examples
