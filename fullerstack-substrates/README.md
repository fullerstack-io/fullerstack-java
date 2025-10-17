# Fullerstack Substrates (Java)

A Java implementation of the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) for building event-driven observability systems.

## Overview

Substrates provides a flexible framework for building event-driven and observability systems by combining concepts of circuits, conduits, channels, pipes, subscribers, and subjects. This implementation aligns with William Louth's vision for **semiotic observability** - moving from metrics to signs, symptoms, syndromes, situations, and steering.

## Features

- **Circuit** - Central processing engine with precise ordering guarantees for events
- **Conduit** - Context that creates Channels and provides Source for subscription
- **Channel** - Named connector linking producers and consumers; provides access to Pipe
- **Source** - Observable context for dynamic observation of Subjects/Channels
- **Sequencer/Segment** - Transformation pipelines (filter, map, reduce, limit, sample, sift)
- **Clock** - Timer utility for time-driven behaviors
- **Scope** - Hierarchical resource lifecycle management
- **Queue** - Coordinates execution and script scheduling
- **Factory Patterns** - Pluggable NameFactory, QueueFactory, RegistryFactory for customization
- **Identity Map Fast Path** - InternedName + LazyTrieRegistry for 5× faster cached lookups

## Performance

Substrates is optimized for high-throughput, low-latency observability:

- **Hot-path emission:** 3.3ns (2× faster with identity map optimization)
- **Cached lookups:** 4-5ns (5× faster via identity map fast path)
- **Full path (lookup + emit):** 101ns
- **Kafka monitoring:** 0.033% CPU for 100k metrics @ 1Hz

**Example: 100 Kafka brokers with 1000 metrics each:**
- 100,000 metrics emitted @ 1Hz
- 730μs total time per second
- 0.073% of one CPU core
- **100× performance headroom** available

See [Performance Guide](docs/PERFORMANCE.md) for comprehensive benchmarks and analysis.

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.fullerstack</groupId>
    <artifactId>fullerstack-substrates</artifactId>
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

### Getting Started
- **[Documentation Index](docs/DOCS-INDEX.md)** - Complete documentation map and navigation guide
- **[Core Concepts](docs/CONCEPTS.md)** - Entities, relationships, and factory patterns
- **[Examples](docs/examples/)** - Hands-on examples from simple to complex

### Architecture & Implementation
- **[Architecture Guide](docs/ARCHITECTURE.md)** - System design, data flow, and factory injection
- **[Implementation Guide](docs/IMPLEMENTATION-GUIDE.md)** - Best practices and recommended patterns
- **[Advanced Topics](docs/ADVANCED.md)** - Performance optimization, custom implementations, extensions

### Performance
- **[Performance Guide](docs/PERFORMANCE.md)** - ⭐ Authoritative performance analysis and benchmarks
- **[Name Implementation Comparison](docs/name-implementation-comparison.md)** - Name strategy selection guide

### Alignment
- **[Substrates 101](docs/archive/alignment/substrates-101.md)** - Core philosophy and concepts (archived)
- **[Alignment Overview](docs/archive/alignment/README.md)** - Humainary Substrates API alignment (archived)

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

- **Channel** = Named connector linking producers and consumers; provides Pipe access
- **Pipe** = Dual-purpose transport (producers emit, consumers receive via registered Pipes)
- **Source** = Observable context (provides `subscribe()` for dynamic observation)
- **Subscriber** = Observer factory; registers consumer Pipes when Subjects emit
- **Conduit** = Context that creates Channels and provides Source (Conduit IS-A Context)

### Design Principles

1. **Factory Injection** - Pluggable NameFactory, QueueFactory, RegistryFactory for customization
2. **Identity Map Fast Path** - InternedName + LazyTrieRegistry for 5× faster cached lookups
3. **Interface Types** - All fields use interface types, not implementation types
4. **@Temporal Types** - Transient types (Registrar, Sift, Closure) are not retained
5. **Virtual Threads** - Daemon threads auto-cleanup on JVM shutdown
6. **Resource Lifecycle** - Component extends Resource, all have `close()`
7. **Precise Ordering** - Circuit guarantees event ordering
8. **Immutable State** - State is immutable, built via fluent API

### Performance Best Practices

