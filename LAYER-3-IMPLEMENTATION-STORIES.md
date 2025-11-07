# Layer 3 Implementation Stories - Parallel Execution Plan

**Epic**: Add Cell Hierarchy + Bridge + Reporters on top of existing 103 measurements

**Status**: Ready for implementation
**Date**: 2025-11-07
**Dependencies**: 103 Monitor measurements already implemented (Layer 2 complete)

---

## Architecture Overview

**Current State** (Layer 2 Complete):
```
Layer 1 (OBSERVE): Gauges/Counters/Queues/Services/Probes conduits
          ↓
Layer 2 (ORIENT): 103 Monitor classes → Monitor conduits ← WE ARE HERE
```

**Target State** (Layer 3):
```
Layer 1 (OBSERVE): Gauges/Counters/Queues/Services/Probes conduits
          ↓
Layer 2 (ORIENT): 103 Monitor classes → Monitor conduits
          ↓
          Bridge → Cell<Monitors.Sign, Monitors.Sign> hierarchy
          ↓
Layer 3 (DECIDE): Reporters → Reporter conduits
```

---

## Parallelization Strategy

### Track A: Infrastructure (Foundation - Do First)
- **Story 3.1**: Cell hierarchy infrastructure
- **Blocks**: All other stories

### Track B: Bridge Layer (Can parallelize after 3.1)
- **Story 3.2**: Bridge pattern implementation
- **Dependencies**: Story 3.1 complete
- **Blocks**: Story 3.6 (integration tests)

### Track C: Reporters (Can parallelize after 3.1)
- **Story 3.3**: ClusterHealthReporter
- **Story 3.4**: ProducerHealthReporter
- **Story 3.5**: ConsumerHealthReporter
- **Dependencies**: Story 3.1 complete
- **Can run in parallel**: Yes (independent reporters)
- **Blocks**: Story 3.6 (integration tests)

### Track D: Integration (Do Last)
- **Story 3.6**: End-to-end integration tests
- **Dependencies**: All stories 3.1-3.5 complete

---

## Story 3.1: Implement Cell Hierarchy Infrastructure

**Module**: `fullerstack-kafka-core`
**Priority**: P0 (Blocks all other work)
**Estimated Effort**: 4-6 hours
**Parallelizable**: No (foundation for everything)

### Acceptance Criteria

- [ ] `MonitorSignComposer` class implements egress composer for `Cell<Monitors.Sign, Monitors.Sign>`
- [ ] `HierarchyManager` class creates and manages Cell hierarchy (4 levels: Cluster → Broker → Topic → Partition)
- [ ] Unit tests verify Sign aggregation (worst-case: DEGRADED overrides STABLE)
- [ ] Unit tests verify upward flow (Partition → Topic → Broker → Cluster)
- [ ] All tests pass

### Implementation Details

#### Classes to Create

**1. `MonitorSignComposer.java`** - Egress composer for Cell hierarchy
```java
package io.fullerstack.kafka.core.hierarchy;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;

/**
 * Egress composer for Cell<Monitors.Sign, Monitors.Sign> hierarchy.
 *
 * Aggregates children's Signs using worst-case logic:
 * - DOWN > DEFECTIVE > DEGRADED > ERRATIC > DIVERGING > CONVERGING > STABLE
 */
public class MonitorSignComposer implements Composer<Monitors.Sign, Monitors.Sign> {

    @Override
    public Monitors.Sign compose(Iterable<Monitors.Sign> childSigns) {
        // Worst-case aggregation logic
        Monitors.Sign worstSign = Monitors.Sign.STABLE;

        for (Monitors.Sign sign : childSigns) {
            if (isWorse(sign, worstSign)) {
                worstSign = sign;
            }
        }

        return worstSign;
    }

    private boolean isWorse(Monitors.Sign a, Monitors.Sign b) {
        return severity(a) > severity(b);
    }

    private int severity(Monitors.Sign sign) {
        return switch (sign) {
            case DOWN -> 6;
            case DEFECTIVE -> 5;
            case DEGRADED -> 4;
            case ERRATIC -> 3;
            case DIVERGING -> 2;
            case CONVERGING -> 1;
            case STABLE -> 0;
        };
    }
}
```

