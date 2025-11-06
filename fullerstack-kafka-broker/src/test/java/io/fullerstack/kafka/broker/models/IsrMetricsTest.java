package io.fullerstack.kafka.broker.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link IsrMetrics} immutable record.
 * <p>
 * Tests validate:
 * <ul>
 *   <li>Record creation and validation</li>
 *   <li>Semantic helper methods (hasShrinkEvents, isReplicaLagging, etc.)</li>
 *   <li>Delta computation for event detection</li>
 * </ul>
 */
@DisplayName("IsrMetrics")
class IsrMetricsTest {

    private static final String TEST_BROKER_ID = "broker-1";
    private static final long TEST_TIMESTAMP = System.currentTimeMillis();

    /**
     * Helper to create test metrics.
     */
    private IsrMetrics createMetrics(
        long isrShrinkCount,
        long isrExpandCount,
        long replicaMaxLag,
        double replicaMinFetchRate
    ) {
        return new IsrMetrics(
            TEST_BROKER_ID,
            isrShrinkCount,
            isrExpandCount,
            replicaMaxLag,
            replicaMinFetchRate,
            TEST_TIMESTAMP
        );
    }

    // ========================================================================
    // Constructor Validation Tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorTests {

        @Test
        @DisplayName("Should create valid metrics")
        void testConstructor_Valid() {
            IsrMetrics metrics = createMetrics(5, 3, 1000, 2.5);

            assertThat(metrics.brokerId()).isEqualTo(TEST_BROKER_ID);
            assertThat(metrics.isrShrinkCount()).isEqualTo(5);
            assertThat(metrics.isrExpandCount()).isEqualTo(3);
            assertThat(metrics.replicaMaxLag()).isEqualTo(1000);
            assertThat(metrics.replicaMinFetchRate()).isEqualTo(2.5);
            assertThat(metrics.timestamp()).isEqualTo(TEST_TIMESTAMP);
        }

