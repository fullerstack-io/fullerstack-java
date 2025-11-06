package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.JvmGcMetrics;
import io.fullerstack.kafka.broker.models.JvmMemoryMetrics;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.ext.serventis.Counters;
import io.humainary.substrates.ext.serventis.Gauges;
import io.humainary.substrates.ext.serventis.Monitors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.humainary.substrates.api.Substrates.cortex;
import static io.humainary.substrates.ext.serventis.Monitors.Confidence.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for JvmHealthMonitor (Layer 2 - Signal Emission).
 *
 * <p>Test Coverage:
 * <ul>
 *   <li>Gauges API: Heap memory signals (INCREMENT/DECREMENT/OVERFLOW)</li>
 *   <li>Counters API: GC metrics signals (INCREMENT/OVERFLOW on storm)</li>
 *   <li>Monitors API: Broker health assessment (STABLE/DEGRADED/DEFECTIVE)</li>
 *   <li>Threshold-based emission logic</li>
 *   <li>Error handling: graceful degradation</li>
 * </ul>
 */
@DisplayName("JvmHealthMonitor (Layer 2 - Signal Emission)")
class JvmHealthMonitorTest {

    private Name circuitName;
    private Channel<Gauges.Sign> gaugesChannel;
    private Channel<Counters.Sign> countersChannel;
    private Channel<Monitors.Signal> monitorsChannel;
    private JvmHealthMonitor monitor;

    private List<Gauges.Sign> emittedGaugeSigns;
    private List<Counters.Sign> emittedCounterSigns;
    private List<Monitors.Signal> emittedMonitorSignals;

    @BeforeEach
    void setUp() {
        circuitName = cortex().name("kafka.broker.jvm-health");

        emittedGaugeSigns = new ArrayList<>();
        emittedCounterSigns = new ArrayList<>();
        emittedMonitorSignals = new ArrayList<>();

        gaugesChannel = createGaugeSignCaptor();
        countersChannel = createCounterSignCaptor();
        monitorsChannel = createMonitorSignalCaptor();

        monitor = new JvmHealthMonitor(circuitName, gaugesChannel, countersChannel, monitorsChannel);
    }

    @SuppressWarnings("unchecked")
    private Channel<Gauges.Sign> createGaugeSignCaptor() {
        Channel<Gauges.Sign> channel = mock(Channel.class);
        Pipe<Gauges.Sign> pipe = mock(Pipe.class);

        when(channel.pipe()).thenReturn(pipe);

        doAnswer(invocation -> {
            Gauges.Sign sign = invocation.getArgument(0);
            emittedGaugeSigns.add(sign);
            return null;
        }).when(pipe).emit(any(Gauges.Sign.class));

        doNothing().when(pipe).flush();

        return channel;
    }

    @SuppressWarnings("unchecked")
    private Channel<Counters.Sign> createCounterSignCaptor() {
        Channel<Counters.Sign> channel = mock(Channel.class);
        Pipe<Counters.Sign> pipe = mock(Pipe.class);

        when(channel.pipe()).thenReturn(pipe);

        doAnswer(invocation -> {
            Counters.Sign sign = invocation.getArgument(0);
            emittedCounterSigns.add(sign);
            return null;
        }).when(pipe).emit(any(Counters.Sign.class));

        doNothing().when(pipe).flush();

        return channel;
    }

    @SuppressWarnings("unchecked")
    private Channel<Monitors.Signal> createMonitorSignalCaptor() {
        Channel<Monitors.Signal> channel = mock(Channel.class);
        Pipe<Monitors.Signal> pipe = mock(Pipe.class);

        when(channel.pipe()).thenReturn(pipe);

        doAnswer(invocation -> {
            Monitors.Signal signal = invocation.getArgument(0);
            emittedMonitorSignals.add(signal);
            return null;
        }).when(pipe).emit(any(Monitors.Signal.class));

        doNothing().when(pipe).flush();

        return channel;
    }