**2. `HierarchyManager.java`** - Creates and manages Cell hierarchy
```java
package io.fullerstack.kafka.core.hierarchy;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Manages Cell<Monitors.Sign, Monitors.Sign> hierarchy for Kafka topology.
 *
 * Hierarchy:
 * - Cluster (root)
 *   - Broker
 *     - Topic
 *       - Partition
 */
public class HierarchyManager implements AutoCloseable {

    private final Circuit circuit;
    private final Cell<Monitors.Sign, Monitors.Sign> clusterCell;
    private final MonitorSignComposer composer;

    public HierarchyManager(String clusterName) {
        this.composer = new MonitorSignComposer();
        this.circuit = cortex().circuit(cortex().name(clusterName));
        this.clusterCell = circuit.cell(
            cortex().name("cluster"),
            composer
        );
    }

    public Cell<Monitors.Sign, Monitors.Sign> getClusterCell() {
        return clusterCell;
    }

    public Cell<Monitors.Sign, Monitors.Sign> getBrokerCell(String brokerId) {
        return clusterCell.get(cortex().name(brokerId));
    }

    public Cell<Monitors.Sign, Monitors.Sign> getTopicCell(String brokerId, String topicName) {
        return getBrokerCell(brokerId).get(cortex().name(topicName));
    }

    public Cell<Monitors.Sign, Monitors.Sign> getPartitionCell(
        String brokerId,
        String topicName,
        String partitionId
    ) {
        return getTopicCell(brokerId, topicName).get(cortex().name(partitionId));
    }

    @Override
    public void close() {
        circuit.close();
    }
}
```

#### Unit Tests to Write

**1. `MonitorSignComposerTest.java`**
```java
@Test
void testWorstCaseAggregation() {
    MonitorSignComposer composer = new MonitorSignComposer();

    // Given: 3 child signs (STABLE, DEGRADED, STABLE)
    List<Monitors.Sign> children = List.of(
        Monitors.Sign.STABLE,
        Monitors.Sign.DEGRADED,
        Monitors.Sign.STABLE
    );

    // When: Compose
    Monitors.Sign result = composer.compose(children);

    // Then: Should return worst-case (DEGRADED)
    assertThat(result).isEqualTo(Monitors.Sign.DEGRADED);
}
```

**2. `HierarchyManagerTest.java`**
```java
@Test
void testPartitionSignFlowsToCluster() {
    HierarchyManager manager = new HierarchyManager("test-cluster");

    // Track cluster emissions
    List<Monitors.Sign> clusterEmissions = new ArrayList<>();
    manager.getClusterCell().subscribe(cortex().subscriber(
        cortex().name("test"),
        (subject, registrar) -> registrar.register(clusterEmissions::add)
    ));

    // Emit DEGRADED to partition
    Cell<Monitors.Sign, Monitors.Sign> partition =
        manager.getPartitionCell("broker-1", "orders", "p0");
    partition.pipe().emit(Monitors.Sign.DEGRADED);

    // Wait for signal propagation
    manager.circuit.await();

    // Should flow up: Partition → Topic → Broker → Cluster
    assertThat(clusterEmissions).contains(Monitors.Sign.DEGRADED);

    manager.close();
}
```

### Files to Create

```
fullerstack-kafka-core/src/main/java/io/fullerstack/kafka/core/hierarchy/
├── MonitorSignComposer.java
├── HierarchyManager.java
└── package-info.java

fullerstack-kafka-core/src/test/java/io/fullerstack/kafka/core/hierarchy/
├── MonitorSignComposerTest.java
└── HierarchyManagerTest.java
```

### Dependencies

