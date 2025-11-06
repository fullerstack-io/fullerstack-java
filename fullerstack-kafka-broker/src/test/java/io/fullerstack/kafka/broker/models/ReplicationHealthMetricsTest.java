package io.fullerstack.kafka.broker.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link ReplicationHealthMetrics} immutable record.
 * <p>
 * Tests validate:
 * <ul>
 *   <li>Record creation and validation</li>
 *   <li>Semantic helper methods (isHealthy, isDegraded, isDefective)</li>
 *   <li>Health state transitions</li>
 * </ul>
 */
@DisplayName("ReplicationHealthMetrics")
class ReplicationHealthMetricsTest {

    private static final String TEST_CLUSTER_ID = "cluster-1";
    private static final long TEST_TIMESTAMP = System.currentTimeMillis();

    /**
     * Helper to create test metrics.
     */
    private ReplicationHealthMetrics createMetrics(
        int underReplicatedPartitions,
        int offlinePartitions,
        int activeControllerCount
    ) {
        return new ReplicationHealthMetrics(
            TEST_CLUSTER_ID,
            underReplicatedPartitions,
            offlinePartitions,
            activeControllerCount,
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
            ReplicationHealthMetrics metrics = createMetrics(5, 2, 1);

            assertThat(metrics.clusterId()).isEqualTo(TEST_CLUSTER_ID);
            assertThat(metrics.underReplicatedPartitions()).isEqualTo(5);
            assertThat(metrics.offlinePartitions()).isEqualTo(2);
            assertThat(metrics.activeControllerCount()).isEqualTo(1);
            assertThat(metrics.timestamp()).isEqualTo(TEST_TIMESTAMP);
        }

        @Test
        @DisplayName("Should reject null clusterId")
        void testConstructor_NullClusterId() {
            assertThatThrownBy(() ->
                new ReplicationHealthMetrics(null, 0, 0, 1, TEST_TIMESTAMP)
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("clusterId required");
        }

        @Test
        @DisplayName("Should reject negative under-replicated partitions")
        void testConstructor_NegativeUnderReplicated() {
            assertThatThrownBy(() ->
                createMetrics(-1, 0, 1)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("underReplicatedPartitions must be >= 0");
        }

        @Test
        @DisplayName("Should reject negative offline partitions")
        void testConstructor_NegativeOffline() {
            assertThatThrownBy(() ->
                createMetrics(0, -1, 1)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("offlinePartitions must be >= 0");
        }

        @Test
        @DisplayName("Should reject negative controller count")
        void testConstructor_NegativeController() {
            assertThatThrownBy(() ->
                createMetrics(0, 0, -1)
            ).isInstanceOf(IllegalArgumentException.class)
             .hasMessageContaining("activeControllerCount must be >= 0");
        }
    }

    // ========================================================================
    // Partition State Tests
    // ========================================================================

    @Nested
    @DisplayName("Partition State")
    class PartitionStateTests {

        @Test
        @DisplayName("Should detect under-replicated partitions")
        void testPartitions_UnderReplicated() {
            ReplicationHealthMetrics metrics = createMetrics(5, 0, 1);
            assertThat(metrics.hasUnderReplicatedPartitions()).isTrue();
        }

        @Test
        @DisplayName("Should detect no under-replicated partitions")
        void testPartitions_FullyReplicated() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 1);
            assertThat(metrics.hasUnderReplicatedPartitions()).isFalse();
        }

        @Test
        @DisplayName("Should detect offline partitions")
        void testPartitions_Offline() {
            ReplicationHealthMetrics metrics = createMetrics(0, 3, 1);
            assertThat(metrics.hasOfflinePartitions()).isTrue();
        }

        @Test
        @DisplayName("Should detect no offline partitions")
        void testPartitions_AllOnline() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 1);
            assertThat(metrics.hasOfflinePartitions()).isFalse();
        }
    }

    // ========================================================================
    // Controller State Tests
    // ========================================================================

    @Nested
    @DisplayName("Controller State")
    class ControllerStateTests {

        @Test
        @DisplayName("Should detect active controller (count = 1)")
        void testController_Active() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 1);
            assertThat(metrics.hasActiveController()).isTrue();
        }

        @Test
        @DisplayName("Should detect no active controller (count = 0)")
        void testController_Inactive() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 0);
            assertThat(metrics.hasActiveController()).isFalse();
        }

        @Test
        @DisplayName("Should handle controller count > 1 (split-brain scenario)")
        void testController_Multiple() {
            // Theoretically impossible, but if it occurs, cluster has a controller
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 2);
            // hasActiveController checks == 1, so this returns false
            assertThat(metrics.hasActiveController()).isFalse();
        }
    }

    // ========================================================================
    // Health State Tests - Healthy
    // ========================================================================

    @Nested
    @DisplayName("Health State - Healthy")
    class HealthyStateTests {

        @Test
        @DisplayName("Should be healthy when all conditions met")
        void testHealthy_AllConditionsMet() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 1);

            assertThat(metrics.isHealthy()).isTrue();
            assertThat(metrics.isDegraded()).isFalse();
            assertThat(metrics.isDefective()).isFalse();
        }

        @Test
        @DisplayName("Should not be healthy with under-replicated partitions")
        void testHealthy_UnderReplicated() {
            ReplicationHealthMetrics metrics = createMetrics(1, 0, 1);

            assertThat(metrics.isHealthy()).isFalse();
        }

        @Test
        @DisplayName("Should not be healthy with offline partitions")
        void testHealthy_Offline() {
            ReplicationHealthMetrics metrics = createMetrics(0, 1, 1);

            assertThat(metrics.isHealthy()).isFalse();
        }

        @Test
        @DisplayName("Should not be healthy without active controller")
        void testHealthy_NoController() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 0);

            assertThat(metrics.isHealthy()).isFalse();
        }
    }

    // ========================================================================
    // Health State Tests - Degraded
    // ========================================================================

    @Nested
    @DisplayName("Health State - Degraded")
    class DegradedStateTests {

        @Test
        @DisplayName("Should be degraded with under-replicated but no offline")
        void testDegraded_UnderReplicatedOnly() {
            ReplicationHealthMetrics metrics = createMetrics(5, 0, 1);

            assertThat(metrics.isDegraded()).isTrue();
            assertThat(metrics.isHealthy()).isFalse();
            assertThat(metrics.isDefective()).isFalse();
        }

        @Test
        @DisplayName("Should not be degraded when healthy")
        void testDegraded_Healthy() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 1);

            assertThat(metrics.isDegraded()).isFalse();
        }

        @Test
        @DisplayName("Should not be degraded with offline partitions")
        void testDegraded_OfflinePresent() {
            // Defective takes precedence over degraded
            ReplicationHealthMetrics metrics = createMetrics(5, 1, 1);

            assertThat(metrics.isDegraded()).isFalse();
            assertThat(metrics.isDefective()).isTrue();
        }

        @Test
        @DisplayName("Should not be degraded without active controller")
        void testDegraded_NoController() {
            // Defective (no controller) takes precedence
            ReplicationHealthMetrics metrics = createMetrics(5, 0, 0);

            assertThat(metrics.isDegraded()).isFalse();
            assertThat(metrics.isDefective()).isTrue();
        }
    }

    // ========================================================================
    // Health State Tests - Defective
    // ========================================================================

    @Nested
    @DisplayName("Health State - Defective")
    class DefectiveStateTests {

        @Test
        @DisplayName("Should be defective with offline partitions")
        void testDefective_OfflinePartitions() {
            ReplicationHealthMetrics metrics = createMetrics(0, 2, 1);

            assertThat(metrics.isDefective()).isTrue();
            assertThat(metrics.isHealthy()).isFalse();
            assertThat(metrics.isDegraded()).isFalse();
        }

        @Test
        @DisplayName("Should be defective without active controller")
        void testDefective_NoController() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 0);

            assertThat(metrics.isDefective()).isTrue();
            assertThat(metrics.isHealthy()).isFalse();
        }

        @Test
        @DisplayName("Should be defective with both offline and no controller")
        void testDefective_WorstCase() {
            ReplicationHealthMetrics metrics = createMetrics(0, 5, 0);

            assertThat(metrics.isDefective()).isTrue();
        }

        @Test
        @DisplayName("Should be defective with offline even if under-replicated")
        void testDefective_OfflineAndUnderReplicated() {
            ReplicationHealthMetrics metrics = createMetrics(10, 2, 1);

            assertThat(metrics.isDefective()).isTrue();
            assertThat(metrics.isDegraded()).isFalse(); // Defective takes precedence
        }

        @Test
        @DisplayName("Should not be defective when healthy")
        void testDefective_Healthy() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 1);

            assertThat(metrics.isDefective()).isFalse();
        }

        @Test
        @DisplayName("Should not be defective when only degraded")
        void testDefective_OnlyDegraded() {
            ReplicationHealthMetrics metrics = createMetrics(5, 0, 1);

            assertThat(metrics.isDefective()).isFalse();
        }
    }

    // ========================================================================
    // Health State Transition Tests
    // ========================================================================

    @Nested
    @DisplayName("Health State Transitions")
    class StateTransitionTests {

        @Test
        @DisplayName("Transition: Healthy → Degraded")
        void testTransition_HealthyToDegraded() {
            ReplicationHealthMetrics healthy = createMetrics(0, 0, 1);
            ReplicationHealthMetrics degraded = createMetrics(5, 0, 1);

            assertThat(healthy.isHealthy()).isTrue();
            assertThat(degraded.isDegraded()).isTrue();
        }

        @Test
        @DisplayName("Transition: Degraded → Defective")
        void testTransition_DegradedToDefective() {
            ReplicationHealthMetrics degraded = createMetrics(5, 0, 1);
            ReplicationHealthMetrics defective = createMetrics(5, 2, 1);

            assertThat(degraded.isDegraded()).isTrue();
            assertThat(defective.isDefective()).isTrue();
        }

        @Test
        @DisplayName("Transition: Defective → Degraded (recovery)")
        void testTransition_DefectiveToDegraded() {
            ReplicationHealthMetrics defective = createMetrics(5, 2, 1);
            ReplicationHealthMetrics degraded = createMetrics(5, 0, 1);

            assertThat(defective.isDefective()).isTrue();
            assertThat(degraded.isDegraded()).isTrue();
        }

        @Test
        @DisplayName("Transition: Degraded → Healthy (recovery)")
        void testTransition_DegradedToHealthy() {
            ReplicationHealthMetrics degraded = createMetrics(5, 0, 1);
            ReplicationHealthMetrics healthy = createMetrics(0, 0, 1);

            assertThat(degraded.isDegraded()).isTrue();
            assertThat(healthy.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("Transition: Healthy → Defective (direct)")
        void testTransition_HealthyToDefective() {
            ReplicationHealthMetrics healthy = createMetrics(0, 0, 1);
            ReplicationHealthMetrics defective = createMetrics(0, 1, 1);

            assertThat(healthy.isHealthy()).isTrue();
            assertThat(defective.isDefective()).isTrue();
        }
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle zero partitions")
        void testEdgeCase_ZeroPartitions() {
            ReplicationHealthMetrics metrics = createMetrics(0, 0, 1);

            assertThat(metrics.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("Should handle large partition counts")
        void testEdgeCase_LargePartitionCounts() {
            ReplicationHealthMetrics metrics = createMetrics(10000, 500, 1);

            assertThat(metrics.isDefective()).isTrue(); // Offline > 0
            assertThat(metrics.hasUnderReplicatedPartitions()).isTrue();
        }

        @Test
        @DisplayName("Should handle boundary at exactly 1 under-replicated")
        void testEdgeCase_OneUnderReplicated() {
            ReplicationHealthMetrics metrics = createMetrics(1, 0, 1);

            assertThat(metrics.isDegraded()).isTrue();
        }

        @Test
        @DisplayName("Should handle boundary at exactly 1 offline")
        void testEdgeCase_OneOffline() {
            ReplicationHealthMetrics metrics = createMetrics(0, 1, 1);

            assertThat(metrics.isDefective()).isTrue();
        }
    }
}
