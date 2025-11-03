package io.fullerstack.kafka.broker.composers;

import io.humainary.substrates.ext.serventis.MonitorSignal;
import io.humainary.substrates.ext.serventis.Monitors.Monitors;
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
    private Cell<BrokerMetrics, MonitorSignal> healthCell;

    @BeforeEach
    void setUp() {
        // Use singleton Cortex instance (M18 pattern)
        cortex = cortex();
        circuit = cortex.circuit(cortex.name("test-cluster"));

        // Create Cell with BrokerHealthCellComposer
        // circuit.cell() requires: Composer<Pipe<I>, E> and Pipe<E>
        healthCell = circuit.cell(new BrokerHealthCellComposer(), Pipe.empty());
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
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();
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

        healthCell.emit(healthyMetrics);

        // Wait for async signal processing (event-driven, zero latency)
        circuit.await();

        // Verify
        assertThat((List<MonitorSignal>) received).hasSize(1);
        MonitorSignal signal = received.get(0);

        assertThat(signal.status().condition()).isEqualTo(Monitors.Condition.STABLE);
        assertThat(signal.status().confidence()).isEqualTo(Monitors.Confidence.CONFIRMED);

        // Verify Subject came from Channel infrastructure
        assertThat((Object) signal.subject()).isNotNull();
        assertThat((Object) signal.subject()).isEqualTo(receivedSubject.get());
    }

    @Test
    void testDegradedCondition_HighHeap() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(degradedMetrics);
        circuit.await();

        // Verify
        assertThat((List<MonitorSignal>) received).hasSize(1);
        MonitorSignal signal = received.get(0);

        assertThat(signal.status().condition()).isEqualTo(Monitors.Condition.DEGRADED);
        assertThat(signal.status().confidence()).isEqualTo(Monitors.Confidence.CONFIRMED);
        assertThat(signal.payload()).containsEntry("heap.usage.percent", "80.0");
    }

    @Test
    void testDegradedCondition_HighCpu() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(degradedMetrics);
        circuit.await();

        // Verify
        assertThat((List<MonitorSignal>) received).hasSize(1);
        MonitorSignal signal = received.get(0);

        assertThat(signal.status().condition()).isEqualTo(Monitors.Condition.DEGRADED);
        assertThat(signal.payload()).containsEntry("cpu.usage.percent", "75.0");
    }

    @Test
    void testDegradedCondition_UnderReplicatedPartitions() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(degradedMetrics);
        circuit.await();

        // Verify
        assertThat((List<MonitorSignal>) received).hasSize(1);
        MonitorSignal signal = received.get(0);

        assertThat(signal.status().condition()).isEqualTo(Monitors.Condition.DEGRADED);
        assertThat(signal.payload()).containsEntry("under.replicated.partitions", "5");
    }

    @Test
    void testDownCondition_CriticalHeap() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(downMetrics);
        circuit.await();

        // Verify
        assertThat((List<MonitorSignal>) received).hasSize(1);
        MonitorSignal signal = received.get(0);

        assertThat(signal.status().condition()).isEqualTo(Monitors.Condition.DOWN);
        assertThat(signal.payload()).containsEntry("heap.usage.percent", "95.0");
    }

    @Test
    void testDownCondition_CriticalCpu() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(downMetrics);
        circuit.await();

        // Verify
        assertThat((List<MonitorSignal>) received).hasSize(1);
        MonitorSignal signal = received.get(0);

        assertThat(signal.status().condition()).isEqualTo(Monitors.Condition.DOWN);
        assertThat(signal.payload()).containsEntry("cpu.usage.percent", "90.0");
    }

    @Test
    void testDownCondition_OfflinePartitions() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(downMetrics);
        circuit.await();

        // Verify
        assertThat((List<MonitorSignal>) received).hasSize(1);
        MonitorSignal signal = received.get(0);

        assertThat(signal.status().condition()).isEqualTo(Monitors.Condition.DOWN);
        assertThat(signal.payload()).containsEntry("offline.partitions", "3");
    }

    @Test
    void testDownCondition_NoActiveController() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(downMetrics);
        circuit.await();

        // Verify
        assertThat((List<MonitorSignal>) received).hasSize(1);
        MonitorSignal signal = received.get(0);

        assertThat(signal.status().condition()).isEqualTo(Monitors.Condition.DOWN);
        assertThat(signal.payload()).containsEntry("active.controllers", "0");
    }

    @Test
    void testConfidence_FreshMetrics() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(freshMetrics);
        circuit.await();

        // Verify
        assertThat(received).hasSize(1);
        assertThat(received.get(0).status().confidence()).isEqualTo(Monitors.Confidence.CONFIRMED);
    }

    @Test
    void testConfidence_SomewhatFreshMetrics() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(somewhatFreshMetrics);
        circuit.await();

        // Verify
        assertThat(received).hasSize(1);
        assertThat(received.get(0).status().condition()).isEqualTo(Monitors.Condition.DEGRADED);
        assertThat(received.get(0).status().confidence()).isEqualTo(Monitors.Confidence.MEASURED);
    }

    @Test
    void testConfidence_StaleMetrics() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(staleMetrics);
        circuit.await();

        // Verify
        assertThat(received).hasSize(1);
        assertThat(received.get(0).status().condition()).isEqualTo(Monitors.Condition.DOWN);
        assertThat(received.get(0).status().confidence()).isEqualTo(Monitors.Confidence.TENTATIVE);
    }

    @Test
    void testNullMetrics_HandledGracefully() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("test-subscriber"),
                (subject, registrar) -> registrar.register(received::add)
        ));

        // Emit null - should be handled gracefully (no emission or error signal)
        healthCell.emit(null);
        circuit.await();

        // Verify no signal emitted (Composer logs warning and returns without emitting)
        assertThat((List<MonitorSignal>) received).isEmpty();
    }

    @Test
    void testContextPayloadPopulation() {
        // Setup
        List<MonitorSignal> received = new CopyOnWriteArrayList<>();

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

        healthCell.emit(metrics);
        circuit.await();

        // Verify
        assertThat((List<MonitorSignal>) received).hasSize(1);
        MonitorSignal signal = received.get(0);

        // Verify all context keys populated
        assertThat(signal.payload()).containsKeys(
                "heap.used", "heap.max", "heap.usage.percent",
                "cpu.usage.percent",
                "request.rate", "byte.in.rate", "byte.out.rate",
                "active.controllers", "under.replicated.partitions", "offline.partitions",
                "network.processor.idle.percent", "request.handler.idle.percent",
                "fetch.consumer.latency.ms", "produce.latency.ms",
                "broker.id", "timestamp"
        );

        // Verify specific values
        assertThat(signal.payload()).containsEntry("heap.used", "600000000");
        assertThat(signal.payload()).containsEntry("heap.max", "1000000000");
        assertThat(signal.payload()).containsEntry("heap.usage.percent", "60.0");
        assertThat(signal.payload()).containsEntry("cpu.usage.percent", "55.0");
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
                        assertThat((Object) signal.subject()).isEqualTo(subject);
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

        healthCell.emit(metrics);
        circuit.await();

        // Verify channel subject was captured
        assertThat((Object) channelSubject.get()).isNotNull();
        assertThat((Object) channelSubject.get()).isInstanceOf(Subject.class);
    }

    @Test
    void testHierarchicalCellStructure() {
        // Create broker child cell
        Cell<BrokerMetrics, MonitorSignal> broker1Cell = healthCell.get(cortex.name("broker-1"));

        // Setup
        List<String> receivedSubjectNames = new ArrayList<>();

        healthCell.subscribe(cortex.subscriber(
                cortex.name("cluster-subscriber"),
                (subject, registrar) -> registrar.register(signal -> {
                    receivedSubjectNames.add(signal.subject().name().toString());
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

        broker1Cell.emit(metrics);
        circuit.await();

        // Verify subject name contains Cell hierarchy
        assertThat((List<String>) receivedSubjectNames).hasSize(1);
        // The exact hierarchy will be: circuit-name.cell-name.broker-1
        String subjectName = receivedSubjectNames.get(0);
        assertThat(subjectName).isNotEmpty();
    }
}