- ✅ Substrates 1.0.0-PREVIEW (Cell API)
- ✅ Serventis 1.0.0-PREVIEW (Monitors.Sign)
- ✅ Existing 103 Monitor measurements

### Definition of Done

- [ ] All classes compile
- [ ] All unit tests pass (6+ tests)
- [ ] Code coverage >80%
- [ ] JavaDoc complete
- [ ] No compiler warnings

---

## Story 3.2: Implement Bridge Pattern

**Module**: `fullerstack-kafka-core`
**Priority**: P1
**Estimated Effort**: 6-8 hours
**Parallelizable**: After Story 3.1 complete
**Dependencies**: Story 3.1 (HierarchyManager)

### Acceptance Criteria

- [ ] `MonitorCellBridge` subscribes to Monitor conduits
- [ ] Bridge parses subject names (e.g., "broker-1.orders.p0" → broker/topic/partition)
- [ ] Bridge forwards Signs to correct cells via HierarchyManager
- [ ] Unit tests verify name parsing for all 4 levels
- [ ] Unit tests verify Sign forwarding to correct cells
- [ ] All tests pass

### Implementation Details

#### Classes to Create

**1. `MonitorCellBridge.java`** - Subscribes to Monitor conduit, forwards to Cells
```java
package io.fullerstack.kafka.core.bridge;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.fullerstack.kafka.core.hierarchy.HierarchyManager;
import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Bridges Monitor conduit emissions to Cell hierarchy.
 *
 * Subscribes to Monitor conduit, parses entity names, and forwards
 * Monitors.Sign to appropriate cells in hierarchy.
 *
 * Name format: "broker-1.orders.p0" → broker=broker-1, topic=orders, partition=p0
 */
public class MonitorCellBridge implements AutoCloseable {

    private final Conduit<Monitors.Monitor, Monitors.Signal> monitors;
    private final HierarchyManager hierarchy;
    private final Subscriber<Monitors.Signal, ?> subscriber;

    public MonitorCellBridge(
        Conduit<Monitors.Monitor, Monitors.Signal> monitors,
        HierarchyManager hierarchy
    ) {
        this.monitors = monitors;
        this.hierarchy = hierarchy;
        this.subscriber = cortex().subscriber(
            cortex().name("monitor-cell-bridge"),
            this::handleMonitorSignal
        );
    }

    public void start() {
        monitors.subscribe(subscriber);
    }

    private void handleMonitorSignal(
        Subject<Channel<Monitors.Signal>> subject,
        Registrar<Monitors.Signal> registrar
    ) {
        registrar.register(signal -> {
            // Use Name API directly (no EntityPath needed)
            Name entityName = subject.name();
            int depth = entityName.depth();

            // Extract components using Name.iterator() (REVERSE order)
            List<String> components = new ArrayList<>();
            for (Name part : entityName) {
                components.add(part.value());
            }

            // Get appropriate cell based on depth
            Cell<Monitors.Sign, Monitors.Sign> cell = switch (depth) {
                case 1 -> {
                    if ("cluster".equals(entityName.value())) {
                        yield hierarchy.getClusterCell();
                    }
                    yield hierarchy.getBrokerCell(components.get(0));
                }
                case 2 -> hierarchy.getTopicCell(
                    components.get(1),  // broker (reversed)
                    components.get(0)   // topic
                );
                case 3 -> hierarchy.getPartitionCell(
                    components.get(2),  // broker (reversed)
                    components.get(1),  // topic
                    components.get(0)   // partition
                );
                default -> throw new IllegalArgumentException("Invalid depth: " + depth);
            };

            // Forward Sign to cell
            cell.emit(signal.sign());
        });
    }

    @Override
    public void close() {
        // Unsubscribe handled by circuit close
    }
}
```
```java
package io.fullerstack.kafka.core.bridge;

/**
 * Parses entity names to extract hierarchy components.
 *
 * Formats:
 * - "broker-1" → BROKER level
 * - "broker-1.orders" → TOPIC level
 * - "broker-1.orders.p0" → PARTITION level
 * - "cluster" → CLUSTER level
 */
```

