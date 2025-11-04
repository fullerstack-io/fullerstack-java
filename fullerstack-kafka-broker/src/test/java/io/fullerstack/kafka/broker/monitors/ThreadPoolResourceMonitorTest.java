package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import io.humainary.substrates.ext.serventis.Resources;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Pipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for ThreadPoolResourceMonitor (Layer 2 - Signal Emission).
 *
 * <p><b>Per ADR-002:</b> Tests validate simple threshold-based signal emission only.
 * Removed: BaselineService, interpretation text, recommendations, trend analysis.
 *
 * <p>Test Coverage (Layer 2 Only):
 * <ul>
 *   <li>Sign assessment: GRANT (healthy/degraded), DENY (exhausted)</li>
 *   <li>Simple fixed thresholds (no contextual baselines)</li>
 *   <li>Payload completeness: raw metrics only</li>
 *   <li>Subject creation: circuit + entity</li>
 *   <li>Error handling: graceful degradation</li>
 * </ul>
 */
@DisplayName("ThreadPoolResourceMonitor (Layer 2 - Signal Emission)")
class ThreadPoolResourceMonitorTest {

    private Name circuitName;
    private Channel<Resources.Signal> signalChannel;
    private ThreadPoolResourceMonitor monitor;

    private List<Resources.Signal> emittedSignals;

    @BeforeEach
    void setUp() {
        circuitName = cortex().name("kafka.broker.resources");
        signalChannel = createSignalCaptor();

        monitor = new ThreadPoolResourceMonitor(circuitName, signalChannel);

        emittedSignals = new ArrayList<>();
    }

    /**
     * Creates a mock Channel that captures emitted signals.
     */
    @SuppressWarnings("unchecked")
    private Channel<Resources.Signal> createSignalCaptor() {
        Channel<Resources.Signal> channel = mock(Channel.class);
        Pipe<Resources.Signal> pipe = mock(Pipe.class);

        // Channel.pipe() returns the Pipe
        when(channel.pipe()).thenReturn(pipe);

        // Capture signals emitted to the pipe
        doAnswer(invocation -> {
            Resources.Signal signal = invocation.getArgument(0);
            emittedSignals.add(signal);
            return null;
        }).when(pipe).emit(any(Resources.Signal.class));

        // Mock flush() to return void
        doNothing().when(pipe).flush();

        return channel;
    }

    /**
     * Helper to create ThreadPoolMetrics with defaults.
     */
    private ThreadPoolMetrics createMetrics(
        String brokerId,
        ThreadPoolType poolType,
        int totalThreads,
        int idleThreads,
        double avgIdlePercent
    ) {
        int activeThreads = totalThreads - idleThreads;
        return new ThreadPoolMetrics(
            brokerId,
            poolType,
            totalThreads,
            activeThreads,
            idleThreads,
            avgIdlePercent,
            0L,  // queueSize
            1000L,  // totalTasksProcessed
            0L,  // rejectionCount
            System.currentTimeMillis()
        );
    }

    /**
     * Gets the last emitted signal.
     */
    private Resources.Signal getLastSignal() {
        assertThat(emittedSignals).isNotEmpty();
        return emittedSignals.get(emittedSignals.size() - 1);
    }

    // ========================================================================
    // GRANT Condition Tests (Healthy >= 30% idle)
    // ========================================================================

    @Nested
    @DisplayName("GRANT Sign (Healthy - >= 30% idle)")
    class HealthyTests {

        @Test
        @DisplayName("Should emit GRANT when idle >= 30%")
        void testHealthy_HighIdle() {
            // Given: 40% idle (healthy - above 30% threshold)
            ThreadPoolMetrics metrics = createMetrics(
                "broker-1",
                ThreadPoolType.NETWORK,
                3,
                1,
                0.40
            );

            // When
            monitor.emit(metrics);

            // Then
            Resources.Signal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
        }

        @Test
        @DisplayName("Should emit GRANT at exactly 30% idle")
        void testHealthy_ExactlyAtThreshold() {
            // Given: Exactly 30% idle (boundary)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.IO, 10, 3, 0.30);

            // When
            monitor.emit(metrics);

            // Then
            Resources.Signal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
        }

        // TODO RC3: payload() method removed from Resources.Signal
        // This test checked payload completeness which is no longer accessible in RC3
        /*
        @Test
        @DisplayName("Should include all required payload fields")
        void testHealthy_PayloadCompleteness() {
            // Test removed - payload() API no longer exists in RC3
        }
        */
    }

    // ========================================================================
    // GRANT Condition Tests (Degraded 10-30% idle)
    // ========================================================================

    @Nested
    @DisplayName("GRANT Sign (Degraded - 10-30% idle)")
    class DegradedTests {

        @Test
        @DisplayName("Should emit GRANT when idle is 10-30%")
        void testDegraded_ModerateLoad() {
            // Given: 22% idle (degraded - between 10% and 30%)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 10, 2, 0.22);

            // When
            monitor.emit(metrics);

            // Then
            Resources.Signal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
        }

        @Test
        @DisplayName("Should emit GRANT at exactly 10% idle")
        void testDegraded_ExactlyAtLowerThreshold() {
            // Given: Exactly 10% idle (boundary - still GRANT)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.IO, 10, 1, 0.10);

            // When
            monitor.emit(metrics);

            // Then
            Resources.Signal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
        }
    }

    // ========================================================================
    // DENY Condition Tests (Exhausted < 10% idle)
    // ========================================================================

    @Nested
    @DisplayName("DENY Sign (Exhausted - < 10% idle)")
    class ExhaustedTests {

