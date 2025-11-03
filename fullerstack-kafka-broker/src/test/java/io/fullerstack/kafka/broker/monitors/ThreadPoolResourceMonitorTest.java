package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import io.humainary.substrates.ext.serventis.ResourceSignal;
import io.humainary.substrates.ext.serventis.Resources.Resources;
import io.humainary.substrates.api.Substrates.Name;
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
    private Pipe<ResourceSignal> signalPipe;
    private ThreadPoolResourceMonitor monitor;

    private List<ResourceSignal> emittedSignals;

    @BeforeEach
    void setUp() {
        circuitName = cortex().name("kafka.broker.resources");
        signalPipe = createSignalCaptor();

        monitor = new ThreadPoolResourceMonitor(circuitName, signalPipe);

        emittedSignals = new ArrayList<>();
    }

    /**
     * Creates a mock Pipe that captures emitted signals.
     */
    @SuppressWarnings("unchecked")
    private Pipe<ResourceSignal> createSignalCaptor() {
        Pipe<ResourceSignal> pipe = mock(Pipe.class);

        doAnswer(invocation -> {
            ResourceSignal signal = invocation.getArgument(0);
            emittedSignals.add(signal);
            return null;
        }).when(pipe).emit(any(ResourceSignal.class));

        return pipe;
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
    private ResourceSignal getLastSignal() {
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
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
            assertThat(signal.resourceSignal().units()).isEqualTo(1);  // idleThreads
            assertThat(signal.payload().get("avgIdlePercent")).isEqualTo("0.40");
        }

        @Test
        @DisplayName("Should emit GRANT at exactly 30% idle")
        void testHealthy_ExactlyAtThreshold() {
            // Given: Exactly 30% idle (boundary)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.IO, 10, 3, 0.30);

            // When
            monitor.emit(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
            assertThat(signal.resourceSignal().units()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should include all required payload fields")
        void testHealthy_PayloadCompleteness() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.IO, 8, 4, 0.50);

            // When
            monitor.emit(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload())
                .containsKeys(
                    "brokerId",
                    "poolType",
                    "totalThreads",
                    "activeThreads",
                    "idleThreads",
                    "avgIdlePercent",
                    "utilizationPercent"
                );

            assertThat(signal.payload().get("brokerId")).isEqualTo("broker-1");
            assertThat(signal.payload().get("poolType")).isEqualTo("I/O Threads");
            assertThat(signal.payload().get("totalThreads")).isEqualTo("8");
            assertThat(signal.payload().get("idleThreads")).isEqualTo("4");
            assertThat(signal.payload().get("activeThreads")).isEqualTo("4");

            // No interpretation, no recommendation, no baseline fields
            assertThat(signal.payload()).doesNotContainKeys(
                "interpretation", "recommendation", "assessment", "expectedIdlePercent", "trend"
            );
        }
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
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
            assertThat(signal.resourceSignal().units()).isEqualTo(2);  // Still has capacity
        }

        @Test
        @DisplayName("Should emit GRANT at exactly 10% idle")
        void testDegraded_ExactlyAtLowerThreshold() {
            // Given: Exactly 10% idle (boundary - still GRANT)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.IO, 10, 1, 0.10);

            // When
            monitor.emit(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
            assertThat(signal.resourceSignal().units()).isEqualTo(1);
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
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
            assertThat(signal.resourceSignal().units()).isEqualTo(0);  // No capacity
        }

        @Test
        @DisplayName("Should emit DENY at 0% idle")
        void testExhausted_ZeroIdle() {
            // Given: 0% idle (fully saturated)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 0, 0.0);

            // When
            monitor.emit(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
            assertThat(signal.resourceSignal().units()).isEqualTo(0);
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
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
            assertThat(signal.payload().get("rejectionCount")).isEqualTo("5");
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
            ResourceSignal signal = getLastSignal();
            assertThat(signal.subject().name().toString()).isEqualTo("broker-1.network");
        }

        @Test
        @DisplayName("Should create unique subjects for different pool types")
        void testSubject_DifferentPoolTypes() {
            // Given
            ThreadPoolMetrics networkMetrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);
            ThreadPoolMetrics ioMetrics = createMetrics("broker-1", ThreadPoolType.IO, 8, 3, 0.40);

            // When
            monitor.emit(networkMetrics);
            ResourceSignal networkSignal = getLastSignal();

            monitor.emit(ioMetrics);
            ResourceSignal ioSignal = getLastSignal();

            // Then: Different entity names
            assertThat(networkSignal.subject().name().toString()).isEqualTo("broker-1.network");
            assertThat(ioSignal.subject().name().toString()).isEqualTo("broker-1.io");
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
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload().get("queueSize")).isEqualTo("50");
        }

        @Test
        @DisplayName("Should not include queue size if zero")
        void testPayload_QueueSizeOmitted() {
            // Given: Metrics with zero queue size
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            // When
            monitor.emit(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload()).doesNotContainKey("queueSize");
        }

        @Test
        @DisplayName("Should not include Layer 4 fields")
        void testPayload_NoLayer4Fields() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.22);

            // When
            monitor.emit(metrics);

            // Then: NO interpretation, recommendation, baseline, or assessment
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload()).doesNotContainKeys(
                "interpretation",
                "recommendation",
                "assessment",
                "expectedIdlePercent",
                "trend",
                "severity",
                "note"
            );
        }
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
            Pipe<ResourceSignal> throwingPipe = mock(Pipe.class);
            doThrow(new RuntimeException("Pipe error"))
                .when(throwingPipe).emit(any(ResourceSignal.class));

            ThreadPoolResourceMonitor monitorWithThrowingPipe =
                new ThreadPoolResourceMonitor(circuitName, throwingPipe);

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
                new ThreadPoolResourceMonitor(null, signalPipe)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("circuitName cannot be null");
        }

        @Test
        @DisplayName("Should reject null signalPipe")
        void testConstructor_NullSignalPipe() {
            assertThatThrownBy(() ->
                new ThreadPoolResourceMonitor(circuitName, null)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("signalPipe cannot be null");
        }
    }

    // ========================================================================
    // Semantic Helpers Tests
    // ========================================================================

    @Nested
    @DisplayName("ResourceSignal Semantic Helpers")
    class SemanticTests {

        @Test
        @DisplayName("DENY should require attention")
        void testSemantic_DenyRequiresAttention() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 0, 0.05);

            // When
            monitor.emit(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.requiresAttention()).isTrue();
        }

        @Test
        @DisplayName("GRANT should not require attention")
        void testSemantic_GrantNoAttention() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 2, 0.50);

            // When
            monitor.emit(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.requiresAttention()).isFalse();
        }

        @Test
        @DisplayName("Should generate human-readable interpretation")
        void testSemantic_InterpretMethod() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 0, 0.05);

            // When
            monitor.emit(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            String interpretation = signal.interpret();
            assertThat(interpretation).isNotNull();
            // ResourceSignal.interpret() provides semantic interpretation based on sign
        }
    }
}
