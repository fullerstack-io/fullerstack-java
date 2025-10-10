# Fullerstack Substrates (Java)

A Java implementation of the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) for building event-driven observability systems.

## Overview

Substrates provides a flexible framework for building event-driven and observability systems by combining concepts of circuits, conduits, channels, pipes, subscribers, and subjects. This implementation aligns with William Louth's vision for **semiotic observability** - moving from metrics to signs, symptoms, syndromes, situations, and steering.

## Features

- **Circuit** - Central processing engine with precise ordering guarantees for events
- **Conduit** - Routes emissions from Channels (producers) to Pipes (consumers)
- **Channel** - Named entry points where producers emit data
- **Source** - Observable event streams that can be subscribed to
- **Sequencer/Segment** - Transformation pipelines (filter, map, reduce, limit, sample, sift)
- **Clock** - Timer utility for time-driven behaviors
- **Scope** - Hierarchical resource lifecycle management
- **Queue** - Coordinates execution and script scheduling

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.fullerstack</groupId>
    <artifactId>substrates-java</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Basic Usage

```java
import io.fullerstack.substrates.CortexRuntime;
import io.humainary.substrates.api.Substrates.*;

// Create runtime
Cortex cortex = CortexRuntime.create();

// Create circuit
Circuit circuit = cortex.circuit(cortex.name("my-circuit"));

// Create conduit with Pipe composer
Conduit<Pipe<String>, String> conduit = circuit.conduit(
    cortex.name("messages"),
    Composer.pipe()
);

// Subscribe to observe emissions
Source<String> source = conduit.source();
source.subscribe(
    cortex.subscriber(
        cortex.name("logger"),
        (subject, registrar) ->
            registrar.register(msg -> System.out.println("Received: " + msg))
    )
);

// Get a pipe and emit
Pipe<String> pipe = conduit.get(cortex.name("producer1"));
pipe.emit("Hello, Substrates!");

// Clean up
circuit.close();
```

### With Transformations (Sequencer/Segment)

```java
// Create conduit with transformation pipeline
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("numbers"),
    Composer.pipe(segment -> segment
        .guard(n -> n > 0)           // Filter: only positive numbers
        .limit(100)                   // Limit: max 100 emissions
        .sample(10)                   // Sample: every 10th emission
    )
);

// Subscribe and emit
conduit.source().subscribe(
    cortex.subscriber(
        cortex.name("consumer"),
        (subject, registrar) ->
            registrar.register(n -> System.out.println("Got: " + n))
    )
);

Pipe<Integer> pipe = conduit.get(cortex.name("counter"));
for (int i = 0; i < 1000; i++) {
    pipe.emit(i);
}
```

### Clock Example

```java
// Create clock that ticks every second
Clock clock = circuit.clock(cortex.name("timer"));

// Subscribe to second ticks
clock.consume(
    cortex.name("tick-handler"),
    Clock.Cycle.SECOND,
    instant -> System.out.println("Tick: " + instant)
);

// Clock runs until circuit is closed
Thread.sleep(5000);
circuit.close();
```

### Scope for Resource Management

```java
// Create scope for resource lifecycle management
Scope scope = cortex.scope(cortex.name("transaction"));

// Register resources
Circuit circuit = scope.register(cortex.circuit());
Conduit<Pipe<String>, String> conduit = scope.register(
    circuit.conduit(cortex.name("events"), Composer.pipe())
);

// Use closure for automatic cleanup
scope.closure(circuit).consume(c -> {
    // Circuit is automatically closed when this block exits
    Pipe<String> pipe = conduit.get(cortex.name("producer"));
    pipe.emit("Event data");
});

// Or close scope manually to clean up all registered resources
scope.close();
```

## Documentation

- **[Architecture Guide](docs/ARCHITECTURE.md)** - Design principles and data flow
- **[Core Concepts](docs/CONCEPTS.md)** - Detailed explanation of key abstractions
- **[Examples](docs/examples/)** - Common usage patterns and recipes

## Key Concepts

### Data Flow

```
Producer Side:
  Channel (entry point)
    → pipe()
    → Pipe
    → emit(value)
    → Conduit's queue

Conduit Processing:
  Queue
    → Queue Processor
    → processEmission()
    → Source.emit() (via Pipe interface)

Consumer Side:
  Source.subscribe(subscriber)
    → When emission occurs
    → subscriber.accept(subject, registrar)
    → registrar.register(consumerPipe)
    → consumerPipe.emit(emission)
```

### Terminology

- **Channel** = Producer (entry point where data enters)
- **Pipe** = Transport mechanism (has `emit()`, used on both sides)
- **Source** = Observable stream (provides `subscribe()`)
- **Subscriber** = Connects consumer Pipes to a Source
- **Conduit** = Routes emissions from Channels (producers) to Pipes (consumers)

### Design Principles

1. **Interface Types** - All fields use interface types, not implementation types
2. **@Temporal Types** - Transient types (Registrar, Sift, Closure) are not retained
3. **Virtual Threads** - Daemon threads auto-cleanup on JVM shutdown
4. **Resource Lifecycle** - Component extends Resource, all have `close()`
5. **Precise Ordering** - Circuit guarantees event ordering
6. **Immutable State** - State is immutable, built via fluent API

## Building from Source

```bash
git clone https://github.com/fullerstack-io/fullerstack-java.git
cd fullerstack-java/fullerstack-substrates-java
mvn clean install
```

## Running Tests

```bash
mvn test
```

All 183 tests should pass.

## Requirements

- Java 24 or later (uses Virtual Threads)
- Maven 3.9+

## References

- [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)
- [Observability X Blog Series](https://humainary.io/blog/category/observability-x/)
  - [Channels](https://humainary.io/blog/observability-x-channels/)
  - [Sources](https://humainary.io/blog/observability-x-sources/)
  - [Subscribers](https://humainary.io/blog/observability-x-subscribers/)
- [William Louth on Semiotic Observability](https://humainary.io)

## License

Apache License 2.0

## Contributing

Contributions welcome! Please ensure all tests pass and follow the existing code style.

## Authors

- Implementation: Fullerstack
- API Design: William Louth (Humainary)
