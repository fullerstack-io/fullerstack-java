package io.fullerstack.kafka.broker.observers;

import io.fullerstack.kafka.broker.models.SystemMetrics;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for SystemMetricsObserver (Layer 1 - Raw Signal Emission).
 *
 * <p>Test Coverage:
 * <ul>
 *   <li>Gauges API: CPU metrics signals (INCREMENT/DECREMENT/OVERFLOW)</li>
 *   <li>Gauges API: File descriptor signals (OVERFLOW on high FD usage)</li>
 *   <li>Threshold-based emission logic</li>
 *   <li>Error handling: graceful degradation</li>
 * </ul>
 */
@DisplayName("SystemMetricsObserver (Layer 1 - Raw Signal Emission)")
class SystemMetricsObserverTest {

    private Name circuitName;
    private Channel<Gauges.Sign> gaugesChannel;
    private SystemMetricsObserver observer;

    private List<Gauges.Sign> emittedGaugeSigns;

    @BeforeEach
    void setUp() {
        circuitName = cortex().name("kafka.broker.system-metrics");

        emittedGaugeSigns = new ArrayList<>();

        gaugesChannel = createGaugeSignCaptor();

        observer = new SystemMetricsObserver(circuitName, gaugesChannel);
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

    private SystemMetrics createMetrics(
        String brokerId,
        double processCpu,
        double systemCpu,
        long openFDs,
        long maxFDs
    ) {
        return new SystemMetrics(
            brokerId,
            processCpu,
            systemCpu,
            openFDs,
            maxFDs,
            System.currentTimeMillis()
        );
    }

    // ========================================================================
    // Process CPU Tests
    // ========================================================================

    @Nested
    @DisplayName("Process CPU Gauges (Gauges API)")
    class ProcessCpuTests {

        @Test
        @DisplayName("Should emit OVERFLOW when process CPU >= 90%")
        void testProcessCpuOverflow() {
            // Given: High CPU utilization
            SystemMetrics metrics = createMetrics("broker-1", 0.95, 0.50, 1000, 10000);

            // When
            observer.emit(metrics);

            // Then: Should emit OVERFLOW
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.OVERFLOW);
        }

        @Test
        @DisplayName("Should emit INCREMENT when process CPU grows")
        void testProcessCpuIncrement() {
            // Given: CPU grows from 50% to 60%
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 1000, 10000));
            emittedGaugeSigns.clear();

            // When
            observer.emit(createMetrics("broker-1", 0.60, 0.40, 1000, 10000));

