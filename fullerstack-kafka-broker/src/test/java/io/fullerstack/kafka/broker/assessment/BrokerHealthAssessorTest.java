package io.fullerstack.kafka.broker.assessment;

import io.fullerstack.kafka.broker.baseline.BaselineService;
import io.fullerstack.serventis.signals.MonitorSignal;
import io.humainary.modules.serventis.monitors.api.Monitors;
import io.humainary.substrates.api.Substrates.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static io.humainary.substrates.api.Substrates.Cortex;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BrokerHealthAssessor.
 * <p>
 * Tests verify:
 * <ul>
 *   <li>All Monitors.Condition cases are covered</li>
 *   <li>Confidence levels are correctly determined</li>
 *   <li>Payload contains assessment, evidence, and recommendations</li>
 *   <li>Observations are recorded to BaselineService</li>
 *   <li>Edge cases and validation logic</li>
 * </ul>
 */
class BrokerHealthAssessorTest {

    private BaselineService baselineService;
    private BrokerHealthAssessor assessor;
    private Subject brokerSubject;
    private static final String BROKER_ID = "broker-1";

    @BeforeEach
    void setUp() {
        baselineService = mock(BaselineService.class);
        assessor = new BrokerHealthAssessor(baselineService);

        // Mock Subject for testing
        brokerSubject = mock(Subject.class);

        // Default baseline mocks
        when(baselineService.getExpectedHeapPercent(eq(BROKER_ID), any(Instant.class)))
            .thenReturn(60.0);
        when(baselineService.getExpectedCpuUsage(eq(BROKER_ID), any(Instant.class)))
            .thenReturn(0.60);
        when(baselineService.getTrend(eq(BROKER_ID), eq("heap"), any(Duration.class)))
            .thenReturn("stable");
        when(baselineService.getTrend(eq(BROKER_ID), eq("cpu"), any(Duration.class)))
            .thenReturn("stable");
        when(baselineService.getConfidence(eq(BROKER_ID), eq("heap")))
            .thenReturn(0.9);
    }

    @Nested
    @DisplayName("Condition: STABLE")
    class StableConditionTests {

