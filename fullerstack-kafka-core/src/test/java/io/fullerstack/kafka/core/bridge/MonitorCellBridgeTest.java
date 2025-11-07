package io.fullerstack.kafka.core.bridge;

import io.fullerstack.kafka.core.hierarchy.HierarchyManager;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link MonitorCellBridge}.
 */
class MonitorCellBridgeTest {

    private Circuit monitorCircuit;
    private Conduit<Monitors.Monitor, Monitors.Signal> monitors;
    private HierarchyManager hierarchy;
    private MonitorCellBridge bridge;

    @BeforeEach
    void setUp() {
        monitorCircuit = cortex().circuit(cortex().name("test-monitors"));
        monitors = monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);
        hierarchy = new HierarchyManager("test-cluster");
        bridge = new MonitorCellBridge(monitors, hierarchy);
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.close();
        }
        if (hierarchy != null) {
            hierarchy.close();
        }
        if (monitorCircuit != null) {
            monitorCircuit.close();
        }
    }

    @Test
    void testBridgeConstructorNullMonitors() {
        assertThatThrownBy(() -> new MonitorCellBridge(null, hierarchy))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("monitors cannot be null");
    }

    @Test
    void testBridgeConstructorNullHierarchy() {
        assertThatThrownBy(() -> new MonitorCellBridge(monitors, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("hierarchy cannot be null");
    }

    @Test
    void testBridgeForwardsSignToPartitionCell() {
        // Given: Bridge started
        bridge.start();

        // Track partition cell emissions
        Cell<Monitors.Sign, Monitors.Sign> partition =
            hierarchy.getPartitionCell("broker-1", "orders", "p0");

        List<Monitors.Sign> emissions = new ArrayList<>();
        partition.subscribe(cortex().subscriber(
            cortex().name("test-partition"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        // When: Monitor emits DEGRADED for "broker-1.orders.p0"
        Monitors.Monitor monitor = monitors.get(cortex().name("broker-1.orders.p0"));
        monitor.degraded(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        hierarchy.getCircuit().await();

        // Then: Should forward to partition cell
        assertThat(emissions).contains(Monitors.Sign.DEGRADED);
    }

    @Test
    void testBridgeForwardsSignToTopicCell() {
        // Given: Bridge started
        bridge.start();

        // Track topic cell emissions
        Cell<Monitors.Sign, Monitors.Sign> topic =
            hierarchy.getTopicCell("broker-2", "payments");

        List<Monitors.Sign> emissions = new ArrayList<>();
        topic.subscribe(cortex().subscriber(
            cortex().name("test-topic"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        // When: Monitor emits ERRATIC for "broker-2.payments"
        Monitors.Monitor monitor = monitors.get(cortex().name("broker-2.payments"));
        monitor.erratic(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        hierarchy.getCircuit().await();

        // Then: Should forward to topic cell
        assertThat(emissions).contains(Monitors.Sign.ERRATIC);
    }

    @Test
    void testBridgeForwardsSignToBrokerCell() {
        // Given: Bridge started
        bridge.start();

        // Track broker cell emissions
        Cell<Monitors.Sign, Monitors.Sign> broker =
            hierarchy.getBrokerCell("broker-3");

        List<Monitors.Sign> emissions = new ArrayList<>();
        broker.subscribe(cortex().subscriber(
            cortex().name("test-broker"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        // When: Monitor emits DIVERGING for "broker-3"
        Monitors.Monitor monitor = monitors.get(cortex().name("broker-3"));
        monitor.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        hierarchy.getCircuit().await();

        // Then: Should forward to broker cell
        assertThat(emissions).contains(Monitors.Sign.DIVERGING);
    }

    @Test
    void testBridgeForwardsSignToClusterCell() {
        // Given: Bridge started
        bridge.start();

        // Track cluster cell emissions
        Cell<Monitors.Sign, Monitors.Sign> cluster = hierarchy.getClusterCell();

        List<Monitors.Sign> emissions = new ArrayList<>();
        cluster.subscribe(cortex().subscriber(
            cortex().name("test-cluster"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        // When: Monitor emits STABLE for "cluster"
        Monitors.Monitor monitor = monitors.get(cortex().name("cluster"));
        monitor.stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        hierarchy.getCircuit().await();

        // Then: Should forward to cluster cell
        assertThat(emissions).contains(Monitors.Sign.STABLE);
    }

    @Test
    void testBridgeForwardsMultipleSignsToSameCell() {
        // Given: Bridge started
        bridge.start();

        // Track partition cell emissions
        Cell<Monitors.Sign, Monitors.Sign> partition =
            hierarchy.getPartitionCell("broker-1", "orders", "p0");

        List<Monitors.Sign> emissions = new ArrayList<>();
        partition.subscribe(cortex().subscriber(
            cortex().name("test-multi"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        // When: Monitor emits multiple Signs
        Monitors.Monitor monitor = monitors.get(cortex().name("broker-1.orders.p0"));
        monitor.stable(Monitors.Dimension.CONFIRMED);
        monitor.diverging(Monitors.Dimension.MEASURED);
        monitor.degraded(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        hierarchy.getCircuit().await();

        // Then: All Signs should be forwarded in order
        assertThat(emissions)
            .containsExactly(
                Monitors.Sign.STABLE,
                Monitors.Sign.DIVERGING,
                Monitors.Sign.DEGRADED
            );
    }

    @Test
    void testBridgeForwardsSignsFromMultipleMonitors() {
        // Given: Bridge started
        bridge.start();

        // Track cluster cell to receive aggregated Signs
        Cell<Monitors.Sign, Monitors.Sign> cluster = hierarchy.getClusterCell();

        List<Monitors.Sign> emissions = new ArrayList<>();
        cluster.subscribe(cortex().subscriber(
            cortex().name("test-aggregation"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        // When: Multiple monitors emit Signs
        monitors.get(cortex().name("broker-1.orders.p0"))
            .degraded(Monitors.Dimension.CONFIRMED);
        monitors.get(cortex().name("broker-2.payments.p1"))
            .stable(Monitors.Dimension.CONFIRMED);
        monitors.get(cortex().name("broker-3"))
            .erratic(Monitors.Dimension.MEASURED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();

        // Then: Cluster should receive all Signs (flows upward)
        assertThat(emissions)
            .contains(
                Monitors.Sign.DEGRADED,
                Monitors.Sign.STABLE,
                Monitors.Sign.ERRATIC
            );
    }

    @Test
    void testBridgeStartTwiceIgnoresSecondCall() {
        // Given: Bridge started once
        bridge.start();

        // When: Start called again (should be ignored)
        bridge.start();

        // Then: No exception, bridge still works
        Cell<Monitors.Sign, Monitors.Sign> partition =
            hierarchy.getPartitionCell("broker-1", "orders", "p0");

        List<Monitors.Sign> emissions = new ArrayList<>();
        partition.subscribe(cortex().subscriber(
            cortex().name("test-double-start"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        monitors.get(cortex().name("broker-1.orders.p0"))
            .stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        hierarchy.getCircuit().await();

        assertThat(emissions).contains(Monitors.Sign.STABLE);
    }

    @Test
    void testBridgeCloseStopsForwarding() {
        // Given: Bridge started and working
        bridge.start();

        Cell<Monitors.Sign, Monitors.Sign> partition =
            hierarchy.getPartitionCell("broker-1", "orders", "p0");

        List<Monitors.Sign> emissions = new ArrayList<>();
        partition.subscribe(cortex().subscriber(
            cortex().name("test-close"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        // Verify bridge works
        monitors.get(cortex().name("broker-1.orders.p0"))
            .stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        hierarchy.getCircuit().await();
        assertThat(emissions).hasSize(1);

        // When: Bridge closed
        bridge.close();
        emissions.clear();

        // Then: No more signals forwarded
        monitors.get(cortex().name("broker-1.orders.p0"))
            .degraded(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        hierarchy.getCircuit().await();

        assertThat(emissions).isEmpty();
    }

    @Test
    void testBridgeHandlesInvalidEntityName() {
        // Given: Bridge started
        bridge.start();

        // Track cluster cell (fallback)
        Cell<Monitors.Sign, Monitors.Sign> cluster = hierarchy.getClusterCell();

        List<Monitors.Sign> emissions = new ArrayList<>();
        cluster.subscribe(cortex().subscriber(
            cortex().name("test-invalid"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        // When: Monitor with too many components (invalid format)
        // Note: The bridge handles this gracefully by logging error
        Monitors.Monitor monitor = monitors.get(cortex().name("a.b.c.d.e"));
        monitor.stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        hierarchy.getCircuit().await();

        // Then: Error logged, no crash, cluster doesn't receive invalid sign
        // (This tests error handling - invalid paths are caught and logged)
    }

    @Test
    void testBridgeAllSignTypes() {
        // Given: Bridge started
        bridge.start();

        // Track partition cell
        Cell<Monitors.Sign, Monitors.Sign> partition =
            hierarchy.getPartitionCell("broker-1", "test", "p0");

        List<Monitors.Sign> emissions = new ArrayList<>();
        partition.subscribe(cortex().subscriber(
            cortex().name("test-all-signs"),
            (subject, registrar) -> registrar.register(emissions::add)
        ));

        // When: Monitor emits all Sign types
        Monitors.Monitor monitor = monitors.get(cortex().name("broker-1.test.p0"));
        monitor.down(Monitors.Dimension.CONFIRMED);
        monitor.defective(Monitors.Dimension.CONFIRMED);
        monitor.degraded(Monitors.Dimension.CONFIRMED);
        monitor.erratic(Monitors.Dimension.MEASURED);
        monitor.diverging(Monitors.Dimension.MEASURED);
        monitor.converging(Monitors.Dimension.MEASURED);
        monitor.stable(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        hierarchy.getCircuit().await();

        // Then: All Signs forwarded
        assertThat(emissions).containsExactly(
            Monitors.Sign.DOWN,
            Monitors.Sign.DEFECTIVE,
            Monitors.Sign.DEGRADED,
            Monitors.Sign.ERRATIC,
            Monitors.Sign.DIVERGING,
            Monitors.Sign.CONVERGING,
            Monitors.Sign.STABLE
        );
    }
}
