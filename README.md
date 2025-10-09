# Fullerstack

Event-driven runtime and observability framework for Java 24.

## Overview

**Fullerstack** is a modern Java framework combining:
- **Substrates**: Pure event-driven runtime implementing the Humainary Substrates API
- **Serventis**: Signal-based observability domain models

Built on Java 24 with virtual threads, structured concurrency, and modern language features.

## Modules

### fullerstack-substrates-java
Pure implementation of the [Humainary Substrates API](https://github.com/Humainary/substrates-java):
- **Circuit**: Event-driven orchestration
- **Queue**: Backpressure management with virtual threads
- **Clock**: Periodic event timing
- **Source**: Event emission and subscriber management
- **Conduit**: Event transformation pipelines
- **Container**: Pool and Source composition

**Dependencies**: Only Substrates API (no Spring, no Kafka, no Redis)

### fullerstack-serventis-java
Signal-based observability domain models:
- **Signal**: Base event types
- **Percept**: Observation patterns
- **Situation**: Derived insights
- **Reporter**: Event output

**Dependencies**: Only Substrates API

### kafka-fullerstack
Kafka observability platform built on Fullerstack:
- Partition health monitoring
- Consumer lag tracking
- Cluster health aggregation
- Failure pattern detection
- Capacity planning with forecasting
- Tiered storage (Redis + EBS)

**Dependencies**: Fullerstack Substrates, Fullerstack Serventis, Spring Boot, Kafka, Redis, RocksDB

## Requirements

- **Java 24** (or higher)
- Maven 3.8+

## Building

```bash
# Build all modules
mvn clean install

# Build specific module
cd fullerstack-substrates-java
mvn clean install

# Run tests
mvn test

# Skip tests
mvn clean install -DskipTests
```

## Usage

### Using Fullerstack Substrates

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>io.fullerstack</groupId>
    <artifactId>fullerstack-substrates-java</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Example:

```java
import io.fullerstack.substrates.CortexRuntime;
import io.humainary.substrates.api.Substrates.*;

Cortex cortex = new CortexRuntime();
Circuit circuit = cortex.circuit(cortex.name("my-app"));

// Use queue for script execution
circuit.queue().post(current -> {
    System.out.println("Processing event");
});

// Subscribe to clock for periodic events
circuit.clock().consume(
    cortex.name("ticker"),
    Clock.Cycle.SECOND,
    instant -> System.out.println("Tick: " + instant)
);
```

### Using Fullerstack Serventis

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>io.fullerstack</groupId>
    <artifactId>fullerstack-serventis-java</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Architecture

```
fullerstack-java/
├── fullerstack-substrates-java/    # Pure runtime (no dependencies)
│   └── io.fullerstack.substrates
├── fullerstack-serventis-java/     # Pure domain models
│   └── io.fullerstack.serventis
└── kafka-fullerstack/              # Kafka application
    └── io.fullerstack.kafka
```

**Dependency Graph:**
```
kafka-fullerstack
    ├── fullerstack-substrates-java
    └── fullerstack-serventis-java
```

## Java 24 Features Used

- **Virtual Threads**: Lightweight concurrency for Queue processing
- **Records**: Immutable data carriers
- **Pattern Matching**: Cleaner type checks
- **Structured Concurrency**: Coordinated task lifecycle
- **Preview Features**: Enabled via `--enable-preview`

## Testing

All modules include comprehensive test coverage:
- Unit tests with JUnit 5
- Integration tests
- Concurrency tests
- Edge case validation

Run tests:
```bash
mvn test
```

## License

[Add your license here]

## Links

- Website: https://fullerstack.io
- Humainary Substrates API: https://github.com/Humainary/substrates-java
