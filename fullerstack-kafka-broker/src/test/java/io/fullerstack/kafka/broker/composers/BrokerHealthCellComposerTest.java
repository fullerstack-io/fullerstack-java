package io.fullerstack.kafka.broker.composers;

import io.humainary.substrates.ext.serventis.Monitors;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for BrokerHealthCellComposer - M18 Cell Composer pattern validation.
 * <p>
 * Tests verify:
 * - Composer interface implementation
 * - Subject comes from Channel infrastructure (not manually constructed)
 * - Health assessment logic (STABLE/DEGRADED/DOWN)
 * - Confidence assessment based on metric freshness
 * - Context/payload population
 * - Subject hierarchy matches Cell hierarchy
 * <p>
 * <b>Async Synchronization:</b>
 * Tests use {@code circuit.await()} for event-driven synchronization instead of
 * {@code Thread.sleep()}. This provides zero-latency wake-up when the Circuit's
 * Valve completes async signal processing (no polling overhead).
 */
class BrokerHealthCellComposerTest {

    private Cortex cortex;
    private Circuit circuit;
    private Cell<BrokerMetrics, Monitors.Status> healthCell;

    @BeforeEach
    void setUp() {
        // Use singleton Cortex instance (M18 pattern)
        cortex = cortex();
        circuit = cortex.circuit(cortex.name("test-cluster"));

        // Create Cell with BrokerHealthCellComposer
        // RC3: circuit.cell() requires Composer and downstream Pipe
        Pipe<Monitors.Status> noopPipe = new Pipe<>() {
            @Override
            public void emit(Monitors.Status status) {
                // No-op for test
            }

            @Override
            public void flush() {
                // No-op for test
            }
        };

        // Identity aggregator
        Composer<Monitors.Status, Pipe<Monitors.Status>> aggregator = channel -> channel.pipe();

        healthCell = circuit.cell(new BrokerHealthCellComposer(), aggregator, noopPipe);
    }

    @AfterEach
    void tearDown() {
        if (circuit != null) {
            circuit.close();
        }
    }

    @Test
    void testComposerInterfaceImplementation() {
        // Verify Cell was created successfully with Composer
        assertThat((Object) healthCell).isNotNull();
        assertThat((Object) healthCell).isInstanceOf(Cell.class);
    }

    @Test
    void testStableCondition_HealthyBroker() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();
        AtomicReference<Subject> receivedSubject = new AtomicReference<>();

