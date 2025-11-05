# Substrates Bootstrap System - Developer Guide

**Version:** 1.0.0-SNAPSHOT
**API:** RC5
**Dependencies:** Zero (ResourceBundle + ServiceLoader only)

---

## Overview

The Substrates Bootstrap System provides **automatic, convention-based discovery and initialization** of circuits with zero configuration boilerplate.

**Key Features:**
- ✅ **ONE LINE bootstrap** - `SubstratesBootstrap.bootstrap()`
- ✅ **Convention-based discovery** - Scans classpath for `config_{circuit-name}.properties`
- ✅ **SPI extensibility** - Applications provide structure and sensors via ServiceLoader
- ✅ **Zero dependencies** - Uses only Java's built-in ResourceBundle and ServiceLoader
- ✅ **Hierarchical configuration** - Global → Circuit → Container → System Properties
- ✅ **Type-safe application layer** - Framework is type-agnostic, applications have full type safety

---

## Quick Start

### 1. Add Dependency

```xml
<dependency>
    <groupId>io.fullerstack</groupId>
    <artifactId>fullerstack-substrates</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Create Configuration File

**File:** `src/main/resources/config_my-circuit.properties`

```properties
# Enable this circuit
circuit.enabled=true

# Infrastructure settings
valve.queue-size=5000
signals.batch-size=500

# Application-specific config
my.app.setting=value
```

### 3. Implement CircuitStructureProvider

**File:** `src/main/java/com/example/MyStructureProvider.java`

```java
package com.example;

import io.fullerstack.substrates.spi.CircuitStructureProvider;
import io.fullerstack.substrates.bootstrap.BootstrapContext;
import io.fullerstack.substrates.config.HierarchicalConfig;
import io.humainary.substrates.api.Substrates.*;

import static io.humainary.substrates.api.Substrates.Composer.pipe;

public class MyStructureProvider implements CircuitStructureProvider {

    @Override
    public void buildStructure(
        String circuitName,
        Circuit circuit,
        Cortex cortex,
        HierarchicalConfig config,
        BootstrapContext context
    ) {
        if ("my-circuit".equals(circuitName)) {
            // Create typed cell
            Cell<MyInput, MyOutput> cell = circuit.cell(
                new MyComposer(),
                Pipe.empty()
            );

            // Create conduit for routing
            Conduit<MyOutput, MyOutput> outputConduit = circuit.conduit(
                cortex.name("output"),
                pipe()
            );

            // Wire subscription
            cell.subscribe(signal -> outputConduit.emit(signal));
        }
    }
}
```

### 4. Implement SensorProvider

**File:** `src/main/java/com/example/MySensorProvider.java`

```java
package com.example;

import io.fullerstack.substrates.spi.SensorProvider;
import io.fullerstack.substrates.config.HierarchicalConfig;
import java.util.List;

public class MySensorProvider implements SensorProvider {

    @Override
    public List<Sensor> getSensors(String circuitName) {
        if ("my-circuit".equals(circuitName)) {
            HierarchicalConfig config = HierarchicalConfig.forCircuit(circuitName);
            return List.of(new MyDataSensor(config));
        }
        return List.of();
    }

    private static class MyDataSensor implements Sensor {
        private final HierarchicalConfig config;

        MyDataSensor(HierarchicalConfig config) {
            this.config = config;
        }

        @Override
        public void start() {
            // Start collecting data and emitting to circuit
            System.out.println("Sensor started for circuit: " + config);
        }

        @Override
        public String name() {
            return "my-data-sensor";
        }

        @Override
        public void close() throws Exception {
            // Cleanup resources
        }
    }
}
```

### 5. Register SPI Providers

**File:** `src/main/resources/META-INF/services/io.fullerstack.substrates.spi.CircuitStructureProvider`
```
com.example.MyStructureProvider
```

**File:** `src/main/resources/META-INF/services/io.fullerstack.substrates.spi.SensorProvider`
```
com.example.MySensorProvider
```

### 6. Bootstrap Application

**File:** `src/main/java/com/example/Application.java`

```java
package com.example;

