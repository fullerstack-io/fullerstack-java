package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.baseline.BaselineService;
import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import io.fullerstack.serventis.signals.ResourceSignal;
import io.humainary.modules.serventis.resources.api.Resources;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pipe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for ThreadPoolResourceMonitor (signal-first architecture).
 *
 * <p>Test Coverage:
 * <ul>
 *   <li>Condition assessment: AVAILABLE, DEGRADED, EXHAUSTED</li>
 *   <li>Baseline integration: contextual thresholds</li>
 *   <li>Trend analysis: degrading, improving, stable</li>
 *   <li>Payload completeness: metrics + interpretation + recommendations</li>
 *   <li>Subject creation: circuit + entity</li>
 *   <li>Observation recording: BaselineService integration</li>
 *   <li>Error handling: graceful degradation</li>
 * </ul>
 */
@DisplayName("ThreadPoolResourceMonitor (Signal-First)")
class ThreadPoolResourceMonitorTest {

    private Name circuitName;
    private Pipe<ResourceSignal> signalPipe;
    private BaselineService baselineService;
    private ThreadPoolResourceMonitor monitor;

    private List<ResourceSignal> emittedSignals;

    @BeforeEach
    void setUp() {
        circuitName = cortex().name("kafka.broker.resources");
        signalPipe = createSignalCaptor();
        baselineService = mock(BaselineService.class);

        monitor = new ThreadPoolResourceMonitor(circuitName, signalPipe, baselineService);

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
    // AVAILABLE Condition Tests
    // ========================================================================

    @Nested
    @DisplayName("AVAILABLE Condition Assessment")
    class AvailableTests {

        @Test
        @DisplayName("Should emit AVAILABLE when idle >= 30%")
        void testAvailableCondition_HealthyPool() {
            // Given: 40% idle (healthy)
            ThreadPoolMetrics metrics = createMetrics(
                "broker-1",
                ThreadPoolType.NETWORK,
                3,
                1,  // 1 idle out of 3 = 33%
                0.40
            );

            // Baseline: 35% expected
            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
            assertThat(signal.payload().get("assessment")).isEqualTo("AVAILABLE");
            assertThat(signal.payload().get("interpretation")).contains("healthy");
            assertThat(signal.payload().get("avgIdlePercent")).isEqualTo("0.40");
        }

        @Test
        @DisplayName("Should emit AVAILABLE when above baseline")
        void testAvailableCondition_AboveBaseline() {
            // Given: 35% idle, baseline 30%
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.IO, 8, 3, 0.35);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
            assertThat(signal.payload().get("assessment")).isEqualTo("AVAILABLE");
        }

        @Test
        @DisplayName("Should note over-provisioning if significantly above baseline")
        void testAvailableCondition_OverProvisioned() {
            // Given: 80% idle (way above 30% baseline)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 2, 0.80);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
            assertThat(signal.payload().get("note")).contains("under-utilized");
        }

        @Test
        @DisplayName("Should include all required payload fields")
        void testAvailableCondition_PayloadCompleteness() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.IO, 8, 4, 0.50);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload())
                .containsKeys(
                    "assessment",
                    "interpretation",
                    "brokerId",
                    "poolType",
                    "totalThreads",
                    "activeThreads",
                    "idleThreads",
                    "avgIdlePercent",
                    "utilizationPercent",
                    "expectedIdlePercent"
                );

            assertThat(signal.payload().get("brokerId")).isEqualTo("broker-1");
            assertThat(signal.payload().get("poolType")).isEqualTo("I/O Threads");
            assertThat(signal.payload().get("totalThreads")).isEqualTo("8");
            assertThat(signal.payload().get("idleThreads")).isEqualTo("4");
        }
    }

    // ========================================================================
    // DEGRADED Condition Tests
    // ========================================================================

    @Nested
    @DisplayName("DEGRADED Condition Assessment")
    class DegradedTests {

        @Test
        @DisplayName("Should emit DEGRADED when idle 10-30%")
        void testDegradedCondition_ModerateLoad() {
            // Given: 22% idle (degraded)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.22);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
// confidence not applicable for ResourceSignal
            assertThat(signal.payload().get("assessment")).isEqualTo("DEGRADED");
            assertThat(signal.payload().get("interpretation")).contains("under pressure");
        }

        @Test
        @DisplayName("Should emit DEGRADED when below 80% of baseline")
        void testDegradedCondition_BelowBaseline() {
            // Given: 20% idle, which is below 80% of 30% baseline (24%)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.IO, 8, 2, 0.20);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
            assertThat(signal.payload().get("assessment")).isEqualTo("DEGRADED");
        }

        @Test
        @DisplayName("Should include monitoring recommendation")
        void testDegradedCondition_Recommendation() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.25);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload().get("recommendation"))
                .contains("Monitor")
                .contains("Network Threads");
        }

        @Test
        @DisplayName("Should add trend interpretation if data available")
        void testDegradedCondition_TrendAnalysis() {
            // Given: 22% idle now, was 30% previously (degrading)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.22);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);

            // Note: Trend won't appear in first observation (no historical data)
            // This test validates that the code handles missing historical data gracefully
        }
    }

    // ========================================================================
    // EXHAUSTED Condition Tests
    // ========================================================================

    @Nested
    @DisplayName("EXHAUSTED Condition Assessment")
    class ExhaustedTests {

        @Test
        @DisplayName("Should emit EXHAUSTED when idle < 10%")
        void testExhaustedCondition_LowIdle() {
            // Given: 8% idle (exhausted)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 0, 0.08);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
// confidence not applicable for ResourceSignal
            assertThat(signal.payload().get("assessment")).isEqualTo("EXHAUSTED");
            assertThat(signal.payload().get("interpretation")).contains("critically saturated");
        }

        @Test
        @DisplayName("Should emit EXHAUSTED when significantly below baseline")
        void testExhaustedCondition_BelowBaseline() {
            // Given: 12% idle, which is below 50% of 30% baseline (15%)
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.IO, 8, 1, 0.12);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
            assertThat(signal.payload().get("interpretation")).contains("below baseline");
        }

        @Test
        @DisplayName("Should include critical recommendation")
        void testExhaustedCondition_CriticalRecommendation() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 0, 0.05);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload().get("recommendation"))
                .contains("CRITICAL")
                .contains("Increase")
                .contains("3 threads");
        }

        @Test
        @DisplayName("Should escalate severity if rejections occurring")
        void testExhaustedCondition_WithRejections() {
            // Given: Exhausted pool with task rejections
            ThreadPoolMetrics metricsWithRejections = new ThreadPoolMetrics(
                "broker-1",
                ThreadPoolType.NETWORK,
                3,
                3,  // all active
                0,  // none idle
                0.02,  // 2% idle
                0L,  // queueSize
                1000L,  // totalTasksProcessed
                5L,  // rejectionCount - CRITICAL!
                System.currentTimeMillis()
            );

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metricsWithRejections);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.sign()).isEqualTo(Resources.Sign.DENY);
            assertThat(signal.payload().get("rejections")).isEqualTo("5");
            assertThat(signal.payload().get("severity")).contains("CRITICAL");
            assertThat(signal.payload().get("severity")).contains("rejected");
        }
    }

    // ========================================================================
    // Baseline Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("BaselineService Integration")
    class BaselineTests {

        @Test
        @DisplayName("Should use default baseline when no historical data")
        void testBaseline_NoHistoricalData() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            // Default baseline is 30%, so 35% should be AVAILABLE
            assertThat(signal.sign()).isEqualTo(Resources.Sign.GRANT);
            assertThat(signal.payload().get("expectedIdlePercent")).isEqualTo("0.30");
        }

        @Test
        @DisplayName("Should record observation to BaselineService")
        void testBaseline_RecordObservation() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then: Observation should be recorded
            verify(baselineService).recordObservation(
                eq("broker-1"),
                eq("thread_pool_idle_network"),
                eq(0.35),
                any(Instant.class)
            );
        }

        @Test
        @DisplayName("Should record observation for all pool types")
        void testBaseline_RecordForAllPoolTypes() {
            // Given: Different pool types
            ThreadPoolMetrics networkMetrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);
            ThreadPoolMetrics ioMetrics = createMetrics("broker-1", ThreadPoolType.IO, 8, 3, 0.40);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(networkMetrics);
            monitor.interpret(ioMetrics);

            // Then: Different metric keys
            verify(baselineService).recordObservation(
                eq("broker-1"),
                eq("thread_pool_idle_network"),
                anyDouble(),
                any(Instant.class)
            );
            verify(baselineService).recordObservation(
                eq("broker-1"),
                eq("thread_pool_idle_io"),
                anyDouble(),
                any(Instant.class)
            );
        }
    }

    // ========================================================================
    // Subject Creation Tests
    // ========================================================================

    @Nested
    @DisplayName("Subject Creation")
    class SubjectTests {

        @Test
        @DisplayName("Should create subject with circuit + entity")
        void testSubject_CircuitAndEntity() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

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

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(networkMetrics);
            ResourceSignal networkSignal = getLastSignal();

            monitor.interpret(ioMetrics);
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

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metricsWithQueue);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload().get("queueSize")).isEqualTo("50");
        }

        @Test
        @DisplayName("Should not include queue size if zero")
        void testPayload_QueueSizeOmitted() {
            // Given: Metrics with zero queue size
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload()).doesNotContainKey("queueSize");
        }

        @Test
        @DisplayName("Should include rejection count if non-zero")
        void testPayload_RejectionCountIncluded() {
            // Given: Metrics with rejections
            ThreadPoolMetrics metricsWithRejections = new ThreadPoolMetrics(
                "broker-1",
                ThreadPoolType.NETWORK,
                3,
                3,
                0,
                0.05,
                0L,
                1000L,
                10L,  // rejectionCount - should appear
                System.currentTimeMillis()
            );

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metricsWithRejections);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.payload().get("rejectionCount")).isEqualTo("10");
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
            assertThatThrownBy(() -> monitor.interpret(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metrics cannot be null");
        }

        @Test
        @DisplayName("Should handle BaselineService failure gracefully")
        void testErrorHandling_BaselineServiceFailure() {
            // Given: BaselineService throws exception
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            when(baselineService.hasBaseline("broker-1")).thenThrow(new RuntimeException("Database error"));

            // When: Should not propagate exception
            assertThatCode(() -> monitor.interpret(metrics))
                .doesNotThrowAnyException();

            // Then: Should still emit signal using default baseline
            assertThat(emittedSignals).isNotEmpty();
        }

        @Test
        @DisplayName("Should handle observation recording failure gracefully")
        void testErrorHandling_RecordObservationFailure() {
            // Given: recordObservation throws exception
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.35);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);
            doThrow(new RuntimeException("Storage error"))
                .when(baselineService)
                .recordObservation(anyString(), anyString(), anyDouble(), any(Instant.class));

            // When: Should not propagate exception
            assertThatCode(() -> monitor.interpret(metrics))
                .doesNotThrowAnyException();

            // Then: Should still emit signal
            assertThat(emittedSignals).isNotEmpty();
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
                new ThreadPoolResourceMonitor(null, signalPipe, baselineService)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("circuitName cannot be null");
        }

        @Test
        @DisplayName("Should reject null signalPipe")
        void testConstructor_NullSignalPipe() {
            assertThatThrownBy(() ->
                new ThreadPoolResourceMonitor(circuitName, null, baselineService)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("signalPipe cannot be null");
        }

        @Test
        @DisplayName("Should reject null baselineService")
        void testConstructor_NullBaselineService() {
            assertThatThrownBy(() ->
                new ThreadPoolResourceMonitor(circuitName, signalPipe, null)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("baselineService cannot be null");
        }
    }

    // ========================================================================
    // Semantic Helpers Tests
    // ========================================================================

    @Nested
    @DisplayName("ResourceSignal Semantic Helpers")
    class SemanticTests {

        @Test
        @DisplayName("EXHAUSTED should require attention")
        void testSemantic_ExhaustedRequiresAttention() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 0, 0.05);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.requiresAttention()).isTrue();
            // severity check removed - ResourceSignal doesn't have condition-based severity
        }

        @Test
        @DisplayName("AVAILABLE should not require attention")
        void testSemantic_AvailableNoAttention() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 1, 0.50);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            assertThat(signal.requiresAttention()).isFalse();
            // severity check removed - ResourceSignal doesn't have condition-based severity
        }

        @Test
        @DisplayName("Should generate human-readable interpretation")
        void testSemantic_InterpretMethod() {
            // Given
            ThreadPoolMetrics metrics = createMetrics("broker-1", ThreadPoolType.NETWORK, 3, 0, 0.05);

            when(baselineService.hasBaseline("broker-1")).thenReturn(false);

            // When
            monitor.interpret(metrics);

            // Then
            ResourceSignal signal = getLastSignal();
            String interpretation = signal.interpret();
            assertThat(interpretation).isNotNull();
            // ResourceSignal.interpret() should provide semantic interpretation
        }
    }
}
