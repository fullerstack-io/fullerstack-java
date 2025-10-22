# Fullerstack Substrates (Java)

A Java implementation of the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) for building event-driven observability systems.

## Overview

Substrates provides a flexible framework for building event-driven and observability systems by combining concepts of circuits, conduits, channels, pipes, subscribers, and subjects. This implementation aligns with William Louth's vision for **semiotic observability** - moving from metrics to signs, symptoms, syndromes, situations, and steering.

## Features

- **Circuit** - Central processing engine with virtual CPU core pattern for precise event ordering
- **Conduit** - Container that coordinates Channels, Pipes, and subscriber management
- **Channel** - Named emission port linking producers to the event stream
- **CellNode** - Hierarchical container with type transformation (I → E) for building observability trees
- **NameNode** - Hierarchical dot-notation names (e.g., "kafka.broker.1") with parent-child structure
- **Flow/Sift** - Transformation pipelines (filter, map, reduce, limit, sample, and more)
- **Clock** - Scheduled event emission with shared ScheduledExecutorService optimization
- **Scope** - Hierarchical resource lifecycle management
- **Sink** - Event capture and storage for testing and debugging
- **Subscriber Management** - Thread-safe subscriber registration and notification (via internal SourceImpl)
- **Immutable State** - Slot-based state management with type safety
- **M17 API** - Full support for sealed interface hierarchy with type safety guarantees

## Performance

Substrates is optimized for high-throughput, low-latency observability with a simplified, lean implementation:

- **Simplified Design** - Removed unnecessary abstractions (4 Name implementations, 8 Registry implementations, benchmark/queue packages)
- **Shared Schedulers** - Clock instances share a single ScheduledExecutorService per Circuit
- **Efficient Emissions** - Direct pipe emission with minimal overhead
- **Thread-Safe** - CopyOnWriteArrayList for subscribers (optimized for read-heavy workloads)
- **Zero-Copy State** - Immutable slot-based state management

**Test Performance:**
- **247 tests** complete in ~16 seconds
- All tests pass with **0 failures, 0 errors**
- Integration tests include multi-threading and timing scenarios

**Production Readiness:**
- Designed for Kafka monitoring (100k+ metrics @ 1Hz)
- Virtual CPU core pattern ensures ordered event processing
- Resource cleanup via Scope ensures no memory leaks

See [Performance Guide](docs/PERFORMANCE.md) for detailed analysis.

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

// Subscribe to observe emissions (Conduit implements Source in M17)
conduit.subscribe(
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

### With Transformations (Flow/Sift)

```java
// Create conduit with transformation pipeline
Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
    cortex.name("numbers"),
    Composer.pipe(flow -> flow
        .sift(n -> n > 0)             // Filter: only positive numbers
        .limit(100)                   // Limit: max 100 emissions
        .sample(10)                   // Sample: every 10th emission
    )
);

// Subscribe and emit (Conduit extends Source via sealed hierarchy)
conduit.subscribe(
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
- **[Async-First Architecture](docs/ASYNC-ARCHITECTURE.md)** ⚠️ **CRITICAL** - Understanding async queue processing
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

1. **Simplified Architecture** - Single NameNode implementation, no factory abstractions
2. **Sealed Hierarchy** - M17 sealed interfaces enforce correct type composition
3. **Interface Types** - Public API uses interface types for flexibility
4. **@Temporal Types** - Transient types (Registrar, Sift, Closure) are not retained
5. **Thread Safety** - CopyOnWriteArrayList for subscribers (read-optimized)
6. **Resource Lifecycle** - All components extend Resource with `close()`
7. **Precise Ordering** - Circuit's virtual CPU core guarantees event ordering
8. **Immutable State** - State is immutable, built via slot-based API

### Performance Best Practices

```java
// ✅ GOOD: Cache pipes for repeated emissions
Pipe<T> pipe = conduit.get(name);  // One-time lookup
for (int i = 0; i < 1000000; i++) {
    pipe.emit(value);  // Direct emission, minimal overhead
}

// ❌ BAD: Lookup on every emission
for (int i = 0; i < 1000000; i++) {
    conduit.get(name).emit(value);  // Repeated lookups add overhead
}

// ✅ GOOD: Share scheduler across Clocks in same Circuit
Circuit circuit = cortex.circuit(name);
Clock clock1 = circuit.clock(name1);  // Shares scheduler
Clock clock2 = circuit.clock(name2);  // Shares same scheduler

// ✅ GOOD: Use hierarchical names for organization
Name brokerName = cortex.name("kafka.broker.1");
Name metricName = brokerName.name("metrics").name("bytes-in");
// Creates: "kafka.broker.1.metrics.bytes-in"
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