```java
// ✅ GOOD: Use defaults (optimized for production)
Cortex cortex = new CortexRuntime();
// → InternedName + LazyTrieRegistry = identity map fast path

// ✅ GOOD: Cache pipes for repeated emissions
Pipe<T> pipe = conduit.get(name);  // One-time lookup: 4ns
for (int i = 0; i < 1000000; i++) {
    pipe.emit(value);  // 3.3ns per emit
}

// ❌ BAD: Lookup on every emission
for (int i = 0; i < 1000000; i++) {
    conduit.get(name).emit(value);  // 101ns per emit (30× slower!)
}
```

## Building from Source

### Prerequisites

This project depends on the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java), which is not published to Maven Central. You must install it locally first:

```bash
# Clone and install Humainary Substrates API
git clone https://github.com/humainary-io/substrates-api-java.git
cd substrates-api-java
mvn clean install
cd ..

# Clone and install Humainary Serventis API (if using serventis module)
git clone https://github.com/humainary-io/serventis-api-java.git
cd serventis-api-java
mvn clean install
cd ..
```

### Building Fullerstack Substrates

```bash
git clone https://github.com/fullerstack-io/fullerstack-java.git
cd fullerstack-java/fullerstack-substrates
mvn clean install
```

## Running Tests

```bash
mvn test
```

All 264 tests should pass.

## Requirements

- Java 25 or later (LTS - uses Virtual Threads)
- Maven 3.9+

## References

- [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)
- [Observability X Blog Series](https://humainary.io/blog/category/observability-x/) - Complete series by William Louth:
  - [Substrates 101](https://humainary.io/blog/observability-x-substrates-101/) - Introduction to the framework
  - [Channels](https://humainary.io/blog/observability-x-channels/) - Named connectors
  - [Sources](https://humainary.io/blog/observability-x-sources/) - Observable contexts
  - [Subscribers](https://humainary.io/blog/observability-x-subscribers/) - Observer pattern
  - [Composers](https://humainary.io/blog/observability-x-composers/) - Instrument factories
  - [Contexts](https://humainary.io/blog/observability-x-contexts/) - Contextual instrumentation
  - [Circuits](https://humainary.io/blog/observability-x-circuits/) - Processing engines
  - [Containers](https://humainary.io/blog/observability-x-containers/) - Pool management
  - [Pipes & Pathways](https://humainary.io/blog/observability-x-pipes-pathways/) - Data transport
  - [Subjects](https://humainary.io/blog/observability-x-subjects/) - Observable entities
  - [States and Slots](https://humainary.io/blog/observability-x-states-and-slots/) - State management
  - [Staging State](https://humainary.io/blog/observability-x-staging-state/) - State coordination
  - [Naming Percepts](https://humainary.io/blog/observability-x-naming-percepts/) - Hierarchical naming
  - [Queues, Scripts, and Currents](https://humainary.io/blog/observability-x-queues-scripts-and-currents/) - Execution control
  - [Resources, Scopes, and Closures](https://humainary.io/blog/observability-x-resources-scopes-and-closures/) - Lifecycle management
  - [eXtensibility](https://humainary.io/blog/observability-x-extensibility/) - Extension patterns
  - [Location Agnostic](https://humainary.io/blog/observability-x-location-agnostic/) - Distribution
- [William Louth on Semiotic Observability](https://humainary.io)

## Acknowledgments

This implementation is based on the **Humainary Substrates API** designed by **William Louth**. The Substrates framework provides a foundational approach to building observable, event-driven systems through elegant abstractions and compositional design patterns.

We are deeply grateful to William Louth and the Humainary community for:
- The innovative design of the Substrates API
- The comprehensive Observability X blog series documenting the concepts
- The vision for semiotic observability and humane software instrumentation
- Making the API available under the Apache 2.0 license

The architectural patterns, design principles, and core concepts implemented in this library originate from William Louth's work on observable systems, signal flow management, and contextual instrumentation.

**Learn more about the Substrates framework:**
- Humainary: https://humainary.io/
- Substrates API (Java): https://github.com/humainary-io/substrates-api-java
- Observability X Blog: https://humainary.io/blog/category/observability-x/
- William Louth's Articles: https://humainary.io/blog/

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

This implementation uses the Humainary Substrates API, which is also licensed under Apache License 2.0.
Copyright information and attributions are detailed in the [NOTICE](NOTICE) file.

## Contributing

Contributions welcome! Please ensure all tests pass and follow the existing code style.

When contributing, please:
- Maintain alignment with the Humainary Substrates API specification
- Follow the design principles documented in the Observability X blog series
- Add tests for new functionality
- Update documentation as needed

## Authors

**Implementation:** Fullerstack (https://fullerstack.io/)
**API Design:** William Louth - Humainary (https://humainary.io/)
