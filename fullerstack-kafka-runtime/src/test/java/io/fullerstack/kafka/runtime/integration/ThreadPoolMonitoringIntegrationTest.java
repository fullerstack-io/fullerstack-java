package io.fullerstack.kafka.runtime.integration;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import io.fullerstack.kafka.core.config.BrokerEndpoint;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.fullerstack.kafka.core.config.ConsumerEndpoint;
import io.fullerstack.kafka.core.config.ConsumerSensorConfig;
import io.fullerstack.kafka.core.config.JmxConnectionPoolConfig;
import io.fullerstack.kafka.core.config.ProducerEndpoint;
import io.fullerstack.kafka.core.config.ProducerSensorConfig;
import io.fullerstack.kafka.runtime.KafkaObsConfig;
import io.fullerstack.kafka.runtime.KafkaObservabilityRuntime;
import io.fullerstack.serventis.signals.ResourceSignal;
import io.fullerstack.substrates.circuit.SequentialCircuit;
import io.humainary.modules.serventis.resources.api.Resources;
import io.humainary.substrates.api.Substrates.Cell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for thread pool monitoring runtime integration.
 * <p>
 * Validates end-to-end flow:
 * - Thread pool Cell hierarchy creation
 * - ThreadPoolMetrics → ResourceSignal transformation
 * - Signal emission and subscription
 * - Runtime lifecycle (start/close)
 * <p>
 * Note: JMX collection from real brokers is tested separately in sensor-level tests.
 * This test focuses on runtime integration using manual metric emission.
 */
class ThreadPoolMonitoringIntegrationTest {

    private KafkaObservabilityRuntime runtime;
    private List<ResourceSignal> capturedSignals;

