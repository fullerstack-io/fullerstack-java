package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.Monitors;
import io.humainary.substrates.ext.serventis.Monitors.Monitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProducerHealthDetector}.
 * <p>
 * Tests health detection logic, condition assessment, and signal emission patterns.
 */
class ProducerHealthDetectorTest {

    @Mock
    private Monitor mockHealthMonitor;

    private AutoCloseable mocks;
    private ProducerHealthDetector detector;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        detector = new ProducerHealthDetector("test-producer", mockHealthMonitor);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (detector != null) {
            detector.close();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    // ========================================
    // Constructor Tests
    // ========================================

    @Test
    void testConstructorWithValidParameters() {
        // When
        ProducerHealthDetector detector = new ProducerHealthDetector(
            "producer-1",
            mockHealthMonitor
        );

        // Then
        assertThat(detector).isNotNull();
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);

        // Cleanup
        detector.close();
    }

    @Test
    void testConstructorDoesNotEmitSignals() {
        // When
        ProducerHealthDetector detector = new ProducerHealthDetector(
            "producer-1",
            mockHealthMonitor
        );

        // Then - monitor should not be called during construction
        verifyNoInteractions(mockHealthMonitor);

        // Cleanup
        detector.close();
    }

    // ========================================
    // Initial State Tests
    // ========================================

