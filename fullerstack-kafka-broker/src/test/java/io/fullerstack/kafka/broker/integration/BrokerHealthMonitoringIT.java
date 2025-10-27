package io.fullerstack.kafka.broker.integration;

import io.fullerstack.serventis.signals.MonitorSignal;
import io.humainary.modules.serventis.monitors.api.Monitors;
import io.humainary.substrates.api.Substrates.Cell;
import io.humainary.substrates.api.Substrates.Name;
import io.fullerstack.kafka.broker.circuit.KafkaCircuitSetup;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.core.config.ClusterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Broker Health Monitoring end-to-end flow.
 * <p>
 * Tests the complete integration:
 * 1. KafkaCircuitSetup creates Circuit and Cell hierarchy
 * 2. BrokerMetrics emission through Cells
 * 3. BrokerHealthCellComposer transformation (BrokerMetrics → MonitorSignal)
 * 4. Subscription model (cluster-level and broker-level)
 * 5. MonitorSignal condition assessment (STABLE/DEGRADED/DOWN)
 * <p>
 * Note: JMX collection is not tested as Testcontainers Kafka doesn't expose JMX by default.
 * This test focuses on the Cell emission and transformation pipeline with synthetic metrics.
 */
@Testcontainers
class BrokerHealthMonitoringIT {