        @Test
        @DisplayName("Should reject null brokerId")
        void testConstructor_NullBrokerId() {
            assertThatThrownBy(() ->
                new IsrMetrics(null, 0, 0, 0, 0.0, TEST_TIMESTAMP)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("brokerId required");
        }

        @Test
        @DisplayName("Should reject negative shrink count")
        void testConstructor_NegativeShrinkCount() {
            assertThatThrownBy(() ->
                createMetrics(-1, 0, 0, 1.0)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("isrShrinkCount must be >= 0");
        }

        @Test
        @DisplayName("Should reject negative expand count")
        void testConstructor_NegativeExpandCount() {
            assertThatThrownBy(() ->
                createMetrics(0, -1, 0, 1.0)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("isrExpandCount must be >= 0");
        }

        @Test
        @DisplayName("Should reject negative lag")
        void testConstructor_NegativeLag() {
            assertThatThrownBy(() ->
                createMetrics(0, 0, -1, 1.0)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("replicaMaxLag must be >= 0");
        }

        @Test
        @DisplayName("Should reject negative fetch rate")
        void testConstructor_NegativeFetchRate() {
            assertThatThrownBy(() ->
                createMetrics(0, 0, 0, -0.5)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("replicaMinFetchRate must be >= 0.0");
        }
    }

    // ========================================================================
    // Semantic Helper Tests - Shrink Events
    // ========================================================================

    @Nested
    @DisplayName("Shrink Events")
    class ShrinkEventTests {

        @Test
        @DisplayName("Should detect shrink events when count > 0")
        void testShrinkEvents_Detected() {
            IsrMetrics metrics = createMetrics(1, 0, 0, 1.0);
            assertThat(metrics.hasShrinkEvents()).isTrue();
        }

        @Test
        @DisplayName("Should not detect shrink events when count = 0")
        void testShrinkEvents_NotDetected() {
            IsrMetrics metrics = createMetrics(0, 0, 0, 1.0);
            assertThat(metrics.hasShrinkEvents()).isFalse();
        }

        @Test
        @DisplayName("Should detect multiple shrink events")
        void testShrinkEvents_Multiple() {
            IsrMetrics metrics = createMetrics(5, 0, 0, 1.0);
            assertThat(metrics.hasShrinkEvents()).isTrue();
            assertThat(metrics.isrShrinkCount()).isEqualTo(5);
        }
    }

    // ========================================================================
    // Semantic Helper Tests - Expand Events
    // ========================================================================

    @Nested
    @DisplayName("Expand Events")
    class ExpandEventTests {

        @Test
        @DisplayName("Should detect expand events when count > 0")
        void testExpandEvents_Detected() {
            IsrMetrics metrics = createMetrics(0, 1, 0, 1.0);
            assertThat(metrics.hasExpandEvents()).isTrue();
        }

        @Test
        @DisplayName("Should not detect expand events when count = 0")
        void testExpandEvents_NotDetected() {
            IsrMetrics metrics = createMetrics(0, 0, 0, 1.0);
            assertThat(metrics.hasExpandEvents()).isFalse();
        }

        @Test
        @DisplayName("Should detect multiple expand events")
        void testExpandEvents_Multiple() {
            IsrMetrics metrics = createMetrics(0, 3, 0, 1.0);
            assertThat(metrics.hasExpandEvents()).isTrue();
            assertThat(metrics.isrExpandCount()).isEqualTo(3);
        }
    }

    // ========================================================================
    // Semantic Helper Tests - Replica Lag
    // ========================================================================

    @Nested
    @DisplayName("Replica Lag")
    class ReplicaLagTests {

        @Test
        @DisplayName("Should detect lagging replica at warning threshold (1000)")
        void testReplicaLag_WarningThreshold() {
            IsrMetrics metrics = createMetrics(0, 0, 1000, 1.0);
            assertThat(metrics.isReplicaLagging()).isTrue();
            assertThat(metrics.isReplicaCritical()).isFalse();
        }

        @Test
        @DisplayName("Should detect lagging replica above warning threshold")
        void testReplicaLag_AboveWarning() {
            IsrMetrics metrics = createMetrics(0, 0, 5000, 1.0);
            assertThat(metrics.isReplicaLagging()).isTrue();
        }

        @Test
        @DisplayName("Should not detect lag below warning threshold")
        void testReplicaLag_BelowWarning() {
            IsrMetrics metrics = createMetrics(0, 0, 999, 1.0);
            assertThat(metrics.isReplicaLagging()).isFalse();
        }

        @Test
        @DisplayName("Should detect critical lag at threshold (10000)")
        void testReplicaLag_CriticalThreshold() {
            IsrMetrics metrics = createMetrics(0, 0, 10000, 1.0);
            assertThat(metrics.isReplicaCritical()).isTrue();
            assertThat(metrics.isReplicaLagging()).isTrue();
        }

        @Test
        @DisplayName("Should detect critical lag above threshold")
        void testReplicaLag_AboveCritical() {
            IsrMetrics metrics = createMetrics(0, 0, 15000, 1.0);
            assertThat(metrics.isReplicaCritical()).isTrue();
        }

        @Test
        @DisplayName("Should not detect critical lag below threshold")
        void testReplicaLag_BelowCritical() {
            IsrMetrics metrics = createMetrics(0, 0, 9999, 1.0);
            assertThat(metrics.isReplicaCritical()).isFalse();
        }
    }

    // ========================================================================
    // Semantic Helper Tests - Fetch Rate
    // ========================================================================

    @Nested
    @DisplayName("Fetch Rate")
    class FetchRateTests {

        @Test
        @DisplayName("Should detect degraded fetch rate below threshold (1.0)")
        void testFetchRate_Degraded() {
            IsrMetrics metrics = createMetrics(0, 0, 0, 0.5);
            assertThat(metrics.isFetchRateDegraded()).isTrue();
        }

        @Test
        @DisplayName("Should not detect degraded fetch rate at threshold")
        void testFetchRate_AtThreshold() {
            IsrMetrics metrics = createMetrics(0, 0, 0, 1.0);
            assertThat(metrics.isFetchRateDegraded()).isFalse();
        }

        @Test
        @DisplayName("Should not detect degraded fetch rate above threshold")
        void testFetchRate_Healthy() {
            IsrMetrics metrics = createMetrics(0, 0, 0, 2.5);
            assertThat(metrics.isFetchRateDegraded()).isFalse();
        }

        @Test
        @DisplayName("Should detect severely degraded fetch rate (near zero)")
        void testFetchRate_NearZero() {
            IsrMetrics metrics = createMetrics(0, 0, 0, 0.01);
            assertThat(metrics.isFetchRateDegraded()).isTrue();
        }
    }

    // ========================================================================
    // Delta Computation Tests
    // ========================================================================

    @Nested
    @DisplayName("Delta Computation")
    class DeltaComputationTests {

        @Test
        @DisplayName("Should compute shrink count delta")
        void testDelta_ShrinkCount() {
            IsrMetrics previous = createMetrics(5, 0, 0, 1.0);
            IsrMetrics current = createMetrics(8, 0, 0, 1.0);

            IsrMetrics delta = current.delta(previous);

            assertThat(delta.isrShrinkCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should compute expand count delta")
        void testDelta_ExpandCount() {
            IsrMetrics previous = createMetrics(0, 2, 0, 1.0);
            IsrMetrics current = createMetrics(0, 7, 0, 1.0);

            IsrMetrics delta = current.delta(previous);

            assertThat(delta.isrExpandCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should use absolute lag value (not delta)")
        void testDelta_Lag() {
            IsrMetrics previous = createMetrics(0, 0, 5000, 1.0);
            IsrMetrics current = createMetrics(0, 0, 8000, 1.0);

            IsrMetrics delta = current.delta(previous);

            // Lag is absolute, not cumulative
            assertThat(delta.replicaMaxLag()).isEqualTo(8000);
        }

        @Test
        @DisplayName("Should use absolute fetch rate (not delta)")
        void testDelta_FetchRate() {
            IsrMetrics previous = createMetrics(0, 0, 0, 2.0);
            IsrMetrics current = createMetrics(0, 0, 0, 1.5);

            IsrMetrics delta = current.delta(previous);

            // Fetch rate is absolute, not cumulative
            assertThat(delta.replicaMinFetchRate()).isEqualTo(1.5);
        }

        @Test
        @DisplayName("Should handle zero delta (no change)")
        void testDelta_NoChange() {
            IsrMetrics previous = createMetrics(5, 3, 1000, 2.0);
            IsrMetrics current = createMetrics(5, 3, 1000, 2.0);

            IsrMetrics delta = current.delta(previous);

            assertThat(delta.isrShrinkCount()).isZero();
            assertThat(delta.isrExpandCount()).isZero();
        }

        @Test
        @DisplayName("Should prevent negative delta (count decreased)")
        void testDelta_NegativePrevented() {
            // Counts should never decrease, but if they do, delta should be 0
            IsrMetrics previous = createMetrics(10, 5, 0, 1.0);
            IsrMetrics current = createMetrics(8, 3, 0, 1.0);

            IsrMetrics delta = current.delta(previous);

            // Math.max(0, ...) ensures no negative deltas
            assertThat(delta.isrShrinkCount()).isZero();
            assertThat(delta.isrExpandCount()).isZero();
        }

        @Test
        @DisplayName("Should reject null previous metrics")
        void testDelta_NullPrevious() {
            IsrMetrics current = createMetrics(5, 3, 1000, 2.0);

            assertThatThrownBy(() -> current.delta(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("previous metrics required");
        }
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle zero values")
        void testEdgeCase_AllZero() {
            IsrMetrics metrics = createMetrics(0, 0, 0, 0.0);

            assertThat(metrics.hasShrinkEvents()).isFalse();
            assertThat(metrics.hasExpandEvents()).isFalse();
            assertThat(metrics.isReplicaLagging()).isFalse();
            assertThat(metrics.isFetchRateDegraded()).isTrue(); // 0.0 < 1.0
        }

        @Test
        @DisplayName("Should handle maximum values")
        void testEdgeCase_MaxValues() {
            IsrMetrics metrics = createMetrics(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Double.MAX_VALUE
            );

            assertThat(metrics.hasShrinkEvents()).isTrue();
            assertThat(metrics.hasExpandEvents()).isTrue();
            assertThat(metrics.isReplicaLagging()).isTrue();
            assertThat(metrics.isReplicaCritical()).isTrue();
        }
    }
}