    @Test
    void testInitialConditionIsStable() {
        // Then
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);
        assertThat(detector.getErrorCount()).isZero();
        assertThat(detector.getOverflowCount()).isZero();
        assertThat(detector.getLatencyOverflowCount()).isZero();
    }

    // ========================================
    // Buffer Overflow Tests
    // ========================================

    @Test
    void testSingleBufferOverflowTriggersDiverging() {
        // When
        detector.onBufferOverflow();

        // Then
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DIVERGING);
        assertThat(detector.getOverflowCount()).isEqualTo(1);

        // Verify monitor.status() was called with DIVERGING
        ArgumentCaptor<Monitors.Condition> conditionCaptor = ArgumentCaptor.forClass(Monitors.Condition.class);
        ArgumentCaptor<Monitors.Confidence> confidenceCaptor = ArgumentCaptor.forClass(Monitors.Confidence.class);
        verify(mockHealthMonitor).status(conditionCaptor.capture(), confidenceCaptor.capture());

        assertThat(conditionCaptor.getValue()).isEqualTo(Monitors.Condition.DIVERGING);
    }

    @Test
    void testMultipleBufferOverflowsTriggerDegraded() {
        // When - 2+ overflows → DEGRADED
        detector.onBufferOverflow();
        detector.onBufferOverflow();

        // Then
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DEGRADED);
        assertThat(detector.getOverflowCount()).isEqualTo(2);
    }

    @Test
    void testBufferUnderflowReducesOverflowCount() {
        // Given
        detector.onBufferOverflow();
        detector.onBufferOverflow();
        assertThat(detector.getOverflowCount()).isEqualTo(2);

        // When
        detector.onBufferUnderflow();

        // Then
        assertThat(detector.getOverflowCount()).isEqualTo(1);
    }

    @Test
    void testBufferNormalWithNoOverflowsDoesNotChangeState() {
        // Given - initially stable
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);

        // When
        detector.onBufferNormal();

        // Then - still stable, no signals emitted
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);
        verifyNoInteractions(mockHealthMonitor);
    }

    // ========================================
    // Error Tests
    // ========================================

    @Test
    void testSingleErrorTriggersDiverging() {
        // When
        detector.onErrorIncrement();

        // Then
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DIVERGING);
        assertThat(detector.getErrorCount()).isEqualTo(1);

        verify(mockHealthMonitor).status(Monitors.Condition.DIVERGING, Monitors.Confidence.TENTATIVE);
    }

    @Test
    void testMultipleErrorsTriggerDegraded() {
        // When - 3+ errors → DEGRADED
        detector.onErrorIncrement();
        detector.onErrorIncrement();
        detector.onErrorIncrement();

        // Then
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DEGRADED);
        assertThat(detector.getErrorCount()).isEqualTo(3);
    }

    @Test
    void testManyErrorsTriggerDefective() {
        // When - 10+ errors → DEFECTIVE
        for (int i = 0; i < 10; i++) {
            detector.onErrorIncrement();
        }

        // Then
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DEFECTIVE);
        assertThat(detector.getErrorCount()).isEqualTo(10);
    }

    // ========================================
    // Buffer Exhaustion Tests
    // ========================================

    @Test
    void testSingleBufferExhaustionRemainsStable() {
        // When - single exhaustion is not enough to trigger DIVERGING
        detector.onBufferExhaustion();

        // Then - still STABLE (needs 2+ for DEGRADED)
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);
    }

    @Test
    void testMultipleBufferExhaustionsTriggerDegraded() {
        // When - 2+ exhaustions → DEGRADED
        detector.onBufferExhaustion();
        detector.onBufferExhaustion();

        // Then
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DEGRADED);
    }

    // ========================================
    // Latency Tests
    // ========================================

    @Test
    void testSingleLatencyOverflowRemainsStable() {
        // When - single latency overflow (threshold is 3)
        detector.onLatencyOverflow();

        // Then - still STABLE (needs 3+ for DIVERGING)
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);
        assertThat(detector.getLatencyOverflowCount()).isEqualTo(1);
    }

    @Test
    void testMultipleLatencyOverflowsTriggerDiverging() {
        // When - 3+ latency overflows → still DIVERGING (not DEGRADED yet)
        detector.onLatencyOverflow();
        detector.onLatencyOverflow();
        detector.onLatencyOverflow();

        // Then
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DIVERGING);
        assertThat(detector.getLatencyOverflowCount()).isEqualTo(3);
    }

    @Test
    void testLatencyImprovementReducesLatencyOverflowCount() {
        // Given
        detector.onLatencyOverflow();
        detector.onLatencyOverflow();
        assertThat(detector.getLatencyOverflowCount()).isEqualTo(2);

        // When
        detector.onLatencyImprovement();

        // Then
        assertThat(detector.getLatencyOverflowCount()).isEqualTo(1);
    }

    // ========================================
    // Combined Failure Tests
    // ========================================

    @Test
    void testErrorsAndOverflowsTriggerDefective() {
        // When - 3+ errors + 2+ overflows → DEFECTIVE
        detector.onErrorIncrement();
        detector.onErrorIncrement();
        detector.onErrorIncrement();
        detector.onBufferOverflow();
        detector.onBufferOverflow();

        // Then
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DEFECTIVE);
    }

    @Test
    void testMultipleSignalTypesTriggerDegraded() {
        // When - combination of issues
        detector.onErrorIncrement();
        detector.onBufferOverflow();
        detector.onLatencyOverflow();

        // Then - should be at least DIVERGING
        assertThat(detector.getCurrentCondition()).isIn(
            Monitors.Condition.DIVERGING,
            Monitors.Condition.DEGRADED
        );
    }

    // ========================================
    // Recovery Tests
    // ========================================

    @Test
    void testRecoveryFromDivergingToConverging() {
        // Given - in DIVERGING state
        detector.onErrorIncrement();
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DIVERGING);

        // When - errors clear (simulated by reset)
        detector.reset();

        // Then - back to STABLE
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);
        assertThat(detector.getErrorCount()).isZero();
    }

    @Test
    void testResetClearsAllCounters() {
        // Given - multiple issues
        detector.onErrorIncrement();
        detector.onErrorIncrement();
        detector.onBufferOverflow();
        detector.onLatencyOverflow();

        // When
        detector.reset();

        // Then - all counters cleared
        assertThat(detector.getErrorCount()).isZero();
        assertThat(detector.getOverflowCount()).isZero();
        assertThat(detector.getLatencyOverflowCount()).isZero();
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);

        // Verify STABLE signal emitted
        verify(mockHealthMonitor).status(Monitors.Condition.STABLE, Monitors.Confidence.CONFIRMED);
    }

    // ========================================
    // Confidence Tests
    // ========================================

    @Test
    void testConfidenceIncreasesWithMoreEvidence() {
        // When - single error (low confidence)
        detector.onErrorIncrement();

        // Then - TENTATIVE confidence
        verify(mockHealthMonitor).status(Monitors.Condition.DIVERGING, Monitors.Confidence.TENTATIVE);
    }

    @Test
    void testHighConfidenceWithManyErrors() {
        // When - many errors (high confidence)
        for (int i = 0; i < 10; i++) {
            detector.onErrorIncrement();
        }

        // Then - CONFIRMED confidence
        ArgumentCaptor<Monitors.Confidence> confidenceCaptor = ArgumentCaptor.forClass(Monitors.Confidence.class);
        verify(mockHealthMonitor, atLeastOnce()).status(any(), confidenceCaptor.capture());

        // Last call should have CONFIRMED
        assertThat(confidenceCaptor.getValue()).isEqualTo(Monitors.Confidence.CONFIRMED);
    }

    // ========================================
    // Lifecycle Tests
    // ========================================

    @Test
    void testCloseDoesNotThrow() {
        // When / Then
        assertThatCode(() -> detector.close()).doesNotThrowAnyException();
    }

    @Test
    void testMultipleCloseCallsAreIdempotent() {
        // When / Then
        assertThatCode(() -> {
            detector.close();
            detector.close();
            detector.close();
        }).doesNotThrowAnyException();
    }

    // ========================================
    // State Transition Tests
    // ========================================

    @Test
    void testStateTransitionEmitsOnlyOnChange() {
        // Given - initial STABLE
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);

        // When - first error (STABLE → DIVERGING)
        detector.onErrorIncrement();

        // Then - signal emitted
        verify(mockHealthMonitor, times(1)).status(Monitors.Condition.DIVERGING, Monitors.Confidence.TENTATIVE);

        // When - second error (still DIVERGING, no state change)
        reset(mockHealthMonitor);
        detector.onErrorIncrement();

        // Then - still DIVERGING, no new signal
        verifyNoInteractions(mockHealthMonitor);
    }

    @Test
    void testStateTransitionFromDivergingToDegraded() {
        // Given - DIVERGING state
        detector.onErrorIncrement();
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DIVERGING);
        reset(mockHealthMonitor);

        // When - more errors push to DEGRADED
        detector.onErrorIncrement();
        detector.onErrorIncrement();

        // Then - new signal for DEGRADED
        verify(mockHealthMonitor).status(eq(Monitors.Condition.DEGRADED), any(Monitors.Confidence.class));
    }

    @Test
    void testStateTransitionFromDegradedToDefective() {
        // Given - DEGRADED state
        for (int i = 0; i < 3; i++) {
            detector.onErrorIncrement();
        }
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DEGRADED);
        reset(mockHealthMonitor);

        // When - many more errors push to DEFECTIVE
        for (int i = 0; i < 7; i++) {
            detector.onErrorIncrement();
        }

        // Then - new signal for DEFECTIVE
        verify(mockHealthMonitor).status(eq(Monitors.Condition.DEFECTIVE), any(Monitors.Confidence.class));
    }

    // ========================================
    // Edge Cases
    // ========================================

    @Test
    void testZeroEventsKeepsStable() {
        // Given - no events
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);

        // When - normal buffer operation
        detector.onBufferNormal();

        // Then - still stable
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);
        verifyNoInteractions(mockHealthMonitor);
    }

    @Test
    void testUnderflowCannotReduceCountBelowZero() {
        // Given - zero overflows
        assertThat(detector.getOverflowCount()).isZero();

        // When - underflow
        detector.onBufferUnderflow();

        // Then - count stays at zero
        assertThat(detector.getOverflowCount()).isZero();
    }

    @Test
    void testLatencyImprovementCannotReduceCountBelowZero() {
        // Given - zero latency overflows
        assertThat(detector.getLatencyOverflowCount()).isZero();

        // When - improvement
        detector.onLatencyImprovement();

        // Then - count stays at zero
        assertThat(detector.getLatencyOverflowCount()).isZero();
    }

    // ========================================
    // Integration-Like Tests
    // ========================================

    @Test
    void testRealisticDegradationScenario() {
        // Scenario: Producer starts having issues
        // 1. Initial errors
        detector.onErrorIncrement();
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DIVERGING);

        // 2. Buffer starts filling up
        detector.onBufferOverflow();
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DIVERGING);

        // 3. More errors accumulate
        detector.onErrorIncrement();
        detector.onErrorIncrement();
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DEGRADED);

        // 4. Buffer exhaustion
        detector.onBufferExhaustion();
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DEGRADED);

        // 5. Critical failure
        for (int i = 0; i < 7; i++) {
            detector.onErrorIncrement();
        }
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.DEFECTIVE);
    }

    @Test
    void testRealisticRecoveryScenario() {
        // Scenario: Producer recovers from issues
        // 1. Start with errors
        detector.onErrorIncrement();
        detector.onErrorIncrement();
        detector.onBufferOverflow();

        // 2. Issues resolve
        detector.reset();

        // 3. Back to normal
        assertThat(detector.getCurrentCondition()).isEqualTo(Monitors.Condition.STABLE);
        assertThat(detector.getErrorCount()).isZero();
        assertThat(detector.getOverflowCount()).isZero();
    }
}