#### Unit Tests to Write

**Name API Testing** (no EntityPath class needed):
```java
@Test
void testNameDepthParsing() {
    Name brokerName = cortex().name("broker-1");
    assertThat(brokerName.depth()).isEqualTo(1);

    Name topicName = cortex().name("broker-1.orders");
    assertThat(topicName.depth()).isEqualTo(2);

    Name partitionName = cortex().name("broker-1.orders.p0");
    assertThat(partitionName.depth()).isEqualTo(3);

    // Extract components (REVERSE order: deepest → root)
    List<String> components = new ArrayList<>();
    for (Name part : partitionName) {
        components.add(part.value());
    }
    assertThat(components).containsExactly("p0", "orders", "broker-1");
}
```

**2. `MonitorCellBridgeTest.java`**
```java
@Test
void testBridgeForwardsSignToPartitionCell() {
    // Given: Monitor conduit and Cell hierarchy
    Circuit circuit = cortex().circuit(cortex().name("test"));
    Conduit<Monitors.Monitor, Monitors.Signal> monitors = circuit.conduit(
        cortex().name("monitors"),
        Monitors::composer
    );

    HierarchyManager hierarchy = new HierarchyManager("test-cluster");
    MonitorCellBridge bridge = new MonitorCellBridge(monitors, hierarchy);
    bridge.start();

    // Track partition cell emissions
    Cell<Monitors.Sign, Monitors.Sign> partition =
        hierarchy.getPartitionCell("broker-1", "orders", "p0");

    List<Monitors.Sign> emissions = new ArrayList<>();
    partition.subscribe(cortex().subscriber(
        cortex().name("test"),
        (s, r) -> r.register(emissions::add)
    ));

    // When: Monitor emits DEGRADED for "broker-1.orders.p0"
    Monitors.Monitor monitor = monitors.get(cortex().name("broker-1.orders.p0"));
    monitor.degraded(Monitors.Dimension.CONFIRMED);
    circuit.await();

    // Then: Should forward to partition cell
    assertThat(emissions).contains(Monitors.Sign.DEGRADED);

    bridge.close();
    hierarchy.close();
    circuit.close();
}
```

### Files to Create

```
fullerstack-kafka-core/src/main/java/io/fullerstack/kafka/core/bridge/
├── MonitorCellBridge.java
└── package-info.java

fullerstack-kafka-core/src/test/java/io/fullerstack/kafka/core/bridge/
├── MonitorCellBridgeTest.java
```

### Definition of Done

- [ ] All classes compile
- [ ] All unit tests pass (8+ tests)
- [ ] Code coverage >80%
- [ ] JavaDoc complete
- [ ] Name parsing handles all 4 hierarchy levels

---

## Story 3.3: Implement ClusterHealthReporter

**Module**: `fullerstack-kafka-runtime` (new module) or `fullerstack-kafka-core`
**Priority**: P1
**Estimated Effort**: 4-5 hours
**Parallelizable**: After Story 3.1, parallel with 3.4/3.5
**Dependencies**: Story 3.1 (HierarchyManager)

### Acceptance Criteria

- [ ] `ClusterHealthReporter` subscribes to cluster cell
- [ ] Receives aggregated `Monitors.Sign` from cluster
- [ ] Assesses urgency: DEGRADED/DOWN → CRITICAL, ERRATIC/DIVERGING → WARNING
- [ ] Emits `Reporters.Sign` to Reporter conduit
- [ ] Unit tests verify urgency mapping
- [ ] All tests pass

### Implementation Details

#### Classes to Create

