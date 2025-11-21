# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java 25 monorepo for Fullerstack libraries, primarily focused on Kafka observability and OCPP (EV charging protocol) integration using the Humainary Substrates API for event-driven observability.

**Key Characteristics:**
- Multi-module Maven project with parent POM managing all versions
- **Requires Java 25** - Uses preview features (`--enable-preview` required)
- Implements semiotic observability using Substrates/Serventis APIs
- External dependency: Humainary Substrates API v1.0.0-PREVIEW (must be built from source)

## Installing Java 25

This project requires Java 25. Install using SDKMAN (recommended):

```bash
# Install SDKMAN if not already installed
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install Java 25
sdk install java 25.0.1-open

# Set as default
sdk default java 25.0.1-open

# Verify installation
java -version
# Should show: openjdk version "25.0.1"
```

**Alternative installations:**
- Download from [jdk.java.net](https://jdk.java.net/25/)
- Use your package manager (if Java 25 is available)

## Build Commands

### Prerequisites

**CRITICAL**: Before building, you must install Humainary PREVIEW dependencies locally:

```bash
# Clone and build Substrates API
git clone https://github.com/humainary-io/substrates-api-java.git
cd substrates-api-java
mvn clean install -DskipTests
cd ..

# Clone and build Fullerstack Substrates implementation
git clone https://github.com/fullerstack-io/fullerstack-humainary.git
cd fullerstack-humainary/fullerstack-substrates
mvn clean install -DskipTests
cd ../..
```

### Build and Test

```bash
# Build all modules (from repository root)
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Run all tests
mvn test

# Build specific module
cd fullerstack-kafka-producer
mvn clean install

# Run single test
mvn test -Dtest=ProducerHealthDetectorTest

# Run specific test method
mvn test -Dtest=ProducerHealthDetectorTest#testHealthDetection
```

### Running Demos

```bash
# Kafka Demo (requires Docker)
cd fullerstack-kafka-demo
docker-compose up -d
sleep 30  # Wait for Kafka cluster
mvn clean package -DskipTests

# Terminal 1: Monitoring dashboard
java -cp target/kafka-observability-demo.jar \
  io.fullerstack.kafka.demo.KafkaObservabilityDemoApplication

# Terminal 2: Producer with JMX
java -Dcom.sun.management.jmxremote.port=11001 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -Dcom.sun.management.jmxremote.rmi.port=11001 \
     -cp target/kafka-observability-demo.jar \
     io.fullerstack.kafka.demo.StandaloneProducer

# Open http://localhost:8080

# OCPP Demo
cd fullerstack-ocpp
mvn exec:java -Dexec.mainClass="io.fullerstack.ocpp.example.OcppDemo"
```

## Architecture

### Module Structure

```
fullerstack-kafka-core/       # Core abstractions, config, hierarchy management
fullerstack-kafka-broker/     # Broker monitoring (JMX metrics, 13 broker metrics)
fullerstack-kafka-producer/   # Producer monitoring (buffer, send, health)
fullerstack-kafka-consumer/   # Consumer monitoring (lag, metrics via AdminClient/JMX)
fullerstack-kafka-runtime/    # Runtime and observability integration
fullerstack-kafka-coordination/ # Distributed coordination primitives
fullerstack-kafka-msk/        # AWS MSK-specific integration
fullerstack-kafka-demo/       # WebSocket dashboard demo
fullerstack-ocpp/             # OCPP 1.6 Central System (EV charging)
```

### Substrates/Serventis Integration Pattern

This codebase implements **semiotic observability** using the Humainary Substrates API, transforming raw metrics into semantic signals following Boyd's OODA loop:

**5-Layer OODA Architecture:**

```
Layer 0: Raw Data (JMX metrics, OCPP messages, Kafka AdminClient data)
         ↓
Layer 1: OBSERVE - Probes/Observers collect and emit raw measurements
         Instruments: Probes, Observers, Collectors, Sensors
         Output: Counter, Gauge, Monitor emissions
         ↓
Layer 2: ORIENT - Monitors transform metrics into semantic signs
         Instruments: Monitors, Detectors, Trackers, Analyzers
         Output: Monitor.Sign (STABLE, DEGRADED, DOWN, ERRATIC, etc.)
         ↓
Layer 3: DECIDE - Reporters assess urgency and situation awareness
         Instruments: Reporters, Assessors
         Output: Reporter.Sign (NORMAL, WARNING, CRITICAL)
         ↓
Layer 4: ACT - Actors execute adaptive responses with speech acts
         Instruments: Actors, Controllers, Regulators
         Output: Actor.Sign (DELIVER, DENY, DEFER, etc.)
         ↓
Layer 5: Physical Actions (OCPP commands, Kafka configuration changes)
```

**Key Substrates Concepts:**

- **Circuit**: Container for related Cells/Conduits (e.g., "kafka-producers")
- **Cell**: Stateful signal processor with input → transformation → output
- **Conduit**: Stateless signal passthrough (pub/sub channel)
- **Name**: Hierarchical identifier using dotted notation (e.g., "cluster.broker-1.topic.p0")
- **Sign**: Semantic signal type (from Serventis instruments: Monitors.Sign, Reporters.Sign, etc.)
- **Composer**: Transformation function in Cell (e.g., metrics → Monitor.Sign)

**Serventis 12 Instruments (PREVIEW):**

```
Layer 1 (OBSERVE):  Probes, Observers, Collectors, Sensors
Layer 2 (ORIENT):   Monitors, Detectors, Trackers, Analyzers
Layer 3 (DECIDE):   Reporters, Assessors
Layer 4 (ACT):      Actors, Controllers
```

**Common Patterns:**

```java
// 1. Create Circuit
Circuit circuit = cortex().circuit(cortex().name("my-circuit"));

// 2. Create Conduit (stateless pub/sub)
Conduit<Monitors.Monitor, Monitors.Signal> monitors =
    circuit.conduit(cortex().name("monitors"), Monitors::composer);

// 3. Emit to Conduit
Monitors.Monitor monitor = monitors.percept(cortex().name("entity-id"));
monitor.stable();  // Emits Monitor.Sign.STABLE

// 4. Subscribe to Conduit
monitors.subscribe(cortex().subscriber(
    cortex().name("subscriber-id"),
    (subject, registrar) -> {
        registrar.register(signal -> {
            System.out.println("Received: " + signal.sign());
        });
    }
));

// 5. Create Cell hierarchy (stateful, with composer)
Cell<Monitors.Sign, Monitors.Sign> brokerCell =
    circuit.cell(
        cortex().name("broker-1"),
        Composer.pipe()  // Identity composer (passthrough)
    );

// Child emissions flow upward automatically
Cell<Monitors.Sign, Monitors.Sign> topicCell =
    brokerCell.cell(cortex().name("orders"), Composer.pipe());
```

### Kafka Module Architecture

**Hierarchical Cell Structure:**

```
Cluster (root Cell)
└── Broker Cells (one per broker, e.g., "broker-1")
    └── Topic Cells (one per topic, e.g., "orders")
        └── Partition Cells (one per partition, e.g., "p0")
```

Names are hierarchical: `"broker-1.orders.p0"` (depth=3)

**Data Collection Flow:**

```
JMX MBeans (broker metrics on port 11001)
    ↓
JmxMetricsCollector (Layer 1: OBSERVE)
    ↓ emits BrokerMetrics
ProducerCell/BrokerCell (hierarchy)
    ↓ transforms via Composer
MonitorSignal (Layer 2: ORIENT)
    ↓ STABLE/DEGRADED/DOWN
MonitorCellBridge (routes to Cell hierarchy)
    ↓
Reporters subscribe to Cells (Layer 3: DECIDE)
```

**Key Configuration:**

- `ClusterConfig`: Immutable config record for AWS MSK clusters
  - accountName/regionName/clusterName for hierarchical naming
  - bootstrapServers, jmxUrl, collectionIntervalMs
  - Factory methods: `ClusterConfig.of()`, `ClusterConfig.withDefaults()`
  - High-frequency monitoring: `ClusterConfig.withHighFrequencyMonitoring()` (enables JMX connection pooling)

**Package Conventions:**

- `sensors/` - Layer 1 (OBSERVE): JMX collectors, AdminClient sensors
- `monitors/` - Layer 2 (ORIENT): Health monitors, detectors
- `reporters/` - Layer 3 (DECIDE): Urgency assessors
- `actors/` - Layer 4 (ACT): Adaptive controllers
- `models/` - Domain data classes (metrics, health status)
- `config/` - Configuration records and builders
- `hierarchy/` - Cell hierarchy management (HierarchyManager)
- `bridge/` - Monitor conduit → Cell hierarchy bridge (MonitorCellBridge)

### OCPP Module Architecture

Implements OCPP 1.6 Central System for EV charger coordination:

**Two Modes:**
1. **Production**: `RealOcppCentralSystem` (uses ChargeTimeEU library, WebSocket server on port 8080)
2. **Testing**: `OcppCentralSystem` (simulated, for unit/integration tests)

**Signal Flow:**

```
OCPP Messages (BootNotification, StatusNotification, Heartbeat, etc.)
    ↓
OcppMessageObserver (Layer 1: translates OCPP → Substrates signals)
    ↓
ChargerConnectionMonitor (Layer 2: heartbeat monitoring)
    ↓ Monitor.Sign (STABLE, DEGRADED, DOWN)
ChargerHealthReporter (Layer 3: urgency assessment)
    ↓ Reporter.Sign (NORMAL, WARNING, CRITICAL)
ChargerDisableActor / TransactionStopActor (Layer 4: adaptive actions)
    ↓ Actor.Sign (DELIVER, DENY)
OCPP Commands to Chargers (ChangeAvailability, RemoteStopTransaction)
```

**Key Components:**
- `OcppObservabilitySystem`: Complete wired system
- `OcppRestApi`: REST API on port 9090 (GET /api/chargers, /api/transactions, /api/health)
- `OfflineStateManager`: Event sourcing for offline operation

## Development Guidelines

### Substrates API Usage (v1.0.0-PREVIEW)

**PREVIEW Key Features:**
- Static Cortex access: `cortex().circuit()`, `cortex().name()`, etc.
- Cells extend Pipe: Emit directly without `.pipe()` call
- Sealed interfaces: Type safety with restricted implementations
- 12 Serventis instruments with full OODA loop coverage

**Import Pattern:**

```java
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import static io.humainary.substrates.api.Substrates.cortex;
```

### Testing with Preview Features

All tests require `--enable-preview` and special JVM flags for Mockito/ByteBuddy:

```xml
<!-- pom.xml surefire configuration -->
<argLine>
    --enable-preview
    -XX:+EnableDynamicAgentLoading
    -Dnet.bytebuddy.experimental=true
    --add-opens java.base/java.lang=ALL-UNNAMED
    --add-opens java.base/java.util=ALL-UNNAMED
</argLine>
```

### JMX Metrics Collection

**Standard Pattern:**

```java
// For normal monitoring (30s interval):
ClusterConfig config = ClusterConfig.withDefaults(
    "localhost:9092",
    "localhost:11001"
);

// For high-frequency monitoring (<10s interval):
ClusterConfig config = ClusterConfig.withHighFrequencyMonitoring(
    "prod-account", "us-east-1", "cluster-name",
    "localhost:9092", "localhost:11001",
    5_000  // 5-second interval - enables connection pooling
);
```

**Connection Pooling**: Reduces overhead from 50-200ms to <5ms per cycle (90-95% reduction)

### Naming Conventions

**Hierarchical Names** (Substrates Name API):
- Use `cortex().name(List.of("account", "region", "cluster", "broker"))` for multi-level hierarchies
- Parse with `Name.depth()`, `Name.iterator()` (returns components in REVERSE order)
- Format: `"prod-account.us-east-1.transactions-cluster.b-1"`

**AWS MSK Broker IDs**:
- Extract from hostname: `"b-1.cluster.kafka.us-east-1.amazonaws.com"` → `"b-1"`
- Use `ClusterConfig.extractBrokerId(hostname)`

### Monitor Signs Mapping

**Common Sign Vocabulary:**

```
STABLE      - Normal operation
CONVERGING  - Approaching stable state
DIVERGING   - Moving away from stable
ERRATIC     - Unstable, oscillating behavior
DEGRADED    - Reduced performance/capability
DEFECTIVE   - Functional problem detected
DOWN        - Complete failure/offline
```

**Urgency Levels (Reporters):**

```
NORMAL      - No action needed
WARNING     - Monitoring/logging only
CRITICAL    - Requires immediate action (triggers Actors)
```

## Common Tasks

### Adding a New Kafka Module

1. Create module directory: `fullerstack-kafka-{module-name}/`
2. Add to parent `pom.xml` `<modules>` section
3. Create module `pom.xml` with parent reference
4. Follow package structure: `sensors/`, `monitors/`, `reporters/`, `models/`, `config/`
5. Add dependencyManagement entry in parent POM

### Adding a New Serventis Instrument

1. Create instrument class in appropriate package (e.g., `monitors/MyHealthMonitor.java`)
2. Import correct Serventis extension: `import io.humainary.substrates.ext.serventis.ext.Monitors;`
3. Create Conduit with appropriate composer: `circuit.conduit(name, Monitors::composer)`
4. Emit signs: `monitor.stable()`, `monitor.degraded()`, etc.
5. Wire into Cell hierarchy or subscribe to Conduit

### Running Tests with IntelliJ IDEA

Enable preview features in Run Configuration:
1. Run → Edit Configurations
2. Add VM options: `--enable-preview -XX:+EnableDynamicAgentLoading -Dnet.bytebuddy.experimental=true`
3. Add `--add-opens` flags as shown in parent POM surefire config

## Related Documentation

- **Humainary Substrates API**: https://github.com/humainary-io/substrates-api-java
- **Fullerstack Substrates Implementation**: https://github.com/fullerstack-io/fullerstack-humainary
- **Observability X Blog**: https://humainary.io/blog/category/observability-x/
- **Serventis Migration Guide**: `/docs/SERVENTIS-API-MIGRATION.md`
- **OCPP Deployment**: `/fullerstack-ocpp/DEPLOYMENT.md` (if exists)
- **Kafka Demo README**: `/fullerstack-kafka-demo/README.md`

## Dependencies Reference

```xml
<!-- Key versions (see pom.xml) -->
<java.version>25</java.version>
<substrates-api.version>1.0.0-PREVIEW</substrates-api.version>
<serventis-api.version>1.0.0-PREVIEW</serventis-api.version>
<kafka.version>3.8.0</kafka.version>
```

**IMPORTANT**: Substrates API artifacts are not in Maven Central. You must build them locally before building this project.