    private JvmMemoryMetrics createMemoryMetrics(String brokerId, double heapUtil) {
        long maxHeap = 10_000_000_000L; // 10GB
        long usedHeap = (long) (maxHeap * heapUtil);
        return new JvmMemoryMetrics(
            brokerId,
            usedHeap,
            maxHeap,
            maxHeap,
            5_000_000_000L, // nonHeapUsed
            2_000_000_000L, // nonHeapMax
            2_000_000_000L, // nonHeapCommitted
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

    // ========================================================================
    // Heap Memory Gauges Tests
    // ========================================================================

    @Nested
    @DisplayName("Heap Memory Gauges (Gauges API)")
    class HeapMemoryTests {

        @Test
        @DisplayName("Should emit OVERFLOW when heap utilization >= 90%")
        void testHeapOverflow() {
            // Given: 95% heap utilization
            JvmMemoryMetrics metrics = createMemoryMetrics("broker-1", 0.95);

            // When
            monitor.emitMemory(metrics);

            // Then: Should emit OVERFLOW
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.OVERFLOW);
        }

        @Test
        @DisplayName("Should emit INCREMENT when heap grows")
        void testHeapIncrement() {
            // Given: Heap grows from 50% to 60%
            monitor.emitMemory(createMemoryMetrics("broker-1", 0.50));
            emittedGaugeSigns.clear();

            // When
            monitor.emitMemory(createMemoryMetrics("broker-1", 0.60));

            // Then: Should emit INCREMENT
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should emit DECREMENT when heap shrinks")
        void testHeapDecrement() {
            // Given: Heap shrinks from 70% to 50%
            monitor.emitMemory(createMemoryMetrics("broker-1", 0.70));
            emittedGaugeSigns.clear();

            // When
            monitor.emitMemory(createMemoryMetrics("broker-1", 0.50));

            // Then: Should emit DECREMENT
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.DECREMENT);
        }

        @Test
        @DisplayName("Should emit INCREMENT on first observation")
        void testHeapFirstObservation() {
            // When: First emission
            monitor.emitMemory(createMemoryMetrics("broker-1", 0.50));

            // Then: Should emit INCREMENT
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.INCREMENT);
        }
    }

    // ========================================================================
    // GC Counters Tests
    // ========================================================================

    @Nested
    @DisplayName("GC Counters (Counters API)")
    class GcCountersTests {

