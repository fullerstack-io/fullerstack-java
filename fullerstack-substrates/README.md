# Fullerstack Substrates (Java)

A Java implementation of the [Humainary Substrates API](https://github.com/humainary-io/substrates-api-java) with integrated [Serventis instrument APIs](https://github.com/humainary-io/substrates-ext-serventis-java) for building semiotic observability systems.

## Overview

Substrates provides the foundational event-routing infrastructure for building observable, event-driven systems. Combined with **Serventis instrument APIs**, it enables **semiotic observability** - transforming raw signals into meaningful insights through progressive interpretation:

```
Raw Signals → Signs → Symptoms → Syndromes → Situations → Steering
   (Probes)    (Monitors)  (Assessors)  (Reporters)    (Actions)
```

This implementation brings William Louth's vision for **humane observability** to life:
- **Context creates meaning** - The same signal means different things in different contexts
- **Progressive interpretation** - From sensing (OBSERVE) to understanding (ORIENT) to action (DECIDE/ACT)
- **Precise event ordering** - Virtual CPU core pattern ensures deterministic processing
- **Type-safe instrument APIs** - Compiler-enforced correctness for observable systems

## Features

### Serventis Instrument APIs (Semiotic Observability)

**OBSERVE Phase** - What's happening?
- **Probes** - Communication outcomes: `probe.observation(CLIENT, CONNECT, SUCCESS)`
- **Services** - Interaction lifecycle: `service.call()`, `service.succeeded()`, `service.failed()`
- **Queues** - Flow control: `queue.enqueue()`, `queue.dequeue()`, `queue.overflow()`, `queue.underflow()`
- **Gauges** - Bidirectional metrics: `gauge.increment()`, `gauge.decrement()`, `gauge.overflow()`, `gauge.underflow()`
- **Counters** - Monotonic metrics: `counter.increment()`, `counter.overflow()`, `counter.reset()`
- **Caches** - Hit/miss tracking: `cache.hit()`, `cache.miss()`, `cache.evict()`, `cache.expire()`

**ORIENT Phase** - What does it mean?
- **Monitors** - Condition assessment: `monitor.degraded(HIGH)`, `monitor.stable(HIGH)`, etc.
- **Resources** - Capacity assessment: `resource.grant()`, `resource.deny()`, `resource.timeout()`

**DECIDE Phase** - How urgent?
- **Reporters** - Situation urgency: `reporter.critical()`, `reporter.warning()`, `reporter.normal()`

**Key Insight:** Context creates meaning. A `queue.overflow()` signal from a producer buffer means something different than the same signal from a consumer lag metric. Subscribers assess conditions based on the Subject (entity context) of each signal.

### Substrates Core Infrastructure
- **Circuit** - Virtual CPU core with Valve pattern for deterministic event ordering
- **Conduit** - Dynamic channel creation with type-safe Composer transformations
- **Cell** - Hierarchical computational cells with bidirectional type transformation (I → E)
- **Channel** - Named pipes with Subject identity for contextual signal routing
- **Flow/Sift** - Transformation pipelines (guard, limit, replace, reduce, diff, sample, skip)
- **Pool** - Thread-safe caching with single-instance-per-name guarantee
- **Scope** - Hierarchical resource lifecycle management with automatic cleanup
- **State/Slot** - Immutable, type-safe state management
- **Subject/Name** - Hierarchical identity with dot-notation (e.g., "kafka.broker-1.jvm.heap")

### Bootstrap System (NEW)
- **SubstratesBootstrap** - **ONE LINE** automatic circuit discovery and initialization
- **CircuitDiscovery** - Convention-based discovery from `config_*.properties` files
- **HierarchicalConfig** - Zero-dependency configuration using ResourceBundle
- **SPI Extensibility** - Applications provide structure and sensors via ServiceLoader
- **Type-Safe Application Layer** - Framework is type-agnostic, applications use typed composers

See [Bootstrap Guide](docs/BOOTSTRAP-GUIDE.md) for complete documentation.

## Performance

Substrates is optimized for high-throughput, low-latency observability with a simplified, lean implementation:

- **Simplified Design** - Removed unnecessary abstractions (4 Name implementations, 8 Registry implementations, benchmark/queue packages)
- **Simplified Architecture** - Removed unnecessary abstractions for clarity
- **Efficient Emissions** - Direct pipe emission with minimal overhead
- **Thread-Safe** - CopyOnWriteArrayList for subscribers (optimized for read-heavy workloads)
- **Zero-Copy State** - Immutable slot-based state management

**Test Performance:**
- **502 tests** complete in ~6 seconds (including 38 API compliance tests)
- All tests pass with **0 failures, 0 errors**
- Compliance tests verify documented API behaviors from Substrates.java
- Integration tests include multi-threading, async patterns, and timing scenarios

**Design Targets:**
- High-throughput observability (100k+ metrics @ 1Hz)
- Virtual CPU core pattern ensures ordered event processing
- Resource cleanup via Scope ensures proper lifecycle management

See [Developer Guide](docs/DEVELOPER-GUIDE.md) for performance details and best practices.

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>io.fullerstack</groupId>
    <artifactId>fullerstack-substrates</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Option 1: Automatic Bootstrap (Recommended)

The easiest way to get started - **ONE LINE** bootstraps your entire application:

```java
import io.fullerstack.substrates.bootstrap.SubstratesBootstrap;
import io.fullerstack.substrates.bootstrap.SubstratesBootstrap.BootstrapResult;

public class MyApp {
    public static void main(String[] args) {
        // ONE LINE - discovers circuits, creates structure, starts sensors
        BootstrapResult result = SubstratesBootstrap.bootstrap();

        System.out.println("Circuits: " + result.getCircuitNames());
    }
}
```

**Prerequisites:**
1. Create `config_my-circuit.properties` with `circuit.enabled=true`
2. Implement `CircuitStructureProvider` SPI to build your circuit structure
3. Implement `SensorProvider` SPI to provide your data sources
4. Register SPIs in `META-INF/services/`

See [Bootstrap Guide](docs/BOOTSTRAP-GUIDE.md) for complete tutorial.

### Option 2: Manual Usage

For fine-grained control, create circuits manually:

```java
import io.fullerstack.substrates.CortexRuntime;
import io.humainary.substrates.api.Substrates.*;

import static io.fullerstack.substrates.CortexRuntime.cortex;

// Get singleton Cortex
Cortex cortex = cortex();

// Create circuit
Circuit circuit = cortex.circuit(cortex.name("my-circuit"));

// Create conduit with Pipe composer
Conduit<Pipe<String>, String> conduit = circuit.conduit(
    cortex.name("messages"),
    Composer.pipe()
);

// Subscribe to observe emissions
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

### Serventis Example: Context Creates Meaning

This example shows how **the same signal means different things in different contexts**:

```java
import static io.humainary.substrates.ext.serventis.Serventis.*;

// Create circuit
Circuit circuit = cortex().circuit(cortex().name("kafka-monitoring"));

// OBSERVE: Create Queue instrument conduit
Conduit<Queue, Queues.Signal> queues = circuit.conduit(
    cortex().name("queues"),
    Queues::composer  // Method reference to Serventis composer
);

// Get Queue instruments for different entities (creates Channels with Subject identity)
Queue producerBuffer = queues.get(cortex().name("producer-1.buffer"));
Queue consumerLag = queues.get(cortex().name("consumer-1.lag"));

// ORIENT: Monitor conduit assesses conditions based on Queue signals
Conduit<Monitor, Monitors.Status> monitors = circuit.conduit(
    cortex().name("monitors"),
    Monitors::composer
);

// Subscribe to Queue signals and assess conditions
queues.subscribe(cortex().subscriber(
    cortex().name("queue-assessor"),
    (Subject<Channel<Queues.Signal>> subject, Registrar<Queues.Signal> registrar) -> {
        // Get Monitor for this specific entity
        Monitor monitor = monitors.get(subject.name());

        registrar.register(signal -> {
            // CONTEXT CREATES MEANING
            // Same OVERFLOW signal, different interpretations:

            if (subject.name().toString().contains("producer")) {
                // Producer buffer overflow = backpressure from broker
                if (signal == Queues.Signal.OVERFLOW) {
                    monitor.degraded(Monitors.Confidence.HIGH);  // Use convenience method
                    System.out.println("⚠️  Producer backpressure on " + subject.name());
                }
            } else if (subject.name().toString().contains("consumer")) {
                // Consumer lag overflow = falling behind, data loss risk
                if (signal == Queues.Signal.OVERFLOW) {
                    monitor.defective(Monitors.Confidence.HIGH);  // Use convenience method
                    System.out.println("🚨 Consumer lag critical on " + subject.name());
                }
            }
        });
    }
));

// Emit signals - same signal, different contexts
producerBuffer.overflow(95L);  // Producer: annoying but recoverable
consumerLag.overflow(95L);     // Consumer: data loss imminent!

circuit.await();  // Process all signals
circuit.close();
```

**Key Insight:** The `Subject` (entity identity) carried with each signal allows subscribers to interpret meaning contextually. This is the essence of semiotic observability - signals become signs, signs reveal symptoms, symptoms indicate syndromes, leading to informed steering decisions.

### Scope for Resource Management

```java
// Create scope for resource lifecycle management
Scope scope = cortex().scope(cortex().name("transaction"));

// Register resources
Circuit circuit = scope.register(cortex().circuit());
Conduit<Pipe<String>, String> conduit = scope.register(
    circuit.conduit(cortex().name("events"), Composer.pipe())
);

// Use closure for automatic cleanup
scope.closure(circuit).consume(c -> {
    // Circuit is automatically closed when this block exits
    Pipe<String> pipe = conduit.get(cortex().name("producer"));
    pipe.emit("Event data");
});

// Or close scope manually to clean up all registered resources
scope.close();
```

## Documentation

**Core Documentation:**

1. **[Architecture & Core Concepts](docs/ARCHITECTURE.md)** - System design, RC5 sealed hierarchy, all core entities
2. **[Developer Guide](docs/DEVELOPER-GUIDE.md)** - Best practices, performance tips, testing strategies
3. **[Async-First Architecture](docs/ASYNC-ARCHITECTURE.md)** ⚠️ **CRITICAL** - Understanding async queue processing

**Additional Resources:**

- **[Examples](docs/examples/)** - Hands-on examples from simple to complex
- **[RC5 Migration Guide](../API-ANALYSIS.md)** - Sealed interfaces and migration from RC4

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

1. **Simplified Architecture** - Single HierarchicalName implementation, no factory abstractions
2. **Sealed Hierarchy** - RC5 sealed interfaces enforce correct type composition
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

All 419 tests should pass.

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