**1. `ClusterHealthReporter.java`**
```java
package io.fullerstack.kafka.reporters;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Layer 3 (DECIDE): Assesses cluster health urgency.
 *
 * Subscribes to cluster Cell, receives aggregated Monitors.Sign,
 * emits Reporters.Sign based on urgency assessment.
 */
public class ClusterHealthReporter implements AutoCloseable {

    private final Cell<Monitors.Sign, Monitors.Sign> clusterCell;
    private final Conduit<Reporters.Reporter, Reporters.Signal> reporters;
    private final Reporters.Reporter reporter;

    public ClusterHealthReporter(
        Cell<Monitors.Sign, Monitors.Sign> clusterCell,
        Conduit<Reporters.Reporter, Reporters.Signal> reporters
    ) {
        this.clusterCell = clusterCell;
        this.reporters = reporters;
        this.reporter = reporters.get(cortex().name("cluster-health"));

        // Subscribe to cluster cell
        clusterCell.subscribe(cortex().subscriber(
            cortex().name("cluster-health-reporter"),
            this::handleClusterSign
        ));
    }

    private void handleClusterSign(
        Subject<Channel<Monitors.Sign>> subject,
        Registrar<Monitors.Sign> registrar
    ) {
        registrar.register(sign -> {
            // Assess urgency based on Sign
            switch (sign) {
                case DOWN, DEFECTIVE ->
                    reporter.critical(Reporters.Dimension.OPERATIONAL);

                case DEGRADED ->
                    reporter.critical(Reporters.Dimension.OPERATIONAL);

                case ERRATIC, DIVERGING ->
                    reporter.warning(Reporters.Dimension.OPERATIONAL);

                case CONVERGING, STABLE ->
                    reporter.normal(Reporters.Dimension.OPERATIONAL);
            }
        });
    }

    @Override
    public void close() {
        // Unsubscribe handled by circuit close
    }
}
```

#### Unit Tests to Write

**1. `ClusterHealthReporterTest.java`**
```java
@Test
void testDegradedSignEmitsCritical() {
    // Given: Cluster cell and reporter conduit
    Circuit circuit = cortex().circuit(cortex().name("test"));
    Cell<Monitors.Sign, Monitors.Sign> clusterCell =
        circuit.cell(cortex().name("cluster"), Monitors::composer);

    Circuit reporterCircuit = cortex().circuit(cortex().name("reporters"));
    Conduit<Reporters.Reporter, Reporters.Signal> reporters =
        reporterCircuit.conduit(
            cortex().name("reporters"),
            Reporters::composer
        );

    ClusterHealthReporter clusterReporter =
        new ClusterHealthReporter(clusterCell, reporters);

    // Track reporter emissions
    List<Reporters.Sign> emissions = new ArrayList<>();
    reporters.subscribe(cortex().subscriber(
        cortex().name("test"),
        (s, r) -> r.register(signal -> emissions.add(signal.sign()))
    ));

    // When: Cluster emits DEGRADED
    clusterCell.pipe().emit(Monitors.Sign.DEGRADED);
    circuit.await();
    reporterCircuit.await();

    // Then: Should emit CRITICAL
    assertThat(emissions).contains(Reporters.Sign.CRITICAL);

    clusterReporter.close();
    reporterCircuit.close();
    circuit.close();
}
```

### Files to Create

```
fullerstack-kafka-core/src/main/java/io/fullerstack/kafka/reporters/
├── ClusterHealthReporter.java
├── BaseReporter.java (optional - shared logic)
└── package-info.java

fullerstack-kafka-core/src/test/java/io/fullerstack/kafka/reporters/
└── ClusterHealthReporterTest.java
```

### Definition of Done

- [ ] All classes compile
- [ ] All unit tests pass (4+ tests covering all urgency levels)
- [ ] Code coverage >80%
- [ ] JavaDoc complete
- [ ] Urgency mapping documented

---

## Story 3.4: Implement ProducerHealthReporter

**Module**: `fullerstack-kafka-core`
**Priority**: P2
**Estimated Effort**: 3-4 hours
**Parallelizable**: After Story 3.1, parallel with 3.3/3.5
**Dependencies**: Story 3.1 (HierarchyManager)