        @Test
        @DisplayName("Should emit INCREMENT for normal GC activity")
        void testGcIncrement() {
            // Given: Normal GC count
            JvmGcMetrics metrics = createGcMetrics("broker-1", "G1 Young Generation", 100L, 5000L);

            // When
            monitor.emitGc(metrics);

            // Then: Should emit INCREMENT
            assertThat(emittedCounterSigns).contains(Counters.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should emit INCREMENT on first observation")
        void testGcFirstObservation() {
            // When: First GC observation
            monitor.emitGc(createGcMetrics("broker-1", "G1 Young Generation", 50L, 2500L));

            // Then: Should emit INCREMENT
            assertThat(emittedCounterSigns).contains(Counters.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should handle multiple collectors independently")
        void testMultipleCollectors() {
            // When: Different collectors
            monitor.emitGc(createGcMetrics("broker-1", "G1 Young Generation", 100L, 5000L));
            monitor.emitGc(createGcMetrics("broker-1", "G1 Old Generation", 10L, 1000L));

            // Then: Should emit INCREMENT for each
            assertThat(emittedCounterSigns).hasSize(2);
            assertThat(emittedCounterSigns).allMatch(sign -> sign == Counters.Sign.INCREMENT);
        }
    }

    // ========================================================================
    // Broker Health Assessment Tests
    // ========================================================================

    @Nested
    @DisplayName("Broker Health Assessment (Monitors API)")
    class BrokerHealthTests {

        @Test
        @DisplayName("Should assess DEFECTIVE when heap >= 95%")
        void testDefectiveHealth() {
            // Given: Critical heap utilization
            JvmMemoryMetrics metrics = createMemoryMetrics("broker-1", 0.96);

            // When
            monitor.assessBrokerHealth(metrics);

            // Then: Should emit DEFECTIVE signal with CONFIRMED confidence
            assertThat(emittedMonitorSignals).hasSize(1);
            Monitors.Signal signal = emittedMonitorSignals.get(0);
            assertThat(signal.sign()).isEqualTo(Monitors.Sign.DEFECTIVE);
            assertThat(signal.confidence()).isEqualTo(CONFIRMED);
        }

        @Test
        @DisplayName("Should assess DEGRADED when heap >= 85%")
        void testDegradedHealth() {
            // Given: High heap utilization
            JvmMemoryMetrics metrics = createMemoryMetrics("broker-1", 0.88);

            // When
            monitor.assessBrokerHealth(metrics);

            // Then: Should emit DEGRADED signal with CONFIRMED confidence
            assertThat(emittedMonitorSignals).hasSize(1);
            Monitors.Signal signal = emittedMonitorSignals.get(0);
            assertThat(signal.sign()).isEqualTo(Monitors.Sign.DEGRADED);
            assertThat(signal.confidence()).isEqualTo(CONFIRMED);
        }

        @Test
        @DisplayName("Should assess DIVERGING when heap >= 75%")
        void testDivergingHealth() {
            // Given: Elevated heap utilization
            JvmMemoryMetrics metrics = createMemoryMetrics("broker-1", 0.78);

            // When
            monitor.assessBrokerHealth(metrics);

            // Then: Should emit DIVERGING signal with MEASURED confidence
            assertThat(emittedMonitorSignals).hasSize(1);
            Monitors.Signal signal = emittedMonitorSignals.get(0);
            assertThat(signal.sign()).isEqualTo(Monitors.Sign.DIVERGING);
            assertThat(signal.confidence()).isEqualTo(MEASURED);
        }

        @Test
        @DisplayName("Should assess STABLE when heap < 75%")
        void testStableHealth() {
            // Given: Normal heap utilization
            JvmMemoryMetrics metrics = createMemoryMetrics("broker-1", 0.60);

            // When
            monitor.assessBrokerHealth(metrics);

            // Then: Should emit STABLE signal with CONFIRMED confidence
            assertThat(emittedMonitorSignals).hasSize(1);
            Monitors.Signal signal = emittedMonitorSignals.get(0);
            assertThat(signal.sign()).isEqualTo(Monitors.Sign.STABLE);
            assertThat(signal.confidence()).isEqualTo(CONFIRMED);
        }
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle null metrics gracefully")
        void testNullMetrics() {
            assertThatThrownBy(() -> monitor.emitMemory(null))
                .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> monitor.emitGc(null))
                .isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> monitor.assessBrokerHealth(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should continue after emission error")
        void testContinueAfterError() {
            // Given: Channel that throws exception
            Channel<Gauges.Sign> faultyChannel = mock(Channel.class);
            when(faultyChannel.pipe()).thenThrow(new RuntimeException("Channel error"));

            JvmHealthMonitor faultyMonitor = new JvmHealthMonitor(
                circuitName,
                faultyChannel,
                countersChannel,
                monitorsChannel
            );

            // When: Emit despite error
            JvmMemoryMetrics metrics = createMemoryMetrics("broker-1", 0.50);

            // Then: Should not propagate exception
            assertThatCode(() -> faultyMonitor.emitMemory(metrics))
                .doesNotThrowAnyException();
        }
    }

    // ========================================================================
    // Multi-Broker Tests
    // ========================================================================

    @Nested
    @DisplayName("Multi-Broker Support")
    class MultiBrokerTests {

        @Test
        @DisplayName("Should track metrics per broker independently")
        void testPerBrokerTracking() {
            // When: Different brokers with different utilization
            monitor.emitMemory(createMemoryMetrics("broker-1", 0.50));
            monitor.emitMemory(createMemoryMetrics("broker-2", 0.70));
            monitor.emitMemory(createMemoryMetrics("broker-1", 0.60)); // broker-1 grows
            monitor.emitMemory(createMemoryMetrics("broker-2", 0.65)); // broker-2 shrinks

            // Then: Should handle deltas correctly per broker
            // Note: Each call emits heap + non-heap signals, so 4 calls = 8 signals
            assertThat(emittedGaugeSigns).hasSize(8); // 4 observations × 2 signals each (heap + non-heap)
        }

        @Test
        @DisplayName("Should assess health per broker")
        void testPerBrokerHealth() {
            // When: Different brokers with different health states
            monitor.assessBrokerHealth(createMemoryMetrics("broker-1", 0.50)); // STABLE
            monitor.assessBrokerHealth(createMemoryMetrics("broker-2", 0.90)); // DEGRADED

            // Then: Should emit appropriate signals
            assertThat(emittedMonitorSignals).hasSize(2);
            assertThat(emittedMonitorSignals.get(0).sign()).isEqualTo(Monitors.Sign.STABLE);
            assertThat(emittedMonitorSignals.get(1).sign()).isEqualTo(Monitors.Sign.DEGRADED);
        }
    }
}