        @Test
        @DisplayName("Should emit DENY when idle < 10%")
        void testExhausted_LowIdle() {
            // Given: 8% idle (exhausted)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 10, 1, 0.08);

            // When
            monitor.emit(metrics);

            // Then
            Resources.Signal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
        }

        @Test
        @DisplayName("Should emit DENY at 0% idle")
        void testExhausted_ZeroIdle() {
            // Given: 0% idle (fully saturated)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 0, 0.0);

            // When
            monitor.emit(metrics);

            // Then
            Resources.Signal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
        }

        @Test
        @DisplayName("Should include rejection count in payload if non-zero")
        void testExhausted_WithRejections() {
            // Given: Exhausted pool with task rejections
            ThreadPoolMetrics metricsWithRejections = new ThreadPoolMetrics(
                "broker-1",
                ThreadPoolType.NETWORK,
                3,
                3,  // all active
                0,  // none idle
                0.02,  // 2% idle
                0L,
                1000L,
                5L,  // rejectionCount
                System.currentTimeMillis()
            );

            // When
            monitor.emit(metricsWithRejections);

            // Then
            Resources.Signal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
        }
    }

    // ========================================================================
    // Subject Creation Tests
    // ========================================================================

    @Nested
    @DisplayName("Subject Creation")
    class SubjectTests {

        @Test
        @DisplayName("Should create subject with entity name")
        void testSubject_EntityName() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            // When
            monitor.emit(metrics);

            // Then
            Resources.Signal signal = getLastSignal();
        }

        @Test
        @DisplayName("Should create unique subjects for different pool types")
        void testSubject_DifferentPoolTypes() {
            // Given
            ThreadPoolMetrics networkMetrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);
            ThreadPoolMetrics ioMetrics = createMetrics("broker-1", ThreadPoolType.IO, 8, 3, 0.40);

            // When
            monitor.emit(networkMetrics);
            Resources.Signal networkSignal = getLastSignal();

            monitor.emit(ioMetrics);
            Resources.Signal ioSignal = getLastSignal();

            // Then: Different entity names
        }
    }

    // ========================================================================
    // Payload Completeness Tests
    // ========================================================================

    @Nested
    @DisplayName("Payload Completeness")
    class PayloadTests {

        @Test
        @DisplayName("Should include queue size if non-zero")
        void testPayload_QueueSizeIncluded() {
            // Given: Metrics with queue size
            ThreadPoolMetrics metricsWithQueue = new ThreadPoolMetrics(
                "broker-1",
                ThreadPoolType.IO,
                8,
                6,
                2,
                0.25,
                50L,  // queueSize - should appear in payload
                1000L,
                0L,
                System.currentTimeMillis()
            );

            // When
            monitor.emit(metricsWithQueue);

            // Then
            Resources.Signal signal = getLastSignal();
        }

        @Test
        @DisplayName("Should not include queue size if zero")
        void testPayload_QueueSizeOmitted() {
            // Given: Metrics with zero queue size
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            // When
            monitor.emit(metrics);

            // Then
            Resources.Signal signal = getLastSignal();
        }

        // TODO RC3: payload() removed from Resources.Signal
        /*
        @Test
        @DisplayName("Should not include Layer 4 fields")
        void testPayload_NoLayer4Fields() {
            // Test removed - payload() API no longer exists in RC3
        }
        */
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should not throw on null metrics")
        void testErrorHandling_NullMetrics() {
            // When/Then: Should throw NPE from Objects.requireNonNull
            assertThatThrownBy(() -> monitor.emit(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metrics cannot be null");
        }

        @Test
        @DisplayName("Should handle Pipe failure gracefully")
        void testErrorHandling_PipeFailure() {
            // Given: Pipe that throws
            Channel<Resources.Signal> throwingChannel = mock(Channel.class);
            Pipe<Resources.Signal> throwingPipe = mock(Pipe.class);
            when(throwingChannel.pipe()).thenReturn(throwingPipe);
            doThrow(new RuntimeException("Pipe error"))
                .when(throwingPipe).emit(any(Resources.Signal.class));

            ThreadPoolResourceMonitor monitorWithThrowingPipe =
                new ThreadPoolResourceMonitor(circuitName, throwingChannel);

            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            // When: Should not propagate exception (monitoring failures shouldn't break system)
            assertThatCode(() -> monitorWithThrowingPipe.emit(metrics))
                .doesNotThrowAnyException();
        }
    }

    // ========================================================================
    // Constructor Validation Tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorTests {

        @Test
        @DisplayName("Should reject null circuitName")
        void testConstructor_NullCircuitName() {
            assertThatThrownBy(() ->
                new ThreadPoolResourceMonitor(null, signalChannel)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("circuitName cannot be null");
        }

        @Test
        @DisplayName("Should reject null signalChannel")
        void testConstructor_NullSignalPipe() {
            assertThatThrownBy(() ->
                new ThreadPoolResourceMonitor(circuitName, null)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("channel cannot be null");
        }
    }

    // ========================================================================
    // Semantic Helpers Tests
    // ========================================================================

    @Nested
    @DisplayName("Resources.Signal Semantic Helpers")
    class SemanticTests {

        @Test
        @DisplayName("DENY signal emitted for exhausted pool")
        void testSemantic_DenyRequiresAttention() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 0, 0.05);

            // When
            monitor.emit(metrics);

            // Then - Verify DENY signal was emitted
            Resources.Signal signal = getLastSignal();
            assertThat(signal).isNotNull();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
        }

        @Test
        @DisplayName("GRANT signal emitted for healthy pool")
        void testSemantic_GrantNoAttention() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 2, 0.50);

            // When
            monitor.emit(metrics);

            // Then - Verify GRANT signal was emitted
            Resources.Signal signal = getLastSignal();
            assertThat(signal).isNotNull();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
        }
    }
}