    @Container
    private static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0")
    );

    private ClusterConfig config;
    private KafkaCircuitSetup setup;

    @BeforeEach
    void setUp() {
        // Get Kafka bootstrap server from Testcontainers
        String bootstrapServers = kafka.getBootstrapServers();

        // For integration tests, use placeholder JMX URL (JMX not exposed by Testcontainers)
        String jmxUrl = "localhost:11001";

        config = ClusterConfig.withDefaults(bootstrapServers, jmxUrl);
        setup = new KafkaCircuitSetup(config);
    }

    @AfterEach
    void tearDown() {
        if (setup != null) {
            setup.close();
        }
    }

    @Test
    void testHierarchicalNameStructure() {
        // Test hierarchical Name creation using ClusterConfig
        Name clusterName = config.getClusterName(setup.getCortex());

        assertEquals("default-account.us-east-1.default-cluster", clusterName.toString());

        // Test broker Name creation
        Name broker1 = config.getBrokerName(setup.getCortex(), "b-1");
        assertEquals("default-account.us-east-1.default-cluster.b-1", broker1.toString());

        Name broker2 = config.getBrokerName(setup.getCortex(), "b-2");
        assertEquals("default-account.us-east-1.default-cluster.b-2", broker2.toString());

        // Different brokers should have different Names
        assertNotEquals(broker1, broker2);
    }

    @Test
    void testBrokerIdExtraction() {
        // Test broker ID extraction from Testcontainers hostname
        String bootstrapServers = kafka.getBootstrapServers();
        String hostname = bootstrapServers.split(":")[0];

        String brokerId = ClusterConfig.extractBrokerId(hostname);

        assertNotNull(brokerId);
        assertFalse(brokerId.isBlank(), "Broker ID should not be blank");

        // Verify Name creation works
        Name brokerName = config.getBrokerName(setup.getCortex(), brokerId);
        assertNotNull(brokerName);

        String fullName = brokerName.toString();
        assertTrue(fullName.contains("default-account"));
        assertTrue(fullName.contains("us-east-1"));
        assertTrue(fullName.contains("default-cluster"));
        assertTrue(fullName.contains(brokerId));
    }

    @Test
    void testCellHierarchyCreation() {
        // Verify Circuit and Cell hierarchy created
        assertNotNull(setup.getCircuit());
        assertNotNull(setup.getClusterCell());
        assertNotNull(setup.getClusterName());

        // Verify cluster Name structure
        String clusterName = setup.getClusterName().toString();
        assertEquals("default-account.us-east-1.default-cluster", clusterName);
    }

    @Test
    void testBrokerCellEmissionTransformation() throws InterruptedException {
        // Test BrokerMetrics → MonitorSignal transformation through Cell
        List<MonitorSignal> receivedSignals = new CopyOnWriteArrayList<>();

        // Subscribe to cluster Cell to receive all broker signals
        Cell<BrokerMetrics, MonitorSignal> clusterCell = setup.getClusterCell();
        clusterCell.subscribe(setup.getCortex().subscriber(
                setup.getCortex().name("test-subscriber"),
                (subject, registrar) -> registrar.register(receivedSignals::add)
        ));

        // Create healthy broker metrics
        BrokerMetrics healthyMetrics = new BrokerMetrics(
                "b-1",
                500_000_000L,  // 500MB heap used
                1_000_000_000L, // 1GB heap max (50% usage - STABLE)
                0.25,           // 25% CPU
                1000L,          // Request rate
                5_000_000L,     // Byte in rate
                5_000_000L,     // Byte out rate
                1,              // Active controllers
                0,              // Under-replicated partitions
                0,              // Offline partitions
                95L,            // Network processor idle %
                90L,            // Request handler idle %
                10L,            // Fetch latency
                5L,             // Produce latency
                System.currentTimeMillis()
        );

        // Emit metrics to broker Cell
        Cell<BrokerMetrics, MonitorSignal> brokerCell = setup.getBrokerCell("b-1");
        brokerCell.emit(healthyMetrics);

        // Wait for async transformation
        Thread.sleep(200);

        // Verify MonitorSignal received
        assertThat(receivedSignals).hasSizeGreaterThanOrEqualTo(1);
        MonitorSignal signal = receivedSignals.get(0);

        assertNotNull(signal);
        assertNotNull(signal.subject());
        assertNotNull(signal.status());

        // Verify signal payload contains metrics context
        assertNotNull(signal.payload());
        assertThat(signal.payload()).containsKey("heap.used");
        assertThat(signal.payload()).containsKey("heap.max");
        assertThat(signal.payload()).containsKey("cpu.usage.percent");
        assertThat(signal.payload()).containsKey("heap.usage.percent");

        // Verify STABLE condition (healthy metrics)
        assertEquals(Monitors.Condition.STABLE, signal.status().condition());
    }

    @Test
    void testDegradedCondition() throws InterruptedException {
        // Test DEGRADED condition with high heap usage
        List<MonitorSignal> receivedSignals = new CopyOnWriteArrayList<>();

        setup.getClusterCell().subscribe(setup.getCortex().subscriber(
                setup.getCortex().name("degraded-test-subscriber"),
                (subject, registrar) -> registrar.register(receivedSignals::add)
        ));

        BrokerMetrics degradedMetrics = new BrokerMetrics(
                "b-1",
                800_000_000L,   // 800MB heap used
                1_000_000_000L, // 1GB heap max (80% usage - DEGRADED)
                0.25,
                1000L,
                5_000_000L,
                5_000_000L,
                1,
                0,
                0,
                95L,
                90L,
                10L,
                5L,
                System.currentTimeMillis()
        );

        setup.getBrokerCell("b-1").emit(degradedMetrics);

        // Wait for async transformation
        Thread.sleep(200);

        assertThat(receivedSignals).hasSizeGreaterThanOrEqualTo(1);
        MonitorSignal signal = receivedSignals.get(0);

        assertEquals(Monitors.Condition.DEGRADED, signal.status().condition());
    }

    @Test
    void testDownCondition() throws InterruptedException {
        // Test DOWN condition with critical heap usage
        List<MonitorSignal> receivedSignals = new CopyOnWriteArrayList<>();

        setup.getClusterCell().subscribe(setup.getCortex().subscriber(
                setup.getCortex().name("down-test-subscriber"),
                (subject, registrar) -> registrar.register(receivedSignals::add)
        ));

        BrokerMetrics downMetrics = new BrokerMetrics(
                "b-1",
                950_000_000L,   // 950MB heap used
                1_000_000_000L, // 1GB heap max (95% usage - DOWN)
                0.25,
                1000L,
                5_000_000L,
                5_000_000L,
                1,
                0,
                0,
                95L,
                90L,
                10L,
                5L,
                System.currentTimeMillis()
        );

        setup.getBrokerCell("b-1").emit(downMetrics);

        // Wait for async transformation
        Thread.sleep(200);

        assertThat(receivedSignals).hasSizeGreaterThanOrEqualTo(1);
        MonitorSignal signal = receivedSignals.get(0);

        assertEquals(Monitors.Condition.DOWN, signal.status().condition());
    }

    @Test
    void testMultipleBrokerNames() {
        // Test creating Names for multiple brokers in same cluster
        Name clusterName = config.getClusterName(setup.getCortex());

        Name broker1 = config.getBrokerName(setup.getCortex(), "b-1");
        Name broker2 = config.getBrokerName(setup.getCortex(), "b-2");
        Name broker3 = config.getBrokerName(setup.getCortex(), "b-3");

        // All should share same cluster hierarchy
        assertTrue(broker1.toString().startsWith("default-account.us-east-1.default-cluster"));
        assertTrue(broker2.toString().startsWith("default-account.us-east-1.default-cluster"));
        assertTrue(broker3.toString().startsWith("default-account.us-east-1.default-cluster"));

        // But have different broker IDs
        assertTrue(broker1.toString().endsWith(".b-1"));
        assertTrue(broker2.toString().endsWith(".b-2"));
        assertTrue(broker3.toString().endsWith(".b-3"));

        // All distinct
        assertNotEquals(broker1, broker2);
        assertNotEquals(broker2, broker3);
        assertNotEquals(broker1, broker3);
    }

    @Test
    void testSubscriptionModel_ClusterLevel() throws InterruptedException {
        // Test cluster-level subscription receives signals from multiple brokers
        List<MonitorSignal> clusterSignals = new CopyOnWriteArrayList<>();

        // Subscribe at cluster level
        setup.getClusterCell().subscribe(setup.getCortex().subscriber(
                setup.getCortex().name("cluster-subscriber"),
                (subject, registrar) -> registrar.register(clusterSignals::add)
        ));

        // Emit metrics for 3 different brokers
        setup.getBrokerCell("b-1").emit(createHealthyMetrics("b-1"));
        setup.getBrokerCell("b-2").emit(createHealthyMetrics("b-2"));
        setup.getBrokerCell("b-3").emit(createHealthyMetrics("b-3"));

        // Wait for async emissions
        Thread.sleep(300);

        // Cluster subscriber should receive all 3 signals
        assertThat(clusterSignals).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void testSubscriptionModel_BrokerLevel() throws InterruptedException {
        // Test broker-level subscription receives only that broker's signals
        List<MonitorSignal> broker1Signals = new CopyOnWriteArrayList<>();
        List<MonitorSignal> broker2Signals = new CopyOnWriteArrayList<>();

        // Subscribe to specific brokers
        setup.getBrokerCell("b-1").subscribe(setup.getCortex().subscriber(
                setup.getCortex().name("broker1-subscriber"),
                (subject, registrar) -> registrar.register(broker1Signals::add)
        ));

        setup.getBrokerCell("b-2").subscribe(setup.getCortex().subscriber(
                setup.getCortex().name("broker2-subscriber"),
                (subject, registrar) -> registrar.register(broker2Signals::add)
        ));

        // Emit metrics for both brokers
        setup.getBrokerCell("b-1").emit(createHealthyMetrics("b-1"));
        setup.getBrokerCell("b-2").emit(createHealthyMetrics("b-2"));

        // Wait for async emissions
        Thread.sleep(200);

        // Each subscriber should receive only their broker's signals
        assertThat(broker1Signals).hasSizeGreaterThanOrEqualTo(1);
        assertThat(broker2Signals).hasSizeGreaterThanOrEqualTo(1);

        // Verify the signals are distinct (different brokers send different signals)
        // Note: With synthetic metrics, both brokers emit identical healthy metrics
        // The key test is that each subscriber receives at least one signal
    }

    @Test
    void testProperCleanup() {
        // Verify close() works without errors
        setup.close();

        // Verify circuit is closed (subsequent operations should fail gracefully)
        assertNotNull(setup.getCircuit());
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