import io.fullerstack.substrates.bootstrap.SubstratesBootstrap;
import io.fullerstack.substrates.bootstrap.SubstratesBootstrap.BootstrapResult;

public class Application {

    public static void main(String[] args) {
        // ONE LINE - Everything happens automatically!
        BootstrapResult result = SubstratesBootstrap.bootstrap();

        System.out.println("Bootstrapped circuits: " + result.getCircuitNames());
        // Output: [my-circuit]

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                result.close();
            } catch (Exception e) {
                System.err.println("Shutdown error: " + e);
            }
        }));
    }
}
```

---

## SPI Interfaces

### CircuitStructureProvider

Applications build typed circuit structure (cells, conduits).

**Why needed:** Framework doesn't know application signal types at compile time.

**Example:**

```java
public interface CircuitStructureProvider {
    /**
     * Build structure for a circuit.
     *
     * @param circuitName Circuit name (e.g., "broker-health")
     * @param circuit Empty circuit to build structure in
     * @param cortex Cortex instance for creating names
     * @param config Circuit-specific hierarchical configuration
     */
    void buildStructure(
        String circuitName,
        Circuit circuit,
        Cortex cortex,
        HierarchicalConfig config
    );
}
```

**Implementation pattern:**

```java
public class MyStructureProvider implements CircuitStructureProvider {

    @Override
    public void buildStructure(
        String circuitName,
        Circuit circuit,
        Cortex cortex,
        HierarchicalConfig config
    ) {
        // Switch on circuit name - one provider handles all your circuits
        switch (circuitName) {
            case "circuit-a" -> buildCircuitA(circuit, cortex, config);
            case "circuit-b" -> buildCircuitB(circuit, cortex, config);
        }
    }

    private void buildCircuitA(Circuit circuit, Cortex cortex, HierarchicalConfig config) {
        // Create cells
        Cell<InputType, OutputType> cell = circuit.cell(
            new MyComposer(),
            Pipe.empty()
        );

        // Create child cells (Cell IS-A Container)
        Cell<InputType, OutputType> child = cell.get(cortex.name("child"));

        // Create conduits
        Conduit<OutputType, OutputType> conduit = circuit.conduit(
            cortex.name("output"),
            Composer.pipe()
        );

        // Wire subscriptions
        cell.subscribe(signal -> conduit.emit(signal));
    }
}
```

### SensorProvider

Applications provide signal emitters.

**Example:**

```java
public interface SensorProvider {
    /**
     * Get sensors for a circuit.
     *
     * @param circuitName Circuit name
     * @return List of sensors to start (empty if circuit unknown)
     */
    List<Sensor> getSensors(String circuitName);

    /**
     * Sensor lifecycle interface.
     */
    interface Sensor extends AutoCloseable {
        void start();
        String name();
        void close() throws Exception;
    }
}
```

**Implementation pattern:**

```java
public class MySensorProvider implements SensorProvider {

    @Override
    public List<Sensor> getSensors(String circuitName) {
        return switch (circuitName) {
            case "my-circuit" -> createMyCircuitSensors(circuitName);
            default -> List.of();  // Unknown circuit
        };
    }

    private List<Sensor> createMyCircuitSensors(String circuitName) {
        HierarchicalConfig config = HierarchicalConfig.forCircuit(circuitName);

        return List.of(
            new JmxSensor(config),
            new MetricsSensor(config)
        );
    }
}
```

### ComposerProvider (Optional)

Applications can optionally provide composers via SPI.

**Note:** Usually not needed - most applications create composers directly in `CircuitStructureProvider`.

---

## Configuration System

### Hierarchical Fallback

Configuration uses a 4-level hierarchy:

```
System Properties (-Dkey=value)           ← HIGHEST PRIORITY
   ↓
Container Config (config_{circuit}-{container}.properties)
   ↓
Circuit Config (config_{circuit}.properties)
   ↓
Global Config (config.properties)         ← LOWEST PRIORITY
```

### File Naming Convention

```
config.properties                          ← Global defaults
config_{circuit-name}.properties           ← Circuit-specific
config_{circuit-name}-{container}.properties  ← Container-specific
```

### Property Namespaces

Use dotted namespaces to organize properties:

```properties
# Substrates infrastructure
valve.queue-size=5000
valve.shutdown-timeout-ms=10000
signals.batch-size=500

