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

**Factory Method Example:**
```java
Subject brokerHeap = Cortex.subject(
    Cortex.name("kafka.broker.health"),
    Cortex.name("broker-1.jvm.heap")
);

MonitorSignal signal = MonitorSignal.degraded(
    brokerHeap,
    Monitors.Confidence.CONFIRMED,
    Map.of("heapUsed", "85%", "threshold", "90%")
);
```

**Builder Pattern Example:**
```java
MonitorSignal signal = MonitorSignal.builder()
    .subject(brokerHeap)
    .degraded()  // Sets condition
    .confidence(Monitors.Confidence.HIGH)
    .vectorClock(new VectorClock(Map.of("broker-1", 42L)))
    .addPayload("heapUsed", "85%")
    .addPayload("threshold", "90%")
    .build();
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

**Factory Method Example:**
```java
Subject producerSubject = Cortex.subject(
    Cortex.name("kafka.client.interactions"),
    Cortex.name("orders.0.producer.order-service")
);

ServiceSignal signal = ServiceSignal.succeed(
    producerSubject,
    Map.of("latency", "45ms", "recordSize", "2048")
);
```

**Builder Pattern Example:**
```java
ServiceSignal signal = ServiceSignal.builder()
    .subject(producerSubject)
    .succeed()  // Sets Services.Signal.SUCCESS
    .vectorClock(new VectorClock(Map.of("order-service", 15L)))
    .addPayload("latency", "45ms")
    .addPayload("recordSize", "2048")
    .build();
```

### 3. QueueSignal (Queues API)

**Purpose:** Monitor queue-like system interactions

**Statuses:** `NORMAL`, `LAGGING`, `STALLED`

**Use Cases:**
- Consumer lag
- Partition depth
- Message backlogs
- Processing queues

**Factory Method Example:**
```java
Subject lagSubject = Cortex.subject(
    Cortex.name("kafka.partition.behavior"),
    Cortex.name("orders.0.lag")
);

QueueSignal signal = QueueSignal.lagging(
    lagSubject,
    Map.of("lag", "5000", "rate", "100/s")
);
```

**Builder Pattern Example:**
```java
QueueSignal signal = QueueSignal.builder()
    .subject(lagSubject)
    .take()  // Sets Queues.Sign.TAKE
    .vectorClock(new VectorClock(Map.of("partition-sensor", 100L)))
    .addPayload("lag", "5000")
    .addPayload("rate", "100/s")
    .build();
```

### 4. ReporterSignal (Reporters API)

**Purpose:** Report situational assessments and aggregated conditions

**Severities:** `INFO`, `WARNING`, `CRITICAL`

**Use Cases:**
- Cluster degradation
- Cascading failures
- Capacity overflow predictions
- Health summaries

**Factory Method Example:**
```java
Subject situationSubject = Cortex.subject(
    Cortex.name("kafka.situations"),
    Cortex.name("cluster.situation")
);

ReporterSignal signal = ReporterSignal.critical(
    situationSubject,
    Map.of(
        "pattern", "cluster-degradation",
        "affectedBrokers", "broker-1,broker-2"
    )
);
```

**Builder Pattern Example:**
```java
ReporterSignal signal = ReporterSignal.builder()
    .subject(situationSubject)
    .critical()  // Sets Reporters.Situation.CRITICAL
    .vectorClock(new VectorClock(Map.of("health-aggregator", 250L)))
    .addPayload("pattern", "cluster-degradation")
    .addPayload("affectedBrokers", "broker-1,broker-2")
    .build();
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
    String subject();             // Subject name (aligns with Substrates terminology)
    Instant timestamp();          // When signal was emitted
    VectorClock vectorClock();    // Causal ordering
    Map<String, String> payload(); // Additional metadata
}
```

**Note:** `subject()` aligns with Substrates' `Conduit.get(Name subject)` pattern, where the subject is the semantic identity that a Channel routes to.

### VectorClock

Enables causal ordering across distributed agents:

```java
public record VectorClock(Map<String, Long> clocks) {

    // Increment clock for an actor
    VectorClock increment(String actor);

    // Test if this clock happened before another
    boolean happenedBefore(VectorClock other);

    // Test if two clocks are concurrent
    boolean concurrent(VectorClock other);

    // Merge two clocks (takes max of each actor)
    VectorClock merge(VectorClock other);

    // Convert to single timestamp (loses causal info)
    long toLong();
}
```

**Causal Ordering Example:**
```java
VectorClock vc1 = new VectorClock(Map.of("broker-1", 10L, "broker-2", 5L));
VectorClock vc2 = new VectorClock(Map.of("broker-1", 12L, "broker-2", 5L));

vc1.happenedBefore(vc2);  // true - vc1 causally precedes vc2
vc1.concurrent(vc2);       // false - there's a causal relationship
VectorClock merged = vc1.merge(vc2);  // {broker-1: 12, broker-2: 5}
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

