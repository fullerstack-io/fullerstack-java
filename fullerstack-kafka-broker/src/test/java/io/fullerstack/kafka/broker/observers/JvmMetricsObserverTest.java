package io.fullerstack.kafka.broker.observers;

import io.fullerstack.kafka.broker.models.JvmGcMetrics;
import io.fullerstack.kafka.broker.models.JvmMemoryMetrics;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.*;

/**
 * Tests for JvmMetricsObserver (Layer 1 - OBSERVE).
 *
 * <p><b>Test Coverage:</b>
 * <ul>
 *   <li>Heap Gauges: OVERFLOW (>=90%), INCREMENT (growing), DECREMENT (shrinking)</li>
 *   <li>Non-heap Gauges: Same signals for non-heap memory</li>
 *   <li>GC Counters: INCREMENT (normal), OVERFLOW (GC storm detection)</li>
 *   <li>Signal-flow compliance: NO pattern detection, only raw signal emission</li>
 *   <li>Multi-broker support: Independent tracking per broker</li>
 *   <li>Resource cleanup: Proper Circuit/Conduit lifecycle</li>
 * </ul>
 *
 * <p><b>CRITICAL:</b> This is Layer 1 (OBSERVE) - tests should verify ONLY raw signal emission,
 * NOT pattern detection or assessment (that's Layer 2's responsibility).
 */
@DisplayName("JvmMetricsObserver (Layer 1 - OBSERVE)")
class JvmMetricsObserverTest {

    private Circuit circuit;
    private JvmMetricsObserver receptor;

    // Signal capture lists
    private List<CapturedGaugeSignal> capturedGaugeSigns;
    private List<CapturedCounterSignal> capturedCounterSigns;

    @BeforeEach
    void setUp() {
        circuit = cortex().circuit(cortex().name("test-jvm-receptor"));
        receptor = new JvmMetricsObserver(circuit);

        capturedGaugeSigns = new CopyOnWriteArrayList<>();
        capturedCounterSigns = new CopyOnWriteArrayList<>();

        // Subscribe to gauges to capture emitted signals
        receptor.gauges().subscribe(cortex().subscriber(
            cortex().name("gauge-test-subscriber"),
            (subject, registrar) -> registrar.register(sign -> {
                String entityName = subject.name().toString();
                capturedGaugeSigns.add(new CapturedGaugeSignal(entityName, sign));
            })
        ));

        // Subscribe to counters to capture emitted signals
        receptor.counters().subscribe(cortex().subscriber(
            cortex().name("counter-test-subscriber"),
            (subject, registrar) -> registrar.register(sign -> {
                String entityName = subject.name().toString();
                capturedCounterSigns.add(new CapturedCounterSignal(entityName, sign));
            })
        ));
    }

    @AfterEach
    void tearDown() {
        if (receptor != null) {
            receptor.close();
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private JvmMemoryMetrics createMemoryMetrics(String brokerId, double heapUtil, double nonHeapUtil) {
        long maxHeap = 10_000_000_000L; // 10GB
        long usedHeap = (long) (maxHeap * heapUtil);
        long maxNonHeap = 2_000_000_000L; // 2GB
        long usedNonHeap = (long) (maxNonHeap * nonHeapUtil);

        return new JvmMemoryMetrics(
            brokerId,
            usedHeap,
            maxHeap,
            maxHeap,
            usedNonHeap,
            maxNonHeap,
            maxNonHeap,
            System.currentTimeMillis()
        );
    }

    private JvmGcMetrics createGcMetrics(String brokerId, String collectorName, long count, long time) {
        return new JvmGcMetrics(
            brokerId,
            collectorName,
            count,
            time,
            System.currentTimeMillis()
        );
    }

    private void awaitSignalPropagation() {
        circuit.await();
    }

    // ========================================================================
    // Heap Gauges Tests
    // ========================================================================

    @Nested
    @DisplayName("Heap Memory Gauges")
    class HeapMemoryTests {

        @Test
        @DisplayName("Should emit OVERFLOW when heap >= 90%")
        void shouldEmitOverflowWhenHeapHigh() {
            // Given: 95% heap utilization
            JvmMemoryMetrics metrics = createMemoryMetrics("broker-1", 0.95, 0.50);

            // When
            receptor.emitMemory(metrics);
            awaitSignalPropagation();

            // Then: Should emit OVERFLOW for heap
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.heap.broker-1"))
                .extracting(s -> s.sign)
                .contains(Gauges.Sign.OVERFLOW);
        }

        @Test
        @DisplayName("Should emit INCREMENT when heap grows")
        void shouldEmitIncrementWhenHeapGrows() {
            // Given: Heap grows from 50% to 60%
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.50, 0.50));
            awaitSignalPropagation();
            capturedGaugeSigns.clear();

            // When
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.60, 0.50));
            awaitSignalPropagation();