    @BeforeEach
    void setUp() {
        capturedSignals = new CopyOnWriteArrayList<>();

        // Create minimal runtime config
        BrokerSensorConfig brokerConfig = new BrokerSensorConfig(
            List.of(
                new BrokerEndpoint("broker-1", "localhost", 9092, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi"),
                new BrokerEndpoint("broker-2", "localhost", 9093, "service:jmx:rmi:///jndi/rmi://localhost:11002/jmxrmi")
            ),
            60000,  // 1 minute interval
            true    // Use connection pooling
        );

        // Minimal configs for producer/consumer (not under test)
        ProducerSensorConfig producerConfig = new ProducerSensorConfig(
            List.of(new ProducerEndpoint("test-producer", "service:jmx:rmi:///jndi/rmi://localhost:12001/jmxrmi")),
            60000,
            true
        );

        ConsumerSensorConfig consumerConfig = new ConsumerSensorConfig(
            "localhost:9092",
            List.of(new ConsumerEndpoint("test-consumer", "test-group", "service:jmx:rmi:///jndi/rmi://localhost:13001/jmxrmi")),
            60000,
            JmxConnectionPoolConfig.withPoolingEnabled()
        );

        KafkaObsConfig config = KafkaObsConfig.of(
            "test-cluster",
            brokerConfig,
            producerConfig,
            consumerConfig
        );

        runtime = new KafkaObservabilityRuntime(config);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void threadPoolCellHierarchyCreated() {
        Cell<ThreadPoolMetrics, ResourceSignal> rootCell = runtime.getBrokerThreadPoolsRootCell();

        assertThat((Object) rootCell).isNotNull();
    }

    @Test
    void metricsEmissionTriggersResourceSignalTransformation() {
        Cell<ThreadPoolMetrics, ResourceSignal> rootCell = runtime.getBrokerThreadPoolsRootCell();

        // Subscribe to signals
        rootCell.subscribe(cortex().subscriber(
            cortex().name("test-subscriber"),
            (subject, registrar) -> registrar.register(capturedSignals::add)
        ));

        // Emit healthy pool metrics (50% idle)
        emitMetrics("broker-1", ThreadPoolType.NETWORK, 0.50);

        // Wait for signal processing to complete
        ((SequentialCircuit) runtime.getCircuit()).await();

        // Verify ResourceSignal was emitted
        assertThat(capturedSignals).hasSizeGreaterThanOrEqualTo(1);

        ResourceSignal signal = capturedSignals.get(0);
        assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
        assertThat(signal.units()).isEqualTo(5);  // 5 idle threads
        assertThat(signal.payload().get("state")).isEqualTo("AVAILABLE");
        assertThat(signal.payload().get("brokerId")).isEqualTo("broker-1");
        assertThat(signal.payload().get("poolType")).isEqualTo("network");
    }

    @Test
    void degradedPoolEmitsGrantWithDegradedState() {
        Cell<ThreadPoolMetrics, ResourceSignal> rootCell = runtime.getBrokerThreadPoolsRootCell();

        rootCell.subscribe(cortex().subscriber(
            cortex().name("test-subscriber"),
            (subject, registrar) -> registrar.register(capturedSignals::add)
        ));

        // Emit degraded pool metrics (20% idle)
        emitMetrics("broker-1", ThreadPoolType.IO, 0.20);

        // Wait for signal processing to complete
        ((SequentialCircuit) runtime.getCircuit()).await();

        assertThat(capturedSignals).hasSizeGreaterThanOrEqualTo(1);

        ResourceSignal signal = capturedSignals.get(0);
        assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
        assertThat(signal.units()).isEqualTo(2);  // 2 idle threads
        assertThat(signal.payload().get("state")).isEqualTo("DEGRADED");
    }

    @Test
    void exhaustedPoolEmitsDenySignal() {
        Cell<ThreadPoolMetrics, ResourceSignal> rootCell = runtime.getBrokerThreadPoolsRootCell();

        rootCell.subscribe(cortex().subscriber(
            cortex().name("test-subscriber"),
            (subject, registrar) -> registrar.register(capturedSignals::add)
        ));

        // Emit exhausted pool metrics (5% idle)
        int totalThreads = 10;
        int idleThreads = 0;  // 0% idle for EXHAUSTED
        int activeThreads = totalThreads - idleThreads;

        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-2",
            ThreadPoolType.NETWORK,
            totalThreads,
            activeThreads,
            idleThreads,
            0.05,  // 5% idle (EXHAUSTED)
            15L,   // Queue building
            0L,
            3L,    // Rejections occurring
            System.currentTimeMillis()
        );

        Cell<ThreadPoolMetrics, ResourceSignal> broker2Cell = rootCell.get(cortex().name("broker-2"));
        Cell<ThreadPoolMetrics, ResourceSignal> networkCell = broker2Cell.get(cortex().name("network"));
        networkCell.emit(metrics);

        // Wait for signal processing to complete
        ((SequentialCircuit) runtime.getCircuit()).await();

        assertThat(capturedSignals).hasSizeGreaterThanOrEqualTo(1);

        ResourceSignal signal = capturedSignals.get(0);
        assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
        assertThat(signal.units()).isEqualTo(0);  // No capacity
        assertThat(signal.payload().get("state")).isEqualTo("EXHAUSTED");
        assertThat(signal.payload().get("queueSize")).isEqualTo("15");
        assertThat(signal.payload().get("rejectionCount")).isEqualTo("3");
    }

    @Test
    void resourceSignalsObservableViaRootCell() {
        Cell<ThreadPoolMetrics, ResourceSignal> rootCell = runtime.getBrokerThreadPoolsRootCell();

        // Subscribe to root - should receive signals from ALL brokers/pools
        rootCell.subscribe(cortex().subscriber(
            cortex().name("test-subscriber"),
            (subject, registrar) -> registrar.register(capturedSignals::add)
        ));

        // Emit from multiple brokers and pools
        emitMetrics("broker-1", ThreadPoolType.NETWORK, 0.50);  // Healthy
        emitMetrics("broker-1", ThreadPoolType.IO, 0.20);       // Degraded
        emitMetrics("broker-2", ThreadPoolType.NETWORK, 0.60);  // Healthy

        // Wait for signal processing to complete
        ((SequentialCircuit) runtime.getCircuit()).await();

        // Should receive all 3 signals
        assertThat(capturedSignals).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void multiplePoolTypesPerBroker() {
        Cell<ThreadPoolMetrics, ResourceSignal> rootCell = runtime.getBrokerThreadPoolsRootCell();

        rootCell.subscribe(cortex().subscriber(
            cortex().name("test-subscriber"),
            (subject, registrar) -> registrar.register(capturedSignals::add)
        ));

        // Emit from different pool types for same broker
        emitMetrics("broker-1", ThreadPoolType.NETWORK, 0.60);
        emitMetrics("broker-1", ThreadPoolType.IO, 0.45);
        emitMetrics("broker-1", ThreadPoolType.LOG_CLEANER, 0.30);

        // Wait for signal processing to complete
        ((SequentialCircuit) runtime.getCircuit()).await();

        // Signals are emitted synchronously but we need to ensure all transformations are complete
        assertThat(capturedSignals).hasSize(3);

        // All should be from broker-1
        assertThat(capturedSignals)
            .allMatch(signal -> signal.payload().get("brokerId").equals("broker-1"));
    }

    // Helper method
    private void emitMetrics(String brokerId, ThreadPoolType poolType, double avgIdlePercent) {
        int totalThreads = 10;
        int idleThreads = (int) Math.round(totalThreads * avgIdlePercent);
        int activeThreads = totalThreads - idleThreads;

        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            brokerId,
            poolType,
            totalThreads,
            activeThreads,
            idleThreads,
            avgIdlePercent,
            0L, 0L, 0L,
            System.currentTimeMillis()
        );

        Cell<ThreadPoolMetrics, ResourceSignal> rootCell = runtime.getBrokerThreadPoolsRootCell();
        Cell<ThreadPoolMetrics, ResourceSignal> brokerCell = rootCell.get(cortex().name(brokerId));
        Cell<ThreadPoolMetrics, ResourceSignal> poolCell = brokerCell.get(cortex().name(poolType.displayName()));
        poolCell.emit(metrics);
    }
}
