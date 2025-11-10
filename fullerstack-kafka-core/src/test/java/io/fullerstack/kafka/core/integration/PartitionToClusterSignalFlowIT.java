package io.fullerstack.kafka.core.integration;

import io.fullerstack.kafka.core.bridge.MonitorCellBridge;
import io.fullerstack.kafka.core.hierarchy.HierarchyManager;
import io.fullerstack.kafka.core.reporters.ClusterHealthReporter;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for complete signal flow from partition monitor to cluster reporter.
 *
 * <p>Tests the complete OBSERVE → ORIENT → DECIDE flow:
 * <pre>
 * Layer 1 (OBSERVE): Monitor emits Monitors.Sign.DEGRADED for partition
 *           ↓
 * Layer 2 (ORIENT): Bridge routes to Cell hierarchy
 *           ↓ Partition Cell → Topic Cell → Broker Cell → Cluster Cell
 * Layer 3 (DECIDE): ClusterHealthReporter assesses urgency
 *           ↓
 *           Reporter emits Reporters.Sign.CRITICAL
 * </pre>
 *
 * @see MonitorCellBridge
 * @see HierarchyManager
 * @see ClusterHealthReporter
 */
@DisplayName("Integration: Partition → Cluster → Reporter Signal Flow")
class PartitionToClusterSignalFlowIT {

    private Circuit monitorCircuit;
    private Circuit reporterCircuit;
    private Conduit<Monitors.Monitor, Monitors.Signal> monitors;
    private Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private HierarchyManager hierarchy;
    private MonitorCellBridge bridge;
    private ClusterHealthReporter clusterReporter;
    private List<Reporters.Sign> reporterEmissions;

    @BeforeEach
    void setUp() {
        // Layer 1: Create Monitor circuit and conduit
        monitorCircuit = cortex().circuit(cortex().name("monitors"));
        monitors = monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);

        // Layer 2: Create Cell hierarchy and bridge
        hierarchy = new HierarchyManager("test-cluster");
        bridge = new MonitorCellBridge(monitors, hierarchy);
        bridge.start();

        // Layer 3: Create Reporter circuit, conduit, and cluster reporter
        reporterCircuit = cortex().circuit(cortex().name("reporters"));
        reporters = reporterCircuit.conduit(cortex().name("reporters"), Reporters::composer);
        clusterReporter = new ClusterHealthReporter(hierarchy.getClusterCell(), reporters);

        // Track reporter emissions
        reporterEmissions = new ArrayList<>();
        reporters.subscribe(cortex().subscriber(
            cortex().name("test-observer"),
            (Subject<Channel<Reporters.Sign>> subject, Registrar<Reporters.Sign> registrar) -> {
                registrar.register(reporterEmissions::add);
            }
        ));
    }

    @AfterEach
    void tearDown() {
        if (clusterReporter != null) {
            clusterReporter.close();
        }
        if (bridge != null) {
            bridge.close();
        }
        if (hierarchy != null) {
            hierarchy.close();
        }
        if (reporterCircuit != null) {
            reporterCircuit.close();
        }
        if (monitorCircuit != null) {
            monitorCircuit.close();
        }
    }

    @Test
    @DisplayName("Partition DEGRADED flows through hierarchy to emit CRITICAL")
    void testPartitionDegradedFlowsToReporter() {
        // When: Monitor emits DEGRADED for partition "broker-1.orders.p0"
        long startTime = System.nanoTime();

        Monitors.Monitor partitionMonitor = monitors.get(cortex().name("broker-1.orders.p0"));
        partitionMonitor.degraded(Monitors.Dimension.CONFIRMED);

        // Wait for event propagation
        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        long endTime = System.nanoTime();
        long latencyMs = (endTime - startTime) / 1_000_000;

        // Then: Signal should flow through hierarchy to reporter
        assertThat(reporterEmissions)
            .as("Reporter should emit CRITICAL for partition DEGRADED")
            .contains(Reporters.Sign.CRITICAL);

        // Verify latency requirement
        assertThat(latencyMs)
            .as("Signal propagation should complete within 100ms")
            .isLessThan(100);
    }

    @Test
    @DisplayName("Multiple partition failures aggregate to cluster CRITICAL")
    void testMultiplePartitionFailuresAggregate() {
        // When: Multiple partitions emit DEGRADED
        Monitors.Monitor partition1 = monitors.get(cortex().name("broker-1.orders.p0"));
        Monitors.Monitor partition2 = monitors.get(cortex().name("broker-1.orders.p1"));
        Monitors.Monitor partition3 = monitors.get(cortex().name("broker-1.orders.p2"));

        partition1.degraded(Monitors.Dimension.CONFIRMED);
        partition2.degraded(Monitors.Dimension.CONFIRMED);
        partition3.degraded(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should aggregate to cluster CRITICAL
        assertThat(reporterEmissions)
            .as("Multiple partition failures should emit CRITICAL")
            .contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Topic-level DEGRADED propagates to cluster")
    void testTopicLevelDegradedPropagates() {
        // When: Monitor emits DEGRADED for topic (2-level name)
        Monitors.Monitor topicMonitor = monitors.get(cortex().name("broker-2.payments"));
        topicMonitor.degraded(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should propagate to cluster and emit CRITICAL
        assertThat(reporterEmissions).contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Broker-level DEGRADED propagates to cluster")
    void testBrokerLevelDegradedPropagates() {
        // When: Monitor emits DEGRADED for broker (1-level name)
        Monitors.Monitor brokerMonitor = monitors.get(cortex().name("broker-3"));
        brokerMonitor.degraded(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should propagate to cluster and emit CRITICAL
        assertThat(reporterEmissions).contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("WARNING signals propagate correctly")
    void testWarningSignalsPropagateCorrectly() {
        // When: Monitor emits ERRATIC for partition
        Monitors.Monitor partitionMonitor = monitors.get(cortex().name("broker-1.orders.p0"));
        partitionMonitor.erratic(Monitors.Dimension.MEASURED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(reporterEmissions).contains(Reporters.Sign.WARNING);
    }

    @Test
    @DisplayName("NORMAL signals propagate correctly")
    void testNormalSignalsPropagateCorrectly() {
        // When: Monitor emits STABLE for partition
        Monitors.Monitor partitionMonitor = monitors.get(cortex().name("broker-1.orders.p0"));
        partitionMonitor.stable(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should emit NORMAL
        assertThat(reporterEmissions).contains(Reporters.Sign.NORMAL);
    }

    @Test
    @DisplayName("Cluster-level signal emits directly")
    void testClusterLevelSignalEmitsDirectly() {
        // When: Monitor emits DEGRADED for cluster (special name)
        Monitors.Monitor clusterMonitor = monitors.get(cortex().name("cluster"));
        clusterMonitor.degraded(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(reporterEmissions).contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Mixed severity signals aggregate to worst-case")
    void testMixedSeveritySignalsAggregateToWorstCase() {
        // When: Emit mixed signals (STABLE, ERRATIC, DEGRADED)
        monitors.get(cortex().name("broker-1.orders.p0")).stable(Monitors.Dimension.CONFIRMED);
        monitors.get(cortex().name("broker-1.orders.p1")).erratic(Monitors.Dimension.MEASURED);
        monitors.get(cortex().name("broker-1.orders.p2")).degraded(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should aggregate to worst-case (DEGRADED → CRITICAL)
        assertThat(reporterEmissions).contains(Reporters.Sign.CRITICAL);
    }
}