### Acceptance Criteria

- [ ] `ProducerHealthReporter` subscribes to producers root cell
- [ ] Receives aggregated `Monitors.Sign` from all producers
- [ ] Emits `Reporters.Sign` based on producer health
- [ ] Unit tests verify reporter behavior
- [ ] All tests pass

### Implementation Details

Similar to Story 3.3, but for producer hierarchy (flat: root → producer children).

**Key Difference**: Producers have 2-level hierarchy (root → producers), not 4-level like cluster.

### Files to Create

```
fullerstack-kafka-core/src/main/java/io/fullerstack/kafka/reporters/
└── ProducerHealthReporter.java

fullerstack-kafka-core/src/test/java/io/fullerstack/kafka/reporters/
└── ProducerHealthReporterTest.java
```

---

## Story 3.5: Implement ConsumerHealthReporter

**Module**: `fullerstack-kafka-core`
**Priority**: P2
**Estimated Effort**: 3-4 hours
**Parallelizable**: After Story 3.1, parallel with 3.3/3.4
**Dependencies**: Story 3.1 (HierarchyManager)

### Acceptance Criteria

- [ ] `ConsumerHealthReporter` subscribes to consumers root cell
- [ ] Receives aggregated `Monitors.Sign` from all consumer groups
- [ ] Emits `Reporters.Sign` based on consumer health
- [ ] Unit tests verify reporter behavior
- [ ] All tests pass

### Implementation Details

Similar to Story 3.4, but for consumer hierarchy (flat: root → consumer groups).

### Files to Create

```
fullerstack-kafka-core/src/main/java/io/fullerstack/kafka/reporters/
└── ConsumerHealthReporter.java

fullerstack-kafka-core/src/test/java/io/fullerstack/kafka/reporters/
└── ConsumerHealthReporterTest.java
```

---

## Story 3.6: Integration Tests for Complete Signal Flow

**Module**: `fullerstack-kafka-runtime` (integration tests)
**Priority**: P0 (Verification)
**Estimated Effort**: 6-8 hours
**Parallelizable**: No (requires all previous stories)
**Dependencies**: Stories 3.1, 3.2, 3.3, 3.4, 3.5 complete

### Acceptance Criteria

- [ ] Integration test: Partition DEGRADED → flows to Cluster → Reporter CRITICAL
- [ ] Integration test: Multiple brokers DEGRADED → Cluster aggregates → Reporter CRITICAL
- [ ] Integration test: Producer buffer OVERFLOW → Reporter WARNING
- [ ] Integration test: Consumer lag DEGRADED → Reporter WARNING
- [ ] All 4 integration tests pass
- [ ] End-to-end latency measured (<100ms for signal propagation)

### Implementation Details

#### Integration Tests to Write

**1. `PartitionToClusterSignalFlowIT.java`**
```java
@Test
void testPartitionDegradedFlowsToReporter() {
    // Given: Full stack (Monitors → Bridge → Cells → Reporters)
    Circuit monitorCircuit = cortex().circuit(cortex().name("monitors"));
    Conduit<Monitors.Monitor, Monitors.Signal> monitors =
        monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);

    HierarchyManager hierarchy = new HierarchyManager("test-cluster");
    MonitorCellBridge bridge = new MonitorCellBridge(monitors, hierarchy);
    bridge.start();

    Circuit reporterCircuit = cortex().circuit(cortex().name("reporters"));
    Conduit<Reporters.Reporter, Reporters.Signal> reporters =
        reporterCircuit.conduit(cortex().name("reporters"), Reporters::composer);

    ClusterHealthReporter reporter =
        new ClusterHealthReporter(hierarchy.getClusterCell(), reporters);

    // Track reporter emissions
    List<Reporters.Sign> emissions = new ArrayList<>();
    reporters.subscribe(cortex().subscriber(
        cortex().name("test"),
        (s, r) -> r.register(signal -> emissions.add(signal.sign()))
    ));

    // When: Monitor emits DEGRADED for partition
    Monitors.Monitor partitionMonitor =
        monitors.get(cortex().name("broker-1.orders.p0"));
    partitionMonitor.degraded(Monitors.Dimension.CONFIRMED);

    monitorCircuit.await();
    reporterCircuit.await();

    // Then: Should flow: Monitor → Bridge → Partition → Topic → Broker → Cluster → Reporter
    assertThat(emissions).contains(Reporters.Sign.CRITICAL);

    // Cleanup
    reporter.close();
    bridge.close();
    hierarchy.close();
    reporterCircuit.close();
    monitorCircuit.close();
}
```

