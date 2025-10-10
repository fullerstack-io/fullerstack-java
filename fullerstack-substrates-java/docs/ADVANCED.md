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

The nested Source pattern enables observing when new pools are created:

```java
container.source().subscribe(
    cortex.subscriber(
        cortex.name("pool-observer"),
        (poolSubject, registrar) -> {
            // Called when new Pool created
            registrar.register(eventSource -> {
                // eventSource is Source<String>
                // Subscribe to events from this pool
                eventSource.subscribe(eventSubscriber);
            });
        }
    )
);

// Later, accessing a new pool name triggers subscription
Pool<Pipe<String>> newPool = container.get(cortex.name("new-group"));
// pool-observer is notified and subscribes to events
```

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

## See Also

- [Core Concepts](CONCEPTS.md) - Fundamental abstractions
- [Architecture Guide](ARCHITECTURE.md) - Design and implementation
- [Examples](examples/) - Practical code examples