            // Then: Should emit INCREMENT for heap
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.heap.broker-1"))
                .extracting(s -> s.sign)
                .contains(Gauges.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should emit DECREMENT when heap shrinks")
        void shouldEmitDecrementWhenHeapShrinks() {
            // Given: Heap shrinks from 70% to 50%
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.70, 0.50));
            awaitSignalPropagation();
            capturedGaugeSigns.clear();

            // When
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.50, 0.50));
            awaitSignalPropagation();

            // Then: Should emit DECREMENT for heap
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.heap.broker-1"))
                .extracting(s -> s.sign)
                .contains(Gauges.Sign.DECREMENT);
        }

        @Test
        @DisplayName("Should emit INCREMENT on first observation")
        void shouldEmitIncrementOnFirstObservation() {
            // When: First observation
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.50, 0.50));
            awaitSignalPropagation();

            // Then: Should emit INCREMENT for heap (first observation baseline)
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.heap.broker-1"))
                .extracting(s -> s.sign)
                .contains(Gauges.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should prefer OVERFLOW over INCREMENT when heap >= 90% and growing")
        void shouldPreferOverflowWhenHeapHighAndGrowing() {
            // Given: Heap at 85%
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.85, 0.50));
            awaitSignalPropagation();
            capturedGaugeSigns.clear();

            // When: Heap grows to 95% (both growing AND overflow threshold)
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.95, 0.50));
            awaitSignalPropagation();

            // Then: Should emit OVERFLOW (takes precedence over INCREMENT)
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.heap.broker-1"))
                .extracting(s -> s.sign)
                .containsOnly(Gauges.Sign.OVERFLOW);
        }
    }

    // ========================================================================
    // Non-Heap Gauges Tests
    // ========================================================================

    @Nested
    @DisplayName("Non-Heap Memory Gauges")
    class NonHeapMemoryTests {

        @Test
        @DisplayName("Should emit OVERFLOW when non-heap >= 90%")
        void shouldEmitOverflowWhenNonHeapHigh() {
            // Given: 92% non-heap utilization
            JvmMemoryMetrics metrics = createMemoryMetrics("broker-1", 0.50, 0.92);

            // When
            receptor.emitMemory(metrics);
            awaitSignalPropagation();

            // Then: Should emit OVERFLOW for non-heap
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.non-heap.broker-1"))
                .extracting(s -> s.sign)
                .contains(Gauges.Sign.OVERFLOW);
        }

        @Test
        @DisplayName("Should emit INCREMENT when non-heap grows")
        void shouldEmitIncrementWhenNonHeapGrows() {
            // Given: Non-heap grows from 40% to 55%
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.50, 0.40));
            awaitSignalPropagation();
            capturedGaugeSigns.clear();

            // When
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.50, 0.55));
            awaitSignalPropagation();

            // Then: Should emit INCREMENT for non-heap
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.non-heap.broker-1"))
                .extracting(s -> s.sign)
                .contains(Gauges.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should emit DECREMENT when non-heap shrinks")
        void shouldEmitDecrementWhenNonHeapShrinks() {
            // Given: Non-heap shrinks from 60% to 45%
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.50, 0.60));
            awaitSignalPropagation();
            capturedGaugeSigns.clear();

            // When
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.50, 0.45));
            awaitSignalPropagation();

            // Then: Should emit DECREMENT for non-heap
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.non-heap.broker-1"))
                .extracting(s -> s.sign)
                .contains(Gauges.Sign.DECREMENT);
        }
    }

    // ========================================================================
    // GC Counters Tests
    // ========================================================================

    @Nested
    @DisplayName("GC Counters")
    class GcCountersTests {

        @Test
        @DisplayName("Should emit INCREMENT for normal GC count increase")
        void shouldEmitIncrementForNormalGcIncrease() {
            // Given: Normal GC count
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 100L, 5000L));
            awaitSignalPropagation();
            capturedCounterSigns.clear();

            // When: GC count increases by 1
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 101L, 5100L));
            awaitSignalPropagation();

            // Then: Should emit INCREMENT for count
            assertThat(capturedCounterSigns)
                .filteredOn(s -> s.entityName.contains("jvm.gc.count.broker-1.G1YoungGeneration"))
                .extracting(s -> s.sign)
                .contains(Counters.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should emit OVERFLOW for GC storm (rapid increase)")
        void shouldEmitOverflowForGcStorm() {
            // Given: Previous GC count
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 100L, 5000L));
            awaitSignalPropagation();
            capturedCounterSigns.clear();

            // When: GC count increases by 50 (storm threshold)
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 150L, 8000L));
            awaitSignalPropagation();

            // Then: Should emit OVERFLOW for count (indicating GC storm)
            assertThat(capturedCounterSigns)
                .filteredOn(s -> s.entityName.contains("jvm.gc.count.broker-1.G1YoungGeneration"))
                .extracting(s -> s.sign)
                .contains(Counters.Sign.OVERFLOW);
        }

        @Test
        @DisplayName("Should emit INCREMENT for normal GC time increase")
        void shouldEmitIncrementForNormalGcTimeIncrease() {
            // Given: Previous GC time
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 100L, 5000L));
            awaitSignalPropagation();
            capturedCounterSigns.clear();

            // When: GC time increases by 100ms
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 101L, 5100L));
            awaitSignalPropagation();

            // Then: Should emit INCREMENT for time
            assertThat(capturedCounterSigns)
                .filteredOn(s -> s.entityName.contains("jvm.gc.time.broker-1.G1YoungGeneration"))
                .extracting(s -> s.sign)
                .contains(Counters.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should emit OVERFLOW for excessive GC time increase")
        void shouldEmitOverflowForExcessiveGcTime() {
            // Given: Previous GC time
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 100L, 5000L));
            awaitSignalPropagation();
            capturedCounterSigns.clear();

            // When: GC time increases by 6000ms (>5s threshold)
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 101L, 11000L));
            awaitSignalPropagation();

            // Then: Should emit OVERFLOW for time
            assertThat(capturedCounterSigns)
                .filteredOn(s -> s.entityName.contains("jvm.gc.time.broker-1.G1YoungGeneration"))
                .extracting(s -> s.sign)
                .contains(Counters.Sign.OVERFLOW);
        }

        @Test
        @DisplayName("Should emit INCREMENT on first GC observation")
        void shouldEmitIncrementOnFirstGcObservation() {
            // When: First GC observation
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 50L, 2500L));
            awaitSignalPropagation();

            // Then: Should emit INCREMENT for both count and time
            assertThat(capturedCounterSigns)
                .filteredOn(s -> s.entityName.contains("broker-1.G1YoungGeneration"))
                .extracting(s -> s.sign)
                .containsOnly(Counters.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should track multiple collectors independently")
        void shouldTrackMultipleCollectorsIndependently() {
            // When: Different collectors
            receptor.emitGc(createGcMetrics("broker-1", "G1YoungGeneration", 100L, 5000L));
            receptor.emitGc(createGcMetrics("broker-1", "G1OldGeneration", 10L, 1000L));
            awaitSignalPropagation();

            // Then: Should emit signals for both collectors
            assertThat(capturedCounterSigns)
                .extracting(s -> s.entityName)
                .anyMatch(name -> name.contains("G1YoungGeneration"))
                .anyMatch(name -> name.contains("G1OldGeneration"));
        }
    }

    // ========================================================================
    // Multi-Broker Tests
    // ========================================================================

    @Nested
    @DisplayName("Multi-Broker Support")
    class MultiBrokerTests {

        @Test
        @DisplayName("Should track heap metrics per broker independently")
        void shouldTrackHeapPerBrokerIndependently() {
            // When: Different brokers with different utilization
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.50, 0.50));
            receptor.emitMemory(createMemoryMetrics("broker-2", 0.70, 0.60));
            awaitSignalPropagation();
            capturedGaugeSigns.clear();

            // When: broker-1 grows, broker-2 shrinks
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.60, 0.50));
            receptor.emitMemory(createMemoryMetrics("broker-2", 0.65, 0.55));
            awaitSignalPropagation();

            // Then: broker-1 should INCREMENT, broker-2 should DECREMENT
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.heap.broker-1"))
                .extracting(s -> s.sign)
                .contains(Gauges.Sign.INCREMENT);

            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.heap.broker-2"))
                .extracting(s -> s.sign)
                .contains(Gauges.Sign.DECREMENT);
        }

        @Test
        @DisplayName("Should track GC metrics per broker and collector independently")
        void shouldTrackGcPerBrokerAndCollectorIndependently() {
            // When: Different brokers and collectors
            receptor.emitGc(createGcMetrics("broker-1", "G1Young", 100L, 5000L));
            receptor.emitGc(createGcMetrics("broker-2", "G1Young", 200L, 10000L));
            receptor.emitGc(createGcMetrics("broker-1", "G1Old", 10L, 1000L));
            awaitSignalPropagation();

            // Then: Should have signals for each entity
            List<String> entityNames = capturedCounterSigns.stream()
                .map(s -> s.entityName)
                .toList();

            assertThat(entityNames)
                .anyMatch(name -> name.contains("broker-1") && name.contains("G1Young"))
                .anyMatch(name -> name.contains("broker-2") && name.contains("G1Young"))
                .anyMatch(name -> name.contains("broker-1") && name.contains("G1Old"));
        }
    }

    // ========================================================================
    // Signal-Flow Compliance Tests
    // ========================================================================

    @Nested
    @DisplayName("Signal-Flow Compliance (Layer 1 Only)")
    class SignalFlowComplianceTests {

        @Test
        @DisplayName("Observer should ONLY emit raw signals, NO pattern detection")
        void shouldOnlyEmitRawSignals() {
            // Given: Multiple heap overflows (would trigger pattern in Layer 2)
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.95, 0.50));
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.96, 0.50));
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.97, 0.50));
            awaitSignalPropagation();

            // Then: Should emit only OVERFLOW signals (no DEGRADED, no confidence)
            // Layer 1 doesn't know about "3 overflows = DEGRADED" pattern
            assertThat(capturedGaugeSigns)
                .filteredOn(s -> s.entityName.contains("jvm.heap.broker-1"))
                .extracting(s -> s.sign)
                .containsOnly(Gauges.Sign.OVERFLOW);

            // Verify NO Monitors signals were emitted (that's Layer 2's job)
            // Observer only exposes gauges() and counters(), not monitors()
        }

        @Test
        @DisplayName("Observer should expose gauges() and counters() conduits")
        void shouldExposeGaugesAndCountersConduits() {
            // Then: Should have conduit accessors
            assertThat(receptor.gauges()).isNotNull();
            assertThat(receptor.counters()).isNotNull();

            // Verify conduits are from same circuit
            assertThat(receptor.gauges()).isInstanceOf(Conduit.class);
            assertThat(receptor.counters()).isInstanceOf(Conduit.class);
        }

        @Test
        @DisplayName("Observer should use Circuit for signal propagation")
        void shouldUseCircuitForSignalPropagation() {
            // When: Emit signal
            receptor.emitMemory(createMemoryMetrics("broker-1", 0.50, 0.50));

            // Then: Signals should propagate through circuit (await ensures completion)
            assertThatCode(() -> circuit.await()).doesNotThrowAnyException();

            // Verify signals were captured (proving circuit propagation worked)
            assertThat(capturedGaugeSigns).isNotEmpty();
        }
    }

    // ========================================================================
    // Resource Cleanup Tests
    // ========================================================================

    @Nested
    @DisplayName("Resource Cleanup")
    class ResourceCleanupTests {

        @Test
        @DisplayName("Should close without error")
        void shouldCloseWithoutError() {
            // When
            assertThatCode(() -> receptor.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should handle close when already closed")
        void shouldHandleDoubleClose() {
            // When
            receptor.close();

            // Then: Second close should not throw
            assertThatCode(() -> receptor.close()).doesNotThrowAnyException();
        }
    }

    // ========================================================================
    // Helper Classes
    // ========================================================================

    private record CapturedGaugeSignal(String entityName, Gauges.Sign sign) {}
    private record CapturedCounterSignal(String entityName, Counters.Sign sign) {}
}