**2. `MultiLevelAggregationIT.java`**
```java
@Test
void testMultipleBrokersDegradedAggregates() {
    // Test that 2/3 brokers DEGRADED → Cluster DEGRADED → Reporter CRITICAL
    // (Tests aggregation logic across multiple brokers)
}
```

**3. `ProducerBufferOverflowIT.java`**
```java
@Test
void testProducerBufferOverflowEmitsWarning() {
    // Test producer buffer pressure → Reporter WARNING
}
```

**4. `ConsumerLagIT.java`**
```java
@Test
void testConsumerLagEmitsWarning() {
    // Test consumer lag DEGRADED → Reporter WARNING
}
```

### Files to Create

```
fullerstack-kafka-runtime/src/test/java/io/fullerstack/kafka/integration/
├── PartitionToClusterSignalFlowIT.java
├── MultiLevelAggregationIT.java
├── ProducerBufferOverflowIT.java
└── ConsumerLagIT.java
```

### Definition of Done

- [ ] All 4 integration tests pass
- [ ] Signal propagation latency <100ms
- [ ] No flaky tests
- [ ] Tests clean up resources properly (no leaks)
- [ ] Tests demonstrate complete OBSERVE → ORIENT → DECIDE flow

---

## Execution Order

### Phase 1: Foundation (Sequential)
1. **Story 3.1** - Cell hierarchy infrastructure (4-6h)
   - **BLOCKS EVERYTHING ELSE**
   - Must complete first

### Phase 2: Parallel Tracks (After 3.1)
Run these in parallel (3 developers or sequential):

**Track A: Bridge (6-8h)**
2. **Story 3.2** - MonitorCellBridge

**Track B: Reporters (10-13h total, can parallelize)**
3. **Story 3.3** - ClusterHealthReporter (4-5h)
4. **Story 3.4** - ProducerHealthReporter (3-4h) - parallel with 3.3
5. **Story 3.5** - ConsumerHealthReporter (3-4h) - parallel with 3.3/3.4

### Phase 3: Integration (Sequential, after all above)
6. **Story 3.6** - Integration tests (6-8h)

---

## Total Effort Estimate

**Sequential Execution**: 26-37 hours (1-2 weeks)

**Parallel Execution** (3 developers):
- Phase 1: 4-6h (sequential, blocks everything)
- Phase 2: 8h max (run Bridge + 3 Reporters in parallel)
- Phase 3: 6-8h (sequential, verification)
- **Total: 18-22 hours (~2-3 days with 3 developers)**

---

## Success Metrics

- [ ] All 6 stories completed
- [ ] 497 existing tests still passing (no regressions)
- [ ] 30+ new tests added (unit + integration)
- [ ] Code coverage >80% for new code
- [ ] Integration tests demonstrate complete Layer 1-2-3 flow
- [ ] Zero compiler warnings
- [ ] Documentation complete (JavaDoc + architecture docs)

---

## Next Steps After Layer 3

**Epic 4: ACT Phase (Actors)**
- AlertActor (PagerDuty, Slack)
- ThrottleActor (producer throttling)
- ReassignmentActor (partition reassignment)
- AutoScaleActor (AWS auto-scaling)

**Estimated**: 20-30 hours additional work