        @Test
        @DisplayName("should emit STABLE when all metrics within expected parameters")
        void testStableCondition() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.STABLE, signal.status().condition());
            assertTrue(signal.payload().get("assessment").contains("normally"));
            assertFalse(signal.payload().containsKey("recommendation"));
        }
    }

    @Nested
    @DisplayName("Condition: CONVERGING")
    class ConvergingConditionTests {

        @Test
        @DisplayName("should emit CONVERGING when heap decreasing but still elevated")
        void testConvergingCondition() {
            when(baselineService.getTrend(eq(BROKER_ID), eq("heap"), any(Duration.class)))
                .thenReturn("decreasing");

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                70.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.CONVERGING, signal.status().condition());
            assertTrue(signal.payload().get("assessment").contains("recovering"));
            assertEquals("decreasing", signal.payload().get("heap_trend"));
        }
    }

    @Nested
    @DisplayName("Condition: DIVERGING")
    class DivergingConditionTests {

        @Test
        @DisplayName("should emit DIVERGING when heap exceeds baseline * 1.2")
        void testDivergingFromHeap() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                75.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.DIVERGING, signal.status().condition());
            assertTrue(signal.payload().get("assessment").contains("diverging"));
            assertTrue(signal.payload().get("evidence").contains("75.0%"));
            assertTrue(signal.payload().containsKey("recommendation"));
        }

        @Test
        @DisplayName("should emit DIVERGING when CPU exceeds 80%")
        void testDivergingFromCpu() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                55.0, 0.85, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.DIVERGING, signal.status().condition());
            assertTrue(signal.payload().get("evidence").contains("85%"));
        }
    }

    @Nested
    @DisplayName("Condition: ERRATIC")
    class ErraticConditionTests {

        @Test
        @DisplayName("should emit ERRATIC when heap trend is erratic")
        void testErraticFromHeapTrend() {
            when(baselineService.getTrend(eq(BROKER_ID), eq("heap"), any(Duration.class)))
                .thenReturn("erratic");

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.ERRATIC, signal.status().condition());
            assertTrue(signal.payload().get("assessment").contains("unstable"));
        }

        @Test
        @DisplayName("should emit ERRATIC when CPU trend is erratic")
        void testErraticFromCpuTrend() {
            when(baselineService.getTrend(eq(BROKER_ID), eq("cpu"), any(Duration.class)))
                .thenReturn("erratic");

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.ERRATIC, signal.status().condition());
        }
    }

    @Nested
    @DisplayName("Condition: DEGRADED")
    class DegradedConditionTests {

        @Test
        @DisplayName("should emit DEGRADED when heap > baseline * 1.3 with increasing trend")
        void testDegradedFromHeap() {
            when(baselineService.getTrend(eq(BROKER_ID), eq("heap"), any(Duration.class)))
                .thenReturn("increasing");

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                85.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.DEGRADED, signal.status().condition());
            assertTrue(signal.payload().get("assessment").contains("degradation"));
            assertTrue(signal.payload().get("evidence").contains("85.0%"));
            assertTrue(signal.payload().get("recommendation").contains("heap"));
        }

        @Test
        @DisplayName("should emit DEGRADED when CPU > 90%")
        void testDegradedFromCpu() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                55.0, 0.95, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.DEGRADED, signal.status().condition());
            assertTrue(signal.payload().get("evidence").contains("95%"));
            assertTrue(signal.payload().get("recommendation").contains("CPU"));
        }

        @Test
        @DisplayName("should NOT emit DEGRADED when heap high but trend is stable")
        void testHeapHighButStable() {
            when(baselineService.getTrend(eq(BROKER_ID), eq("heap"), any(Duration.class)))
                .thenReturn("stable");

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                80.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            // Should be DIVERGING, not DEGRADED
            assertEquals(Monitors.Condition.DIVERGING, signal.status().condition());
        }
    }

    @Nested
    @DisplayName("Condition: DEFECTIVE")
    class DefectiveConditionTests {

        @Test
        @DisplayName("should emit DEFECTIVE when under-replicated partitions exist")
        void testDefectiveFromUnderReplication() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                55.0, 0.55, 1000, 5, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.DEFECTIVE, signal.status().condition());
            assertTrue(signal.payload().get("assessment").contains("DEFECTIVE"));
            assertTrue(signal.payload().get("evidence").contains("5"));
            assertEquals("5", signal.payload().get("under_replicated_partitions"));
            assertTrue(signal.payload().get("recommendation").contains("replication"));
        }
    }

    @Nested
    @DisplayName("Condition: DOWN")
    class DownConditionTests {

        @Test
        @DisplayName("should emit DOWN when offline partitions exist")
        void testDownFromOfflinePartitions() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                55.0, 0.55, 1000, 0, 3, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.DOWN, signal.status().condition());
            assertTrue(signal.payload().get("assessment").contains("DOWN"));
            assertTrue(signal.payload().get("evidence").contains("3"));
            assertEquals("3", signal.payload().get("offline_partitions"));
            assertTrue(signal.payload().get("recommendation").contains("CRITICAL"));
        }

        @Test
        @DisplayName("should prioritize DOWN over DEFECTIVE when both partition issues exist")
        void testDownTakesPrecedence() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                55.0, 0.55, 1000, 5, 2, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Condition.DOWN, signal.status().condition());
        }
    }

    @Nested
    @DisplayName("Confidence Levels")
    class ConfidenceLevelTests {

        @Test
        @DisplayName("should emit CONFIRMED confidence when baseline confidence > 0.8 and stable trend")
        void testConfirmedConfidence() {
            when(baselineService.getConfidence(eq(BROKER_ID), eq("heap")))
                .thenReturn(0.9);
            when(baselineService.getTrend(eq(BROKER_ID), eq("heap"), any(Duration.class)))
                .thenReturn("stable");

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Confidence.CONFIRMED, signal.status().confidence());
        }

        @Test
        @DisplayName("should emit MEASURED confidence when baseline confidence > 0.5")
        void testMeasuredConfidence() {
            when(baselineService.getConfidence(eq(BROKER_ID), eq("heap")))
                .thenReturn(0.7);

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Confidence.MEASURED, signal.status().confidence());
        }

        @Test
        @DisplayName("should emit TENTATIVE confidence when baseline confidence <= 0.5")
        void testTentativeConfidence() {
            when(baselineService.getConfidence(eq(BROKER_ID), eq("heap")))
                .thenReturn(0.3);

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Confidence.TENTATIVE, signal.status().confidence());
        }

        @Test
        @DisplayName("should not be CONFIRMED if trend is not stable")
        void testNotConfirmedWithoutStableTrend() {
            when(baselineService.getConfidence(eq(BROKER_ID), eq("heap")))
                .thenReturn(0.9);
            when(baselineService.getTrend(eq(BROKER_ID), eq("heap"), any(Duration.class)))
                .thenReturn("increasing");

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(Monitors.Confidence.MEASURED, signal.status().confidence());
        }
    }

    @Nested
    @DisplayName("Payload Construction")
    class PayloadConstructionTests {

        @Test
        @DisplayName("should include core metrics in payload")
        void testCoreMetricsInPayload() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                72.5, 0.68, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            Map<String, String> payload = signal.payload();
            assertEquals("broker_health", payload.get("metric"));
            assertEquals("72.5%", payload.get("heap_current"));
            assertEquals("60.0%", payload.get("heap_baseline"));
            assertEquals("stable", payload.get("heap_trend"));
            assertEquals("0.68", payload.get("cpu_current"));
            assertEquals("0.60", payload.get("cpu_baseline"));
            assertEquals("stable", payload.get("cpu_trend"));
        }

        @Test
        @DisplayName("should include assessment, evidence in payload")
        void testAssessmentAndEvidenceInPayload() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertTrue(signal.payload().containsKey("assessment"));
            assertTrue(signal.payload().containsKey("evidence"));
            assertFalse(signal.payload().get("assessment").isBlank());
            assertFalse(signal.payload().get("evidence").isBlank());
        }

        @Test
        @DisplayName("should include recommendation for non-STABLE conditions")
        void testRecommendationForNonStable() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                75.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertTrue(signal.payload().containsKey("recommendation"));
            assertFalse(signal.payload().get("recommendation").isBlank());
        }

        @Test
        @DisplayName("should NOT include recommendation for STABLE condition")
        void testNoRecommendationForStable() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertFalse(signal.payload().containsKey("recommendation"));
        }

        @Test
        @DisplayName("should only include partition counts when non-zero")
        void testPartitionCountsOnlyWhenNonZero() {
            // No partition issues
            BrokerHealthAssessor.JmxData jmxData1 = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal1 = assessor.assess(BROKER_ID, brokerSubject, jmxData1);
            assertFalse(signal1.payload().containsKey("under_replicated_partitions"));
            assertFalse(signal1.payload().containsKey("offline_partitions"));

            // With partition issues
            BrokerHealthAssessor.JmxData jmxData2 = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 3, 2, 90, 85
            );

            MonitorSignal signal2 = assessor.assess(BROKER_ID, brokerSubject, jmxData2);
            assertEquals("3", signal2.payload().get("under_replicated_partitions"));
            assertEquals("2", signal2.payload().get("offline_partitions"));
        }
    }

    @Nested
    @DisplayName("Baseline Recording")
    class BaselineRecordingTests {

        @Test
        @DisplayName("should record heap and CPU observations to BaselineService")
        void testRecordObservations() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                72.5, 0.68, 1000, 0, 0, 90, 85
            );

            assessor.assess(BROKER_ID, brokerSubject, jmxData);

            verify(baselineService).recordObservation(
                eq(BROKER_ID), eq("heap"), eq(72.5), any(Instant.class)
            );
            verify(baselineService).recordObservation(
                eq(BROKER_ID), eq("cpu"), eq(0.68), any(Instant.class)
            );
        }

        @Test
        @DisplayName("should record observations even when condition is DOWN")
        void testRecordObservationsEvenWhenDown() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                85.0, 0.95, 1000, 0, 5, 90, 85
            );

            assessor.assess(BROKER_ID, brokerSubject, jmxData);

            verify(baselineService).recordObservation(eq(BROKER_ID), eq("heap"), eq(85.0), any(Instant.class));
            verify(baselineService).recordObservation(eq(BROKER_ID), eq("cpu"), eq(0.95), any(Instant.class));
        }
    }

    @Nested
    @DisplayName("JmxData Validation")
    class JmxDataValidationTests {

        @Test
        @DisplayName("should reject invalid heap percent")
        void testRejectInvalidHeap() {
            assertThrows(IllegalArgumentException.class, () ->
                new BrokerHealthAssessor.JmxData(-10.0, 0.55, 1000, 0, 0, 90, 85)
            );
            assertThrows(IllegalArgumentException.class, () ->
                new BrokerHealthAssessor.JmxData(105.0, 0.55, 1000, 0, 0, 90, 85)
            );
        }

        @Test
        @DisplayName("should accept valid heap percent boundaries")
        void testAcceptValidHeapBoundaries() {
            assertDoesNotThrow(() ->
                new BrokerHealthAssessor.JmxData(0.0, 0.55, 1000, 0, 0, 90, 85)
            );
            assertDoesNotThrow(() ->
                new BrokerHealthAssessor.JmxData(100.0, 0.55, 1000, 0, 0, 90, 85)
            );
        }

        @Test
        @DisplayName("should reject invalid CPU usage")
        void testRejectInvalidCpu() {
            assertThrows(IllegalArgumentException.class, () ->
                new BrokerHealthAssessor.JmxData(65.0, -0.1, 1000, 0, 0, 90, 85)
            );
            assertThrows(IllegalArgumentException.class, () ->
                new BrokerHealthAssessor.JmxData(65.0, 1.5, 1000, 0, 0, 90, 85)
            );
        }

        @Test
        @DisplayName("should reject negative counts")
        void testRejectNegativeCounts() {
            assertThrows(IllegalArgumentException.class, () ->
                new BrokerHealthAssessor.JmxData(65.0, 0.55, -100, 0, 0, 90, 85)
            );
            assertThrows(IllegalArgumentException.class, () ->
                new BrokerHealthAssessor.JmxData(65.0, 0.55, 1000, -5, 0, 90, 85)
            );
            assertThrows(IllegalArgumentException.class, () ->
                new BrokerHealthAssessor.JmxData(65.0, 0.55, 1000, 0, -3, 90, 85)
            );
        }
    }

    @Nested
    @DisplayName("Signal Structure")
    class SignalStructureTests {

        @Test
        @DisplayName("should create signal with correct subject and metadata")
        void testSignalStructure() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertEquals(brokerSubject, signal.subject());
            assertNotNull(signal.id());
            assertNotNull(signal.timestamp());
        }

        @Test
        @DisplayName("should create unique signal IDs for multiple assessments")
        void testUniqueSignalIds() {
            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                65.0, 0.55, 1000, 0, 0, 90, 85
            );

            MonitorSignal signal1 = assessor.assess(BROKER_ID, brokerSubject, jmxData);
            MonitorSignal signal2 = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            assertNotEquals(signal1.id(), signal2.id());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle multiple issues with correct precedence")
        void testMultipleIssues() {
            when(baselineService.getTrend(eq(BROKER_ID), eq("heap"), any(Duration.class)))
                .thenReturn("increasing");

            BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
                85.0, 0.92, 1000, 5, 0, 90, 85
            );

            MonitorSignal signal = assessor.assess(BROKER_ID, brokerSubject, jmxData);

            // DEFECTIVE takes precedence
            assertEquals(Monitors.Condition.DEFECTIVE, signal.status().condition());
            // But evidence should mention all issues
            String evidence = signal.payload().get("evidence");
            assertTrue(evidence.contains("under-replicated") || evidence.contains("Heap") || evidence.contains("CPU"));
        }

        @Test
        @DisplayName("should handle threshold boundaries correctly")
        void testThresholdBoundaries() {
            when(baselineService.getExpectedHeapPercent(eq(BROKER_ID), any(Instant.class)))
                .thenReturn(60.0);

            // Exactly at threshold (60.0 * 1.2 = 72.0)
            BrokerHealthAssessor.JmxData jmxData1 = new BrokerHealthAssessor.JmxData(
                72.0, 0.55, 1000, 0, 0, 90, 85
            );
            MonitorSignal signal1 = assessor.assess(BROKER_ID, brokerSubject, jmxData1);
            assertEquals(Monitors.Condition.STABLE, signal1.status().condition());

            // Just above threshold
            BrokerHealthAssessor.JmxData jmxData2 = new BrokerHealthAssessor.JmxData(
                72.01, 0.55, 1000, 0, 0, 90, 85
            );
            MonitorSignal signal2 = assessor.assess(BROKER_ID, brokerSubject, jmxData2);
            assertEquals(Monitors.Condition.DIVERGING, signal2.status().condition());
        }
    }
}