# Application-specific
myapp.broker-ids=b-1,b-2,b-3
myapp.timeout-ms=30000
```

### Usage

```java
// Global config
HierarchicalConfig global = HierarchicalConfig.global();
int queueSize = global.getInt("valve.queue-size", 1000);

// Circuit-specific config (inherits from global)
HierarchicalConfig circuit = HierarchicalConfig.forCircuit("my-circuit");
int circuitQueueSize = circuit.getInt("valve.queue-size", 1000);

// Container-specific config (inherits from circuit → global)
HierarchicalConfig container = HierarchicalConfig.forContainer("my-circuit", "brokers");
int containerQueueSize = container.getInt("valve.queue-size", 1000);

// System property override (highest priority)
// java -Dvalve.queue-size=20000 -jar app.jar
```

---

## Bootstrap Process

### Automatic Flow

```
1. CircuitDiscovery.discoverCircuits()
   ↓ Scans classpath for config_*.properties
   ↓ Filters by circuit.enabled=true
   ↓ Returns Set<String> circuit names

2. ServiceLoader.load(CircuitStructureProvider.class)
   ↓ Discovers SPI implementations

3. ServiceLoader.load(SensorProvider.class)
   ↓ Discovers SPI implementations

4. For each circuit:
   a. Create Cortex (singleton)
   b. Create empty Circuit
   c. Call CircuitStructureProvider.buildStructure()
      → Application creates cells, conduits with typed composers
   d. Call SensorProvider.getSensors()
      → Get sensors for this circuit
   e. Call Sensor.start() on each sensor
      → Sensors begin emitting signals

5. Return BootstrapResult
   → Contains all circuits and sensors
   → Implements AutoCloseable for shutdown
```

### Custom Bootstrap Configuration

```java
BootstrapResult result = SubstratesBootstrap.builder()
    .onCircuitCreated((name, circuit) -> {
        System.out.println("Created circuit: " + name);
    })
    .onSensorStarted((circuitName, sensor) -> {
        System.out.println("Started sensor: " + sensor.name());
    })
    .onError((circuitName, error) -> {
        System.err.println("Error in " + circuitName + ": " + error);
    })
    .bootstrap();
```

---

## Complete Example: Kafka Observability

### Configuration

**File:** `config_broker-health.properties`

```properties
circuit.enabled=true

# Substrates infrastructure
valve.queue-size=5000
signals.batch-size=500

# Application config
kafka.broker-ids=b-1,b-2,b-3
kafka.bootstrap-servers=localhost:9092
jmx.port=11001
```

### Structure Provider

```java
public class KafkaObsStructureProvider implements CircuitStructureProvider {

    @Override
    public void buildStructure(
        String circuitName,
        Circuit circuit,
        Cortex cortex,
        HierarchicalConfig config
    ) {
        if ("broker-health".equals(circuitName)) {
            buildBrokerHealthCircuit(circuit, cortex, config);
        }
    }

    private void buildBrokerHealthCircuit(
        Circuit circuit,
        Cortex cortex,
        HierarchicalConfig config
    ) {
        // Create parent cell for cluster-level aggregation
        Cell<BrokerMetrics, MonitorSignal> clusterCell = circuit.cell(
            new BrokerHealthCellComposer(),
            Pipe.empty()
        );

        // Create child cells for each broker
        String brokerIds = config.getString("kafka.broker-ids", "");
        for (String brokerId : brokerIds.split(",")) {
            Cell<BrokerMetrics, MonitorSignal> brokerCell =
                clusterCell.get(cortex.name(brokerId.trim()));
        }

        // Create conduits for signal routing
        Conduit<MonitorSignal, MonitorSignal> metrics = circuit.conduit(
            cortex.name("metrics"),
            Composer.pipe()
        );

        Conduit<MonitorSignal, MonitorSignal> alerts = circuit.conduit(
            cortex.name("alerts"),
            Composer.pipe()
        );

        // Wire subscriptions
        clusterCell.subscribe(signal -> {
            metrics.emit(signal);
            if (signal.requiresAttention()) {
                alerts.emit(signal);
            }
        });
    }
}
```

### Sensor Provider

```java
public class KafkaObsSensorProvider implements SensorProvider {

