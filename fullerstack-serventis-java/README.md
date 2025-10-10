# Fullerstack Serventis Signals (Java)

Concrete signal type implementations for the [Humainary Serventis](https://github.com/humainary-io/serventis-api-java) observability framework.

## Overview

This library provides **immutable Java record implementations** for all six Serventis signal types, designed for use with the [Substrates event processing framework](../fullerstack-substrates-java).

### What is Serventis?

**Serventis** is a semiotic-inspired observability framework that provides structured sensing and sense-making for distributed systems. It defines a contract for monitoring system states and service interactions through a standardized language of signals and assessments.

**Key Principle:** Separating observation from interpretation

---

## The Humainary Stack

```
┌─────────────────────────────────────────────────────┐
│      Application Layer (kafka-obs, etc.)            │
└──────────────────┬──────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────┐
│  Serventis (Semantic Layer) ← THIS LIBRARY          │
│  • 6 signal type APIs (semantic contracts)          │
│  • Immutable Java records                           │
│  • Semiotic framework for meaning-making            │
└──────────────────┬──────────────────────────────────┘
                   ↓
┌─────────────────────────────────────────────────────┐
│  Substrates (Infrastructure Layer)                  │
│  • Circuits, Conduits, Channels                     │
│  • Event processing engine                          │
│  • Signal flow infrastructure                       │
└─────────────────────────────────────────────────────┘
```

---

## Signal Types

This library implements all six Serventis signal types:

### 1. MonitorSignal (Monitors API)

**Purpose:** Track operational condition of services and resources

**Statuses:** `STABLE`, `DEGRADED`, `DOWN`

**Use Cases:**
- JVM heap memory usage
- GC pause times
- Disk health
- Network throughput
- Request latency

**Example:**
```java
MonitorSignal signal = new MonitorSignal(
    UUID.randomUUID(),
    "kafka.broker.health",
    "broker-1.jvm.heap",
    Instant.now(),
    new VectorClock(Map.of("broker-1", 42L)),
    MonitorStatus.DEGRADED,
    Map.of("heapUsed", "85%", "threshold", "90%")
);
```

### 2. ServiceSignal (Services API)

**Purpose:** Capture service-to-service interactions

**Statuses:** `COMPLETED`, `FAILED`, `TIMEOUT`

**Use Cases:**
- Producer send operations
- Consumer poll operations
- API calls
- Database transactions
- Message processing

**Example:**
```java
ServiceSignal signal = new ServiceSignal(
    UUID.randomUUID(),
    "kafka.client.interactions",
    "orders.0.producer.order-service",
    Instant.now(),
    new VectorClock(Map.of("order-service", 15L)),
    ServiceStatus.COMPLETED,
    Map.of("latency", "45ms", "recordSize", "2048")
);
```

### 3. QueueSignal (Queues API)

**Purpose:** Monitor queue-like system interactions

**Statuses:** `NORMAL`, `LAGGING`, `STALLED`

**Use Cases:**
- Consumer lag
- Partition depth
- Message backlogs
- Processing queues

**Example:**
```java
QueueSignal signal = new QueueSignal(
    UUID.randomUUID(),
    "kafka.partition.behavior",
    "orders.0.lag",
    Instant.now(),
    new VectorClock(Map.of("partition-sensor", 100L)),
    QueueStatus.LAGGING,
    Map.of("lag", "5000", "rate", "100/s")
);
```

### 4. ReporterSignal (Reporters API)

**Purpose:** Report situational assessments and aggregated conditions

**Severities:** `INFO`, `WARNING`, `CRITICAL`

**Use Cases:**
- Cluster degradation
- Cascading failures
- Capacity overflow predictions
- Health summaries

**Example:**
```java
ReporterSignal signal = new ReporterSignal(
    UUID.randomUUID(),
    "kafka.situations",
    "cluster.situation",
    Instant.now(),
    new VectorClock(Map.of("health-aggregator", 250L)),
    ReporterSeverity.CRITICAL,
    Map.of(
        "pattern", "cluster-degradation",
        "affectedBrokers", "broker-1,broker-2"
    )
);
```

### 5. ProbeSignal (Probes API)

**Purpose:** Monitor communication outcomes in distributed systems

**Use Cases:**
- Network connectivity checks
- Endpoint health probes
- Service discovery
- Heartbeat monitoring

### 6. ResourceSignal (Resources API)

**Purpose:** Emit signals describing shared resource interactions

**Use Cases:**
- Database connection pools
- Thread pools
- File handles
- Memory allocations

---

## Architecture

### Signal Base Interface

All signal types implement the common `Signal` interface:

```java
public interface Signal {
    UUID id();                    // Unique identifier
    String circuit();             // Circuit name for routing
    String channel();             // Channel within circuit
    Instant timestamp();          // When signal was emitted
    VectorClock vectorClock();    // Causal ordering
    Map<String, String> payload(); // Additional metadata
}
```

### VectorClock

Enables causal ordering across distributed agents:

```java
public record VectorClock(Map<String, Long> clocks) {
    public long toLong() {
        return clocks.values().stream()
            .max(Long::compare)
            .orElse(0L);
    }
}
```

### Immutability

All signals are **immutable Java records** for:
- Thread-safety
- Value semantics
- Pattern matching support
- Clear data contracts

---

## Integration with Substrates

Signals flow through Substrates infrastructure:

```java
// 1. Create Circuit
Circuit circuit = cortex.circuit(cortex.name("kafka.broker.health"));

// 2. Create Conduit for MonitorSignals
Conduit<Pipe<MonitorSignal>, MonitorSignal> monitors =
    circuit.conduit(cortex.name("monitors"), Composer.pipe());

// 3. Get Channel Pipe
Pipe<MonitorSignal> heapPipe =
    monitors.get(cortex.name("broker-1.jvm.heap"));

// 4. Emit Signal
MonitorSignal signal = new MonitorSignal(...);
heapPipe.emit(signal);

// 5. Subscribe to Signals
monitors.source().subscribe(
    cortex.subscriber(
        cortex.name("cluster-health-aggregator"),
        (subject, registrar) -> {
            registrar.register(s -> {
                // Process signal, emit higher-level assessments
            });
        }
    )
);
```

---

## Philosophical Foundations

### Semiotics (Charles Sanders Peirce)

**Triadic Sign Model:**
1. **Signal** (Firstness) - Raw observation
2. **Interpretation** (Secondness) - Contextual understanding
3. **Meaning** (Thirdness) - Crystallized assessment

### Cybernetics

**Feedback Loops:**
- Signals enable self-regulation
- Observers interpret and adapt
- Recursive meaning-making

### The Semiotic Loop

```
Signal → Observer → Condition → Action → New Signal → ...
  ↓         ↓          ↓
Raw     Context    Meaning
```

---

## Dependencies

### Humainary Serventis APIs

```xml
<dependencies>
    <!-- Monitors API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.monitors</groupId>
        <artifactId>humainary-modules-serventis-monitors-api</artifactId>
        <version>1.0.0-M13</version>
    </dependency>

    <!-- Services API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.services</groupId>
        <artifactId>humainary-modules-serventis-services-api</artifactId>
        <version>1.0.0-M13</version>
    </dependency>

    <!-- Queues API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.queues</groupId>
        <artifactId>humainary-modules-serventis-queues-api</artifactId>
        <version>1.0.0-M13</version>
    </dependency>

    <!-- Reporters API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.reporters</groupId>
        <artifactId>humainary-modules-serventis-reporters-api</artifactId>
        <version>1.0.0-M13</version>
    </dependency>

    <!-- Probes API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.probes</groupId>
        <artifactId>humainary-modules-serventis-probes-api</artifactId>
        <version>1.0.0-M13</version>
    </dependency>

    <!-- Resources API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.resources</groupId>
        <artifactId>humainary-modules-serventis-resources-api</artifactId>
        <version>1.0.0-M13</version>
    </dependency>
</dependencies>
```

---

## Maven Usage

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>io.fullerstack</groupId>
    <artifactId>serventis-signals-java</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## Requirements

- **Java 24** (with preview features enabled)
- **Humainary Serventis APIs** (1.0.0-M13)

---

## Project Structure

```
fullerstack-serventis-java/
├── src/
│   ├── main/java/io/fullerstack/serventis/signals/
│   │   ├── Signal.java              # Base interface
│   │   ├── VectorClock.java         # Causal ordering
│   │   ├── MonitorSignal.java       # Monitors API impl
│   │   ├── ServiceSignal.java       # Services API impl
│   │   ├── QueueSignal.java         # Queues API impl
│   │   ├── ReporterSignal.java      # Reporters API impl
│   │   ├── ProbeSignal.java         # Probes API impl
│   │   └── ResourceSignal.java      # Resources API impl
│   └── test/java/io/fullerstack/serventis/signals/
│       └── (unit tests)
├── docs/
│   └── HUMAINARY_ALIGNMENT_ANALYSIS.md
├── pom.xml
└── README.md
```

---

## Related Projects

- **[fullerstack-substrates-java](../fullerstack-substrates-java)** - Event processing infrastructure
- **[kafka-obs](../../kafka-obs)** - Kafka observability application using these signals
- **[Humainary Substrates API](https://github.com/humainary-io/substrates-api-java)** - Upstream API
- **[Humainary Serventis API](https://github.com/humainary-io/serventis-api-java)** - Upstream API

---

## Further Reading

- [Humainary Alignment Analysis](docs/HUMAINARY_ALIGNMENT_ANALYSIS.md) - Detailed explanation of Serventis vs Signetics
- [Serventis Blog Post](https://humainary.io/blog/serventis-a-semiotic-framework-for-observability/)
- [The Semiotic Loop](https://humainary.io/blog/the-semiotic-loop-cybernetics-meaning-and-substrates/)
- [Fullerstack Substrates README](../fullerstack-substrates-java/README.md)

---

## License

Apache License 2.0

---

## Credits

**Humainary Initiative** - William Louth's vision of cybersemiotic observability

**Fullerstack** - Concrete implementations aligned with Humainary's architectural principles
