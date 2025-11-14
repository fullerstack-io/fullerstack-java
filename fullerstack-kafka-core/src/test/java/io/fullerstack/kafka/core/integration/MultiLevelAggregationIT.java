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
 * Integration test for multi-level aggregation across the Cell hierarchy.
 *
 * <p>Tests that multiple failures at different levels aggregate properly
 * through the hierarchy:
 * <pre>
 * Multiple Partition DEGRADED → Topic Cell aggregates
 *                             ↓
 * Multiple Topic DEGRADED → Broker Cell aggregates
 *                          ↓
 * Multiple Broker DEGRADED → Cluster Cell aggregates
 *                           ↓
 * ClusterHealthReporter → CRITICAL
 * </pre>
 *
 * <p>This validates the hierarchical aggregation logic where:
 * <ul>
 *   <li>2/3 partitions DEGRADED → Topic DEGRADED</li>
 *   <li>2/3 topics DEGRADED → Broker DEGRADED</li>
 *   <li>2/3 brokers DEGRADED → Cluster CRITICAL</li>
 * </ul>
 *
 * @see MonitorCellBridge
 * @see HierarchyManager
 * @see ClusterHealthReporter
 */
@DisplayName("Integration: Multi-Level Aggregation")
class MultiLevelAggregationIT {

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
            cortex().name("test-receptor"),
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
    @DisplayName("Multiple partition failures aggregate to topic level")
    void testMultiplePartitionsAggregateToTopic() {
        // When: 2 out of 3 partitions in same topic emit DEGRADED
        monitors.percept(cortex().name("broker-1.orders.p0")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-1.orders.p1")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-1.orders.p2")).stable(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should aggregate at topic level and emit CRITICAL
        assertThat(reporterEmissions)
            .as("Multiple partition failures should aggregate to topic DEGRADED → CRITICAL")
            .contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Multiple topic failures aggregate to broker level")
    void testMultipleTopicsAggregateToBroker() {
        // When: 2 out of 3 topics on same broker emit DEGRADED
        monitors.percept(cortex().name("broker-1.orders.p0")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-1.payments.p0")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-1.notifications.p0")).stable(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should aggregate at broker level and emit CRITICAL
        assertThat(reporterEmissions)
            .as("Multiple topic failures should aggregate to broker DEGRADED → CRITICAL")
            .contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Multiple broker failures aggregate to cluster CRITICAL")
    void testMultipleBrokersAggregateToCluster() {
        // When: 2 out of 3 brokers emit DEGRADED
        monitors.percept(cortex().name("broker-1")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-2")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-3")).stable(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should aggregate to cluster CRITICAL
        assertThat(reporterEmissions)
            .as("2/3 brokers DEGRADED should aggregate to cluster CRITICAL")
            .contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Cascading failure from partition to cluster")
    void testCascadingFailureFromPartitionToCluster() {
        long startTime = System.nanoTime();

        // When: Multiple partitions fail across multiple brokers
        // Broker 1: 2 topics DEGRADED
        monitors.percept(cortex().name("broker-1.orders.p0")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-1.payments.p0")).degraded(Monitors.Dimension.CONFIRMED);

        // Broker 2: 2 topics DEGRADED
        monitors.percept(cortex().name("broker-2.orders.p0")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-2.payments.p0")).degraded(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        long endTime = System.nanoTime();
        long latencyMs = (endTime - startTime) / 1_000_000;

        // Then: Should cascade through hierarchy to cluster CRITICAL
        assertThat(reporterEmissions)
            .as("Cascading failures should aggregate to cluster CRITICAL")
            .contains(Reporters.Sign.CRITICAL);

        assertThat(latencyMs)
            .as("Cascading aggregation should complete within 100ms")
            .isLessThan(100);
    }

    @Test
    @DisplayName("Partial failures with ERRATIC emit WARNING not CRITICAL")
    void testPartialFailuresEmitWarning() {
        // When: Only 1 out of 3 brokers has ERRATIC issues (not DEGRADED)
        monitors.percept(cortex().name("broker-1.orders.p0")).erratic(Monitors.Dimension.MEASURED);
        monitors.percept(cortex().name("broker-2")).stable(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-3")).stable(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should emit WARNING (not CRITICAL)
        assertThat(reporterEmissions)
            .as("Single broker ERRATIC should emit WARNING")
            .contains(Reporters.Sign.WARNING);
    }

    @Test
    @DisplayName("Mixed severity signals aggregate to worst case")
    void testMixedSeverityAggregation() {
        // When: Mix of DOWN, DEGRADED, ERRATIC across brokers
        monitors.percept(cortex().name("broker-1.orders.p0")).down(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-2.payments.p0")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-3.notifications.p0")).erratic(Monitors.Dimension.MEASURED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should aggregate to worst-case (DOWN → CRITICAL)
        assertThat(reporterEmissions)
            .as("Mixed severity should aggregate to worst-case CRITICAL")
            .contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Recovery propagates from partition to cluster")
    void testRecoveryPropagatesUpHierarchy() {
        // Given: Initial failures
        monitors.percept(cortex().name("broker-1.orders.p0")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-2.orders.p0")).degraded(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        reporterEmissions.clear(); // Clear initial CRITICAL

        // When: All partitions recover
        monitors.percept(cortex().name("broker-1.orders.p0")).stable(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-2.orders.p0")).stable(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        // Then: Should propagate NORMAL through hierarchy
        assertThat(reporterEmissions)
            .as("Recovery should propagate to cluster NORMAL")
            .contains(Reporters.Sign.NORMAL);
    }

    @Test
    @DisplayName("Aggregation latency meets <100ms requirement across all levels")
    void testAggregationLatency() {
        long startTime = System.nanoTime();

        // When: Emit signals at all 4 levels simultaneously
        monitors.percept(cortex().name("broker-1.orders.p0")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-1.payments")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("broker-2")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.percept(cortex().name("cluster")).degraded(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();
        reporterCircuit.await();

        long endTime = System.nanoTime();
        long latencyMs = (endTime - startTime) / 1_000_000;

        // Then: All aggregations should complete within 100ms
        assertThat(latencyMs)
            .as("Multi-level aggregation should complete within 100ms")
            .isLessThan(100);

        assertThat(reporterEmissions)
            .as("Should emit CRITICAL for cluster-level issues")
            .contains(Reporters.Sign.CRITICAL);
    }
}