    @Override
    public List<Sensor> getSensors(String circuitName) {
        if ("broker-health".equals(circuitName)) {
            HierarchicalConfig config = HierarchicalConfig.forCircuit(circuitName);
            return List.of(new JmxMonitoringSensor(config));
        }
        return List.of();
    }

    private static class JmxMonitoringSensor implements Sensor {
        private final BrokerMonitoringAgent agent;

        JmxMonitoringSensor(HierarchicalConfig config) {
            String servers = config.getString("kafka.bootstrap-servers");
            int jmxPort = config.getInt("jmx.port");
            this.agent = new BrokerMonitoringAgent(servers, jmxPort);
        }

        @Override
        public void start() {
            agent.start();
        }

        @Override
        public String name() {
            return "jmx-monitoring";
        }

        @Override
        public void close() throws Exception {
            agent.close();
        }
    }
}
```

### Application

```java
public class KafkaObsApplication {

    public static void main(String[] args) {
        // ONE LINE bootstrap
        BootstrapResult result = SubstratesBootstrap.bootstrap();

        System.out.println("Circuits: " + result.getCircuitNames());
        // Output: [broker-health]

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                result.close();
            } catch (Exception e) {
                System.err.println("Shutdown error: " + e);
            }
        }));
    }
}
```

---

## Best Practices

### 1. Single Provider Per Application

Use **one** CircuitStructureProvider that handles all your circuits:

```java
public class MyAppStructureProvider implements CircuitStructureProvider {
    @Override
    public void buildStructure(...) {
        switch (circuitName) {
            case "circuit-a" -> buildA(...);
            case "circuit-b" -> buildB(...);
            case "circuit-c" -> buildC(...);
        }
    }
}
```

**Why:** All application logic in one place, single SPI registration.

### 2. Use Property Namespaces

Organize properties with dotted prefixes:

```properties
# Infrastructure (framework)
valve.queue-size=5000

# Application domain
myapp.timeout-ms=30000
myapp.max-retries=3

# External systems
kafka.broker-ids=b-1,b-2,b-3
jmx.port=11001
```

### 3. Circuit-Specific Overrides

Use circuit-specific files for different configurations:

```properties
# config_high-volume.properties
valve.queue-size=10000
signals.batch-size=1000

# config_low-latency.properties
valve.queue-size=500
signals.batch-size=50
```

### 4. System Property Overrides

Allow runtime tuning via system properties:

```bash
java -Dvalve.queue-size=20000 -jar app.jar
```

### 5. Resource Management

Always use try-with-resources or shutdown hooks:

```java
try (BootstrapResult result = SubstratesBootstrap.bootstrap()) {
    // Application runs...
} // Automatic cleanup
```

---

## Testing

### Unit Testing Structure Providers

```java
@Test
void testBuildStructure() {
    Cortex cortex = CortexRuntime.cortex();
    Circuit circuit = cortex.circuit(cortex.name("test-circuit"));
    HierarchicalConfig config = HierarchicalConfig.forCircuit("test-circuit");
    BootstrapContext context = new BootstrapContext();

    MyStructureProvider provider = new MyStructureProvider();
    provider.buildStructure("test-circuit", circuit, cortex, config, context);

    // Verify structure was built
    assertThat(circuit).isNotNull();
}
```

### Integration Testing Bootstrap

```java
@Test
void testBootstrap() {
    BootstrapResult result = SubstratesBootstrap.bootstrap();

    assertThat(result.getCircuitNames()).contains("test-circuit");
    assertThat(result.getSensors()).isNotEmpty();

    result.close();
}
```

---

## See Also

- [Architecture Guide](ARCHITECTURE.md) - Overall architecture
- [Developer Guide](DEVELOPER-GUIDE.md) - Development best practices
- [Async Architecture](ASYNC-ARCHITECTURE.md) - Event-driven patterns