            // Then: Should emit INCREMENT for process CPU
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should emit DECREMENT when process CPU drops")
        void testProcessCpuDecrement() {
            // Given: CPU drops from 70% to 50%
            observer.emit(createMetrics("broker-1", 0.70, 0.40, 1000, 10000));
            emittedGaugeSigns.clear();

            // When
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 1000, 10000));

            // Then: Should emit DECREMENT
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.DECREMENT);
        }

        @Test
        @DisplayName("Should emit INCREMENT on first observation")
        void testProcessCpuFirstObservation() {
            // When: First emission
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 1000, 10000));

            // Then: Should emit INCREMENT
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.INCREMENT);
        }
    }

    // ========================================================================
    // System CPU Tests
    // ========================================================================

    @Nested
    @DisplayName("System CPU Gauges (Gauges API)")
    class SystemCpuTests {

        @Test
        @DisplayName("Should emit OVERFLOW when system CPU >= 90%")
        void testSystemCpuOverflow() {
            // Given: High system CPU
            SystemMetrics metrics = createMetrics("broker-1", 0.50, 0.92, 1000, 10000);

            // When
            observer.emit(metrics);

            // Then: Should emit OVERFLOW
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.OVERFLOW);
        }

        @Test
        @DisplayName("Should emit INCREMENT when system CPU grows")
        void testSystemCpuIncrement() {
            // Given: System CPU grows
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 1000, 10000));
            emittedGaugeSigns.clear();

            // When
            observer.emit(createMetrics("broker-1", 0.50, 0.50, 1000, 10000));

            // Then: Should emit INCREMENT
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should emit DECREMENT when system CPU drops")
        void testSystemCpuDecrement() {
            // Given: System CPU drops
            observer.emit(createMetrics("broker-1", 0.50, 0.60, 1000, 10000));
            emittedGaugeSigns.clear();

            // When
            observer.emit(createMetrics("broker-1", 0.50, 0.50, 1000, 10000));

            // Then: Should emit DECREMENT
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.DECREMENT);
        }
    }

    // ========================================================================
    // File Descriptor Tests
    // ========================================================================

    @Nested
    @DisplayName("File Descriptor Gauges (Gauges API)")
    class FileDescriptorTests {

        @Test
        @DisplayName("Should emit OVERFLOW when FD utilization >= 95%")
        void testFdOverflow() {
            // Given: 96% FD utilization
            SystemMetrics metrics = createMetrics("broker-1", 0.50, 0.40, 9600, 10000);

            // When
            observer.emit(metrics);

            // Then: Should emit OVERFLOW
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.OVERFLOW);
        }

        @Test
        @DisplayName("Should emit INCREMENT when FD usage grows")
        void testFdIncrement() {
            // Given: FD usage grows
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 5000, 10000));
            emittedGaugeSigns.clear();

            // When
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 6000, 10000));

            // Then: Should emit INCREMENT
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.INCREMENT);
        }

        @Test
        @DisplayName("Should emit DECREMENT when FD usage drops")
        void testFdDecrement() {
            // Given: FD usage drops
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 7000, 10000));
            emittedGaugeSigns.clear();

            // When
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 6000, 10000));

            // Then: Should emit DECREMENT
            assertThat(emittedGaugeSigns).contains(Gauges.Sign.DECREMENT);
        }
    }

    // ========================================================================
    // Combined Metrics Tests
    // ========================================================================

    @Nested
    @DisplayName("Combined Metrics")
    class CombinedMetricsTests {

        @Test
        @DisplayName("Should emit multiple signals for different metrics")
        void testMultipleSignals() {
            // When: First emission
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 5000, 10000));

            // Then: Should emit signals for all 3 metrics (process CPU, system CPU, FDs)
            assertThat(emittedGaugeSigns).hasSize(3);
        }

        @Test
        @DisplayName("Should emit OVERFLOW for multiple metrics simultaneously")
        void testMultipleOverflows() {
            // When: High utilization across all metrics
            observer.emit(createMetrics("broker-1", 0.95, 0.92, 9600, 10000));

            // Then: Should emit OVERFLOW for all 3 metrics
            assertThat(emittedGaugeSigns).hasSize(3);
            assertThat(emittedGaugeSigns).allMatch(sign -> sign == Gauges.Sign.OVERFLOW);
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
            assertThatThrownBy(() -> observer.emit(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Should continue after emission error")
        void testContinueAfterError() {
            // Given: Channel that throws exception
            Channel<Gauges.Sign> faultyChannel = mock(Channel.class);
            when(faultyChannel.pipe()).thenThrow(new RuntimeException("Channel error"));

            SystemMetricsObserver faultyObserver = new SystemMetricsObserver(
                circuitName,
                faultyChannel
            );

            // When: Emit despite error
            SystemMetrics metrics = createMetrics("broker-1", 0.50, 0.40, 5000, 10000);

            // Then: Should not propagate exception
            assertThatCode(() -> faultyObserver.emit(metrics))
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
            observer.emit(createMetrics("broker-1", 0.50, 0.40, 5000, 10000));
            observer.emit(createMetrics("broker-2", 0.70, 0.60, 7000, 10000));
            observer.emit(createMetrics("broker-1", 0.60, 0.45, 5500, 10000)); // broker-1 grows
            observer.emit(createMetrics("broker-2", 0.65, 0.55, 6500, 10000)); // broker-2 shrinks

            // Then: Should handle deltas correctly per broker
            // First 2 emissions: 3 signals each (6 total)
            // Next 2 emissions: 3 signals each (6 total)
            // Total: 12 signals
            assertThat(emittedGaugeSigns).hasSize(12);
        }
    }
}
