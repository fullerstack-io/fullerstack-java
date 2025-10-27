package io.fullerstack.kafka.broker.circuit;

import io.fullerstack.serventis.signals.MonitorSignal;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.core.config.ClusterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for KafkaCircuitSetup - Cell hierarchy initialization.
 */
class KafkaCircuitSetupTest {

    private KafkaCircuitSetup setup;

    @AfterEach
    void tearDown() {
        if (setup != null) {
            setup.close();
        }
    }

    @Test
    void testBasicSetup() {
        ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        setup = new KafkaCircuitSetup(config);

        assertNotNull(setup.getCortex());
        assertNotNull(setup.getConfig());
        assertEquals(config, setup.getConfig());
    }

    @Test
    void testClusterNameCreation() {
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "us-east-1",
                "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000,
                null
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);
        Name clusterName = setup.getClusterName();

        assertNotNull(clusterName);
        assertEquals("prod-account.us-east-1.transactions-cluster", clusterName.toString());
    }

    @Test
    void testBrokerIdParsing_SingleBroker() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001"
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);
        List<String> brokerIds = setup.getBrokerIds();

        assertEquals(1, brokerIds.size());
        assertEquals("b-1", brokerIds.get(0));
    }

    @Test
    void testBrokerIdParsing_MultipleBrokers() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092,b-2.trans.kafka.us-east-1.amazonaws.com:9092,b-3.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001"
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);
        List<String> brokerIds = setup.getBrokerIds();

        assertEquals(3, brokerIds.size());
        assertTrue(brokerIds.contains("b-1"));
        assertTrue(brokerIds.contains("b-2"));
        assertTrue(brokerIds.contains("b-3"));
    }

    @Test
    void testBrokerIdParsing_LocalhostBrokers() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "localhost:9092,localhost:9093,localhost:9094",
                "localhost:11001"
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);
        List<String> brokerIds = setup.getBrokerIds();

        assertEquals(3, brokerIds.size());
        // localhost appears 3 times
        assertEquals("localhost", brokerIds.get(0));
        assertEquals("localhost", brokerIds.get(1));
        assertEquals("localhost", brokerIds.get(2));
    }

    @Test
    void testGetBrokerName_SimpleBrokerId() {
        ClusterConfig config = new ClusterConfig(
                "staging-account",
                "eu-west-1",
                "analytics-cluster",
                "b-1.kafka.eu-west-1.amazonaws.com:9092",
                "b-1.kafka.eu-west-1.amazonaws.com:11001",
                30_000,
                null
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);
        Name brokerName = setup.getBrokerName("b-1");

        assertNotNull(brokerName);
        assertEquals("staging-account.eu-west-1.analytics-cluster.b-1", brokerName.toString());
    }

    @Test
    void testGetBrokerName_FullMskHostname() {
        ClusterConfig config = new ClusterConfig(
                "prod-account",
                "ap-south-1",
                "payments-cluster",
                "b-2.payments.kafka.ap-south-1.amazonaws.com:9092",
                "b-2.payments.kafka.ap-south-1.amazonaws.com:11001",
                30_000,
                null
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);

        // Pass full MSK hostname - should extract broker ID
        Name brokerName = setup.getBrokerName("b-2.payments.kafka.ap-south-1.amazonaws.com");

        assertNotNull(brokerName);
        assertEquals("prod-account.ap-south-1.payments-cluster.b-2", brokerName.toString());
    }

    @Test
    void testGetBrokerName_MultipleBrokers() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001"
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);

        Name broker1Name = setup.getBrokerName("b-1");
        Name broker2Name = setup.getBrokerName("b-2");

        assertEquals("default-account.us-east-1.default-cluster.b-1", broker1Name.toString());
        assertEquals("default-account.us-east-1.default-cluster.b-2", broker2Name.toString());
        assertNotEquals(broker1Name, broker2Name);
    }

    @Test
    void testHasBroker_ExistingBroker() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001"
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);

        assertTrue(setup.hasBroker("b-1"));
        assertTrue(setup.hasBroker("b-2"));
    }

    @Test
    void testHasBroker_NonExistingBroker() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001"
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);

        assertFalse(setup.hasBroker("b-2"));
        assertFalse(setup.hasBroker("b-3"));
    }

    @Test
    void testHasBroker_WithFullHostname() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001"
        );

        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);

        // Should work with full hostname (extracts broker ID)
        assertTrue(setup.hasBroker("b-1.trans.kafka.us-east-1.amazonaws.com"));
        assertTrue(setup.hasBroker("b-1"));
    }

    @Test
    void testGetBrokerCount() {
        ClusterConfig singleBroker = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        KafkaCircuitSetup setup1 = new KafkaCircuitSetup(singleBroker);
        assertEquals(1, setup1.getBrokerCount());

        ClusterConfig threeBrokers = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092,b-3.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001"
        );
        KafkaCircuitSetup setup3 = new KafkaCircuitSetup(threeBrokers);
        assertEquals(3, setup3.getBrokerCount());
    }

    @Test
    void testGetBrokerIds_DefensiveCopy() {
        ClusterConfig config = ClusterConfig.withDefaults("b-1.kafka.us-east-1.amazonaws.com:9092", "b-1.kafka.us-east-1.amazonaws.com:11001");
        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);

        List<String> brokerIds1 = setup.getBrokerIds();
        List<String> brokerIds2 = setup.getBrokerIds();

        // Should return different list instances (defensive copy)
        assertNotSame(brokerIds1, brokerIds2);

        // But with same content
        assertEquals(brokerIds1, brokerIds2);
    }

    @Test
    void testGetBrokerName_NullBrokerId() {
        ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);

        assertThrows(NullPointerException.class, () -> setup.getBrokerName(null));
    }

    @Test
    void testConstructor_NullConfig() {
        assertThrows(NullPointerException.class, () -> new KafkaCircuitSetup(null));
    }

    @Test
    void testCortexReuse_MultipleSetups() {
        // Each setup gets its own Cortex instance
        ClusterConfig config1 = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        ClusterConfig config2 = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");

        KafkaCircuitSetup setup1 = new KafkaCircuitSetup(config1);
        KafkaCircuitSetup setup2 = new KafkaCircuitSetup(config2);

        // With singleton pattern, both use same Cortex instance
        assertSame(setup1.getCortex(), setup2.getCortex());

        // But Circuits are still different (unique per setup)
        assertNotSame(setup1.getCircuit(), setup2.getCircuit());
    }

    @Test
    void testCircuitCreation() {
        ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        KafkaCircuitSetup setup = new KafkaCircuitSetup(config);

        assertNotNull(setup.getCircuit());
    }

    @Test
    void testMultipleSetups_IndependentCircuits() {
        // Each cluster gets its own isolated Circuit
        ClusterConfig config1 = ClusterConfig.withDefaults("b-1.cluster1:9092", "b-1.cluster1:11001");
        ClusterConfig config2 = ClusterConfig.withDefaults("b-1.cluster2:9092", "b-1.cluster2:11001");

        KafkaCircuitSetup setup1 = new KafkaCircuitSetup(config1);
        KafkaCircuitSetup setup2 = new KafkaCircuitSetup(config2);

        // Different Circuit instances - one per cluster
        assertNotSame(setup1.getCircuit(), setup2.getCircuit());
    }

    @Test
    void testHierarchicalNamesAcrossDifferentClusters() {
        // Test that different clusters create independent hierarchies
        ClusterConfig usEast = new ClusterConfig(
                "prod-account", "us-east-1", "transactions-cluster",
                "b-1.trans.kafka.us-east-1.amazonaws.com:9092",
                "b-1.trans.kafka.us-east-1.amazonaws.com:11001",
                30_000,
                null
        );

        ClusterConfig euWest = new ClusterConfig(
                "prod-account", "eu-west-1", "analytics-cluster",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:9092",
                "b-1.analytics.kafka.eu-west-1.amazonaws.com:11001",
                30_000,
                null
        );

        KafkaCircuitSetup usSetup = new KafkaCircuitSetup(usEast);
        KafkaCircuitSetup euSetup = new KafkaCircuitSetup(euWest);

        Name usBroker = usSetup.getBrokerName("b-1");
        Name euBroker = euSetup.getBrokerName("b-1");

        assertEquals("prod-account.us-east-1.transactions-cluster.b-1", usBroker.toString());
        assertEquals("prod-account.eu-west-1.analytics-cluster.b-1", euBroker.toString());
        assertNotEquals(usBroker, euBroker);
    }

    // ===== New Cell Hierarchy Tests =====

    @Test
    void testClusterCellCreation() {
        ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        setup = new KafkaCircuitSetup(config);

        Cell<BrokerMetrics, MonitorSignal> clusterCell = setup.getClusterCell();

        assertThat((Object) clusterCell).isNotNull();
        assertThat((Object) clusterCell).isInstanceOf(Cell.class);
    }

    @Test
    void testBrokerCellsCreated() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092,b-3.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001"
        );
        setup = new KafkaCircuitSetup(config);

        List<Cell<BrokerMetrics, MonitorSignal>> brokerCells = setup.getBrokerCells();

        assertThat((List<Cell<BrokerMetrics, MonitorSignal>>) brokerCells).hasSize(3);
    }

    @Test
    void testGetBrokerCellById() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001"
        );
        setup = new KafkaCircuitSetup(config);

        Cell<BrokerMetrics, MonitorSignal> broker1 = setup.getBrokerCell("b-1");
        Cell<BrokerMetrics, MonitorSignal> broker2 = setup.getBrokerCell("b-2");

        assertThat((Object) broker1).isNotNull();
        assertThat((Object) broker2).isNotNull();
        assertThat((Object) broker1).isNotSameAs(broker2);
    }

    @Test
    void testSubscriptionModelClusterLevel() throws InterruptedException {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001"
        );
        setup = new KafkaCircuitSetup(config);

        List<MonitorSignal> receivedSignals = new CopyOnWriteArrayList<>();

        Cell<BrokerMetrics, MonitorSignal> clusterCell = setup.getClusterCell();

        clusterCell.subscribe(setup.getCortex().subscriber(
                setup.getCortex().name("cluster-observer"),
                (subject, registrar) -> registrar.register(receivedSignals::add)
        ));

        // Emit metrics for 2 different brokers
        setup.getBrokerCell("b-1").emit(createHealthyMetrics("b-1"));
        setup.getBrokerCell("b-2").emit(createHealthyMetrics("b-2"));

        // Wait for async emissions
        Thread.sleep(200);

        // Verify cluster-level subscriber received all signals
        assertThat((List<MonitorSignal>) receivedSignals).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void testComposerInheritance() throws InterruptedException {
        ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        setup = new KafkaCircuitSetup(config);

        List<MonitorSignal> receivedSignals = new CopyOnWriteArrayList<>();

        Cell<BrokerMetrics, MonitorSignal> brokerCell = setup.getBrokerCell("localhost");

        brokerCell.subscribe(setup.getCortex().subscriber(
                setup.getCortex().name("test-subscriber"),
                (subject, registrar) -> registrar.register(receivedSignals::add)
        ));

        // Emit BrokerMetrics
        BrokerMetrics metrics = createHealthyMetrics("localhost");
        brokerCell.emit(metrics);

        // Wait for async emission
        Thread.sleep(100);

        // Verify transformation happened: BrokerMetrics → MonitorSignal
        assertThat((List<MonitorSignal>) receivedSignals).hasSize(1);
        MonitorSignal signal = receivedSignals.get(0);

        assertThat((Object) signal).isNotNull();
        assertThat((Object) signal).isInstanceOf(MonitorSignal.class);
        assertThat(signal.status().condition()).isNotNull();
    }

    @Test
    void testCircuitClose() {
        ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        setup = new KafkaCircuitSetup(config);

        // Verify close() works without errors
        setup.close();
    }

    @Test
    void testCreateMonitoringAgent() {
        ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
        setup = new KafkaCircuitSetup(config);

        // Create monitoring agent using helper method
        var agent = setup.createMonitoringAgent();

        assertThat(agent).isNotNull();
        assertThat(agent.getBrokerEndpoints()).hasSize(1);
        assertThat(agent.getVectorClock()).isNotNull();

        // Cleanup
        agent.shutdown();
    }

    @Test
    void testCreateMonitoringAgent_MultipleBrokers() {
        ClusterConfig config = ClusterConfig.withDefaults(
                "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092",
                "b-1.kafka.us-east-1.amazonaws.com:11001,b-2.kafka.us-east-1.amazonaws.com:11001"
        );
        setup = new KafkaCircuitSetup(config);

        // Create monitoring agent
        var agent = setup.createMonitoringAgent();

        assertThat(agent).isNotNull();
        assertThat(agent.getBrokerEndpoints()).hasSize(2);
        assertThat(agent.getBrokerEndpoints().get(0).brokerId()).isEqualTo("b-1");
        assertThat(agent.getBrokerEndpoints().get(1).brokerId()).isEqualTo("b-2");

        // Cleanup
        agent.shutdown();
    }

    /**
     * Helper method to create healthy broker metrics for testing.
     */
    private BrokerMetrics createHealthyMetrics(String brokerId) {
        return new BrokerMetrics(
                brokerId,
                500_000_000L,      // heapUsed: 500MB
                1_000_000_000L,    // heapMax: 1GB (50% usage - STABLE)
                0.50,              // cpuUsage: 50%
                1000L,             // requestRate
                5_000_000L,        // byteInRate
                5_000_000L,        // byteOutRate
                1,                 // activeControllers
                0,                 // underReplicatedPartitions
                0,                 // offlinePartitionsCount
                95L,               // networkProcessorAvgIdlePercent
                90L,               // requestHandlerAvgIdlePercent
                10L,               // fetchConsumerTotalTimeMs
                5L,                // produceTotalTimeMs
                System.currentTimeMillis()
        );
    }
}