// 3. Get Pipe for specific subject
Pipe<MonitorSignal> heapPipe =
    monitors.get(cortex.name("broker-1.jvm.heap"));  // Subject name

// 4. Emit Signal with matching subject
MonitorSignal signal = MonitorSignal.degraded(
    "kafka.broker.health",           // circuit
    "broker-1.jvm.heap",  // subject              // subject (matches Conduit.get() call)
    Monitors.Confidence.CONFIRMED,
    Map.of("heapUsed", "85%")
);
heapPipe.emit(signal);

// 5. Subscribe to Signals (Conduit extends Source in M17 sealed hierarchy)
monitors.subscribe(
    cortex.subscriber(
        cortex.name("cluster-health-aggregator"),
        (subject, registrar) -> {  // subject parameter = Name from Conduit.get()
            registrar.register(s -> {
                // Process signal, emit higher-level assessments
                if (s.subject().equals("broker-1.jvm.heap")) {
                    // React to specific subject's signals
                }
            });
        }
    )
);
```

**Key Alignment:**
- `Signal.subject()` corresponds to the `Name` passed to `Conduit.get(Name subject)`
- `Signal.circuit()` corresponds to the `Circuit` name
- The subject is the semantic identity, the Channel/Pipe is the infrastructure

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
        <version>1.0.0-M18</version>
    </dependency>

    <!-- Services API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.services</groupId>
        <artifactId>humainary-modules-serventis-services-api</artifactId>
        <version>1.0.0-M18</version>
    </dependency>

    <!-- Queues API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.queues</groupId>
        <artifactId>humainary-modules-serventis-queues-api</artifactId>
        <version>1.0.0-M18</version>
    </dependency>

    <!-- Reporters API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.reporters</groupId>
        <artifactId>humainary-modules-serventis-reporters-api</artifactId>
        <version>1.0.0-M18</version>
    </dependency>

    <!-- Probes API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.probes</groupId>
        <artifactId>humainary-modules-serventis-probes-api</artifactId>
        <version>1.0.0-M18</version>
    </dependency>

    <!-- Resources API -->
    <dependency>
        <groupId>io.humainary.modules.serventis.resources</groupId>
        <artifactId>humainary-modules-serventis-resources-api</artifactId>
        <version>1.0.0-M18</version>
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

- **Java 25** or higher (LTS)
- **Humainary Serventis APIs** (1.0.0-M18)

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

## Configuration

Serventis uses Substrates' hierarchical configuration system for health thresholds and other settings.

### HealthThresholds

Circuit-specific health assessment thresholds:

```java
import io.fullerstack.serventis.config.HealthThresholds;

// Load circuit-specific thresholds
HealthThresholds thresholds = HealthThresholds.forCircuit("broker-health");

// Use thresholds for signal assessment
double heapUsage = jmxClient.getHeapUsage();
if (heapUsage > thresholds.heapDegraded()) {
    signal = MonitorSignal.degraded(subject, ...);
} else if (heapUsage > thresholds.heapStable()) {
    signal = MonitorSignal.converging(subject, ...);
} else {
    signal = MonitorSignal.stable(subject, ...);
}
```

### Configuration Files

**Global defaults** (`config.properties`):
```properties
health.thresholds.heap.stable=0.75
health.thresholds.heap.degraded=0.90
health.thresholds.cpu.stable=0.70
health.thresholds.cpu.degraded=0.85
```

**Circuit-specific overrides** (`config_broker-health.properties`):
```properties
# Brokers can tolerate higher heap
health.thresholds.heap.stable=0.80
health.thresholds.heap.degraded=0.95
```

**Container-specific overrides** (`config_broker-health-brokers.properties`):
```properties
# Specific brokers have even higher tolerance
health.thresholds.heap.stable=0.85
```

See [Substrates Bootstrap Guide](../fullerstack-substrates/docs/BOOTSTRAP-GUIDE.md) for complete configuration documentation.

---

## Further Reading

- [Humainary Alignment Analysis](docs/HUMAINARY_ALIGNMENT_ANALYSIS.md) - Detailed explanation of Serventis vs Signetics
- [Serventis Blog Post](https://humainary.io/blog/serventis-a-semiotic-framework-for-observability/)
- [The Semiotic Loop](https://humainary.io/blog/the-semiotic-loop-cybernetics-meaning-and-substrates/)
- [Fullerstack Substrates README](../fullerstack-substrates-java/README.md)
- [Substrates Bootstrap Guide](../fullerstack-substrates/docs/BOOTSTRAP-GUIDE.md)

---

## License

Apache License 2.0

---

## Credits

**Humainary Initiative** - William Louth's vision of cybersemiotic observability

**Fullerstack** - Concrete implementations aligned with Humainary's architectural principles
