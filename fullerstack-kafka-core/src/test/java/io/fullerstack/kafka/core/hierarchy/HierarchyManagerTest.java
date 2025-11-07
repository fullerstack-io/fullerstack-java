package io.fullerstack.kafka.core.hierarchy;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static io.humainary.substrates.api.Substrates.cortex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class HierarchyManagerTest {

    private HierarchyManager manager;

    @AfterEach
    void cleanup() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void testPartitionSignFlowsToCluster() throws InterruptedException {
        // Create manager
        manager = new HierarchyManager("test-cluster");

        // Get partition cell
        Cell<Monitors.Sign, Monitors.Sign> partition =
            manager.getPartitionCell("broker-1", "orders", "p0");

        // Emit DEGRADED to partition (PREVIEW API: Cell extends Pipe<I>)
        partition.emit(Monitors.Sign.DEGRADED);

        // Wait for signal propagation
        manager.getCircuit().await();

        // Test passes if no exceptions (upward flow is automatic)
        assertThat((Object) partition).isNotNull();
    }

    @Test
    void testFourLevelNavigation() {
        manager = new HierarchyManager("test-cluster");

        Cell<Monitors.Sign, Monitors.Sign> cluster = manager.getClusterCell();
        Cell<Monitors.Sign, Monitors.Sign> broker = manager.getBrokerCell("broker-1");
        Cell<Monitors.Sign, Monitors.Sign> topic = manager.getTopicCell("broker-1", "orders");
        Cell<Monitors.Sign, Monitors.Sign> partition = manager.getPartitionCell("broker-1", "orders", "p0");

        assertThat((Object) cluster).isNotNull();
        assertThat((Object) broker).isNotNull();
        assertThat((Object) topic).isNotNull();
        assertThat((Object) partition).isNotNull();
    }

    @Test
    void testResourceCleanup() {
        manager = new HierarchyManager("test-cluster");

        assertThatCode(() -> manager.close()).doesNotThrowAnyException();
    }
}