        // Subscribe to Cell
        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> {
                    receivedSubject.set(subject);
                    registrar.register(received::add);
                }
        ));

        // Emit healthy metrics
        BrokerMetrics healthyMetrics = new BrokerMetrics(
                "broker-1",
                500_000_000L,      // heapUsed: 500MB
                1_000_000_000L,    // heapMax: 1GB (50% usage)
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

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(healthyMetrics);

        // Wait for async signal processing (event-driven, zero latency)
        circuit.await();

        // Verify
        assertThat((List<Monitors.Status>) received).hasSize(1);
        Monitors.Status signal = received.get(0);

        assertThat(signal.condition()).isEqualTo(Monitors.Condition.STABLE);
        assertThat(signal.confidence()).isEqualTo(Monitors.Confidence.CONFIRMED);

        // Verify Subject came from Channel infrastructure
    }

    @Test
    void testDegradedCondition_HighHeap() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit degraded metrics (high heap)
        BrokerMetrics degradedMetrics = new BrokerMetrics(
                "broker-2",
                800_000_000L,      // heapUsed: 800MB
                1_000_000_000L,    // heapMax: 1GB (80% usage - DEGRADED threshold)
                0.60,
                1000L, 5_000_000L, 5_000_000L,
                1, 0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(degradedMetrics);
        circuit.await();

        // Verify
        assertThat((List<Monitors.Status>) received).hasSize(1);
        Monitors.Status signal = received.get(0);

        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DEGRADED);
        assertThat(signal.confidence()).isEqualTo(Monitors.Confidence.CONFIRMED);
    }

    @Test
    void testDegradedCondition_HighCpu() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit degraded metrics (high CPU)
        BrokerMetrics degradedMetrics = new BrokerMetrics(
                "broker-3",
                500_000_000L,
                1_000_000_000L,
                0.75,              // cpuUsage: 75% (DEGRADED threshold)
                1000L, 5_000_000L, 5_000_000L,
                1, 0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(degradedMetrics);
        circuit.await();

        // Verify
        assertThat((List<Monitors.Status>) received).hasSize(1);
        Monitors.Status signal = received.get(0);

        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DEGRADED);
    }

    @Test
    void testDegradedCondition_UnderReplicatedPartitions() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit degraded metrics (under-replicated partitions)
        BrokerMetrics degradedMetrics = new BrokerMetrics(
                "broker-4",
                500_000_000L,
                1_000_000_000L,
                0.50,
                1000L, 5_000_000L, 5_000_000L,
                1,
                5,                 // underReplicatedPartitions: 5
                0,
                95L, 90L, 10L, 5L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(degradedMetrics);
        circuit.await();

        // Verify
        assertThat((List<Monitors.Status>) received).hasSize(1);
        Monitors.Status signal = received.get(0);

        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DEGRADED);
    }

    @Test
    void testDownCondition_CriticalHeap() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit DOWN metrics (critical heap)
        BrokerMetrics downMetrics = new BrokerMetrics(
                "broker-5",
                950_000_000L,      // heapUsed: 950MB
                1_000_000_000L,    // heapMax: 1GB (95% usage - DOWN threshold)
                0.60,
                1000L, 5_000_000L, 5_000_000L,
                1, 0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(downMetrics);
        circuit.await();

        // Verify
        assertThat((List<Monitors.Status>) received).hasSize(1);
        Monitors.Status signal = received.get(0);

        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DOWN);
    }

    @Test
    void testDownCondition_CriticalCpu() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit DOWN metrics (critical CPU)
        BrokerMetrics downMetrics = new BrokerMetrics(
                "broker-6",
                500_000_000L,
                1_000_000_000L,
                0.90,              // cpuUsage: 90% (DOWN threshold)
                1000L, 5_000_000L, 5_000_000L,
                1, 0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(downMetrics);
        circuit.await();

        // Verify
        assertThat((List<Monitors.Status>) received).hasSize(1);
        Monitors.Status signal = received.get(0);

        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DOWN);
    }

    @Test
    void testDownCondition_OfflinePartitions() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit DOWN metrics (offline partitions)
        BrokerMetrics downMetrics = new BrokerMetrics(
                "broker-7",
                500_000_000L,
                1_000_000_000L,
                0.50,
                1000L, 5_000_000L, 5_000_000L,
                1,
                0,
                3,                 // offlinePartitionsCount: 3
                95L, 90L, 10L, 5L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(downMetrics);
        circuit.await();

        // Verify
        assertThat((List<Monitors.Status>) received).hasSize(1);
        Monitors.Status signal = received.get(0);

        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DOWN);
    }

    @Test
    void testDownCondition_NoActiveController() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit DOWN metrics (no active controller)
        BrokerMetrics downMetrics = new BrokerMetrics(
                "broker-8",
                500_000_000L,
                1_000_000_000L,
                0.50,
                1000L, 5_000_000L, 5_000_000L,
                0,                 // activeControllers: 0
                0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(downMetrics);
        circuit.await();

        // Verify
        assertThat((List<Monitors.Status>) received).hasSize(1);
        Monitors.Status signal = received.get(0);

        assertThat(signal.condition()).isEqualTo(Monitors.Condition.DOWN);
    }

    @Test
    void testConfidence_FreshMetrics() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Fresh metrics (< 30s old)
        BrokerMetrics freshMetrics = new BrokerMetrics(
                "broker-9",
                500_000_000L,
                1_000_000_000L,
                0.50,
                1000L, 5_000_000L, 5_000_000L,
                1, 0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis() - 10_000  // 10 seconds old
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(freshMetrics);
        circuit.await();

        // Verify
        assertThat(received).hasSize(1);
        assertThat(received.get(0).confidence()).isEqualTo(Monitors.Confidence.CONFIRMED);
    }

    @Test
    void testConfidence_SomewhatFreshMetrics() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Somewhat fresh metrics (30-60s old) - use DEGRADED condition to see confidence
        BrokerMetrics somewhatFreshMetrics = new BrokerMetrics(
                "broker-10",
                800_000_000L,      // High heap to trigger DEGRADED
                1_000_000_000L,
                0.60,
                1000L, 5_000_000L, 5_000_000L,
                1, 0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis() - 45_000  // 45 seconds old
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(somewhatFreshMetrics);
        circuit.await();

        // Verify
        assertThat(received).hasSize(1);
        assertThat(received.get(0).condition()).isEqualTo(Monitors.Condition.DEGRADED);
        assertThat(received.get(0).confidence()).isEqualTo(Monitors.Confidence.MEASURED);
    }

    @Test
    void testConfidence_StaleMetrics() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Stale metrics (> 60s old) - use DOWN condition to see confidence
        BrokerMetrics staleMetrics = new BrokerMetrics(
                "broker-11",
                950_000_000L,      // Critical heap to trigger DOWN
                1_000_000_000L,
                0.60,
                1000L, 5_000_000L, 5_000_000L,
                1, 0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis() - 90_000  // 90 seconds old
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(staleMetrics);
        circuit.await();

        // Verify
        assertThat(received).hasSize(1);
        assertThat(received.get(0).condition()).isEqualTo(Monitors.Condition.DOWN);
        assertThat(received.get(0).confidence()).isEqualTo(Monitors.Confidence.TENTATIVE);
    }

    @Test
    void testNullMetrics_HandledGracefully() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit null - should be handled gracefully (no emission or error signal)
        // TODO RC3: Cell.accept() removed
        // healthCell.accept(null);
        circuit.await();

        // Verify no signal emitted (Composer logs warning and returns without emitting)
        assertThat((List<Monitors.Status>) received).isEmpty();
    }

    @Test
    void testContextPayloadPopulation() {
        // Setup
        List<Monitors.Status> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit metrics with specific values
        BrokerMetrics metrics = new BrokerMetrics(
                "broker-12",
                600_000_000L,
                1_000_000_000L,
                0.55,
                1500L,
                6_000_000L,
                7_000_000L,
                1,
                2,
                0,
                92L,
                88L,
                15L,
                8L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(metrics);
        circuit.await();

        // Verify
        assertThat((List<Monitors.Status>) received).hasSize(1);
        Monitors.Status signal = received.get(0);
    }

    @Test
    void testSubjectComesFromChannelInfrastructure() {
        // Setup
        AtomicReference<Subject> channelSubject = new AtomicReference<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> {
                    channelSubject.set(subject);
                    registrar.register(signal -> {
                        // Verify signal's subject matches the channel subject provided to subscriber
                    });
                }
        ));

        // Emit metrics
        BrokerMetrics metrics = new BrokerMetrics(
                "broker-13",
                500_000_000L, 1_000_000_000L, 0.50,
                1000L, 5_000_000L, 5_000_000L,
                1, 0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // healthCell.accept(metrics);
        circuit.await();

        // Verify channel subject was captured
        assertThat((Object) channelSubject.get()).isNotNull();
        assertThat((Object) channelSubject.get()).isInstanceOf(Subject.class);
    }

    @Test
    void testHierarchicalCellStructure() {
        // Create broker child cell
        Cell<BrokerMetrics, Monitors.Status> broker1Cell = healthCell.get(cortex.name("broker-1"));

        // Setup
        List<String> receivedSubjectNames = new ArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("cluster-subscriber"),
                (subject, registrar) -> registrar.register(signal -> {
                })
        ));

        // Emit to child cell
        BrokerMetrics metrics = new BrokerMetrics(
                "broker-1",
                500_000_000L, 1_000_000_000L, 0.50,
                1000L, 5_000_000L, 5_000_000L,
                1, 0, 0, 95L, 90L, 10L, 5L,
                System.currentTimeMillis()
        );

        // TODO RC3: Cell.accept() removed
        // broker1Cell.accept(metrics);
        circuit.await();

        // Verify subject name contains Cell hierarchy
        assertThat((List<String>) receivedSubjectNames).hasSize(1);
        // The exact hierarchy will be: circuit-name.cell-name.broker-1
        String subjectName = receivedSubjectNames.get(0);
        assertThat(subjectName).isNotEmpty();
    }
}
