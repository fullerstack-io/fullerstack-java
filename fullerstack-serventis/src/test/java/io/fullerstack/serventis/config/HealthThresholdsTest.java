package io.fullerstack.serventis.config;

import io.fullerstack.substrates.config.HierarchicalConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link HealthThresholds}.
 * <p>
 * Coverage:
 * - Default values (match hardcoded constants)
 * - fromConfig() factory method
 * - Validation: stable &lt; degraded
 * - Validation: range [0.0, 1.0]
 * - Edge cases: 0.0, 1.0, boundary values
 */
class HealthThresholdsTest {

    @Test
    void testWithDefaults_ReturnsCorrectValues() {
        HealthThresholds defaults = HealthThresholds.withDefaults();

        assertThat(defaults.heapStable()).isEqualTo(0.75);
        assertThat(defaults.heapDegraded()).isEqualTo(0.90);
        assertThat(defaults.cpuStable()).isEqualTo(0.70);
        assertThat(defaults.cpuDegraded()).isEqualTo(0.85);
    }

    @Test
    void testFromConfig_UsesDefaultValues() {
        HierarchicalConfig config = HierarchicalConfig.global();
        HealthThresholds thresholds = HealthThresholds.fromConfig(config);

        assertThat(thresholds.heapStable()).isEqualTo(0.75);
        assertThat(thresholds.heapDegraded()).isEqualTo(0.90);
        assertThat(thresholds.cpuStable()).isEqualTo(0.70);
        assertThat(thresholds.cpuDegraded()).isEqualTo(0.85);
    }

    @Test
    void testFromConfig_MatchesWithDefaults() {
        HierarchicalConfig config = HierarchicalConfig.global();
        HealthThresholds fromConfig = HealthThresholds.fromConfig(config);
        HealthThresholds fromDefaults = HealthThresholds.withDefaults();

        assertThat(fromConfig).isEqualTo(fromDefaults);
    }

    @Test
    void testForCircuit_UsesGlobalDefaults() {
        // No circuit-specific properties file exists, should use global defaults
        HealthThresholds thresholds = HealthThresholds.forCircuit("nonexistent-circuit");

        assertThat(thresholds.heapStable()).isEqualTo(0.75);
        assertThat(thresholds.heapDegraded()).isEqualTo(0.90);
        assertThat(thresholds.cpuStable()).isEqualTo(0.70);
        assertThat(thresholds.cpuDegraded()).isEqualTo(0.85);
    }

    @Test
    void testForContainer_UsesGlobalDefaults() {
        // No container-specific properties file exists, should use global defaults
        HealthThresholds thresholds = HealthThresholds.forContainer("nonexistent-circuit", "nonexistent-container");

        assertThat(thresholds.heapStable()).isEqualTo(0.75);
        assertThat(thresholds.heapDegraded()).isEqualTo(0.90);
        assertThat(thresholds.cpuStable()).isEqualTo(0.70);
        assertThat(thresholds.cpuDegraded()).isEqualTo(0.85);
    }

    @Test
    void testValidThresholds_Success() {
        HealthThresholds thresholds = new HealthThresholds(0.60, 0.80, 0.65, 0.85);

        assertThat(thresholds.heapStable()).isEqualTo(0.60);
        assertThat(thresholds.heapDegraded()).isEqualTo(0.80);
        assertThat(thresholds.cpuStable()).isEqualTo(0.65);
        assertThat(thresholds.cpuDegraded()).isEqualTo(0.85);
    }

    @Test
    void testHeapStableEqualsHeapDegraded_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.75, 0.75, 0.70, 0.85))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("heapStable")
            .hasMessageContaining("must be <")
            .hasMessageContaining("heapDegraded");
    }

    @Test
    void testHeapStableGreaterThanHeapDegraded_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.90, 0.75, 0.70, 0.85))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("heapStable")
            .hasMessageContaining("must be <")
            .hasMessageContaining("heapDegraded");
    }

    @Test
    void testCpuStableEqualsCpuDegraded_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.75, 0.90, 0.80, 0.80))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cpuStable")
            .hasMessageContaining("must be <")
            .hasMessageContaining("cpuDegraded");
    }

    @Test
    void testCpuStableGreaterThanCpuDegraded_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.75, 0.90, 0.90, 0.80))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cpuStable")
            .hasMessageContaining("must be <")
            .hasMessageContaining("cpuDegraded");
    }

    @Test
    void testHeapStableNegative_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(-0.1, 0.90, 0.70, 0.85))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("heapStable")
            .hasMessageContaining("range [0.0, 1.0]");
    }

    @Test
    void testHeapStableAboveOne_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(1.1, 1.5, 0.70, 0.85))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("heapStable")
            .hasMessageContaining("range [0.0, 1.0]");
    }

    @Test
    void testHeapDegradedNegative_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.75, -0.1, 0.70, 0.85))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("heapDegraded")
            .hasMessageContaining("range [0.0, 1.0]");
    }

    @Test
    void testHeapDegradedAboveOne_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.75, 1.5, 0.70, 0.85))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("heapDegraded")
            .hasMessageContaining("range [0.0, 1.0]");
    }

    @Test
    void testCpuStableNegative_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.75, 0.90, -0.1, 0.85))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cpuStable")
            .hasMessageContaining("range [0.0, 1.0]");
    }

    @Test
    void testCpuStableAboveOne_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.75, 0.90, 1.1, 1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cpuStable")
            .hasMessageContaining("range [0.0, 1.0]");
    }

    @Test
    void testCpuDegradedNegative_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.75, 0.90, 0.70, -0.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cpuDegraded")
            .hasMessageContaining("range [0.0, 1.0]");
    }

    @Test
    void testCpuDegradedAboveOne_ThrowsException() {
        assertThatThrownBy(() -> new HealthThresholds(0.75, 0.90, 0.70, 1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cpuDegraded")
            .hasMessageContaining("range [0.0, 1.0]");
    }

    @Test
    void testBoundaryValues_ZeroStable_Success() {
        // Valid: 0.0 < 0.1
        HealthThresholds thresholds = new HealthThresholds(0.0, 0.1, 0.0, 0.1);

        assertThat(thresholds.heapStable()).isEqualTo(0.0);
        assertThat(thresholds.cpuStable()).isEqualTo(0.0);
    }

    @Test
    void testBoundaryValues_OneDegraded_Success() {
        // Valid: 0.9 < 1.0
        HealthThresholds thresholds = new HealthThresholds(0.9, 1.0, 0.9, 1.0);

        assertThat(thresholds.heapDegraded()).isEqualTo(1.0);
        assertThat(thresholds.cpuDegraded()).isEqualTo(1.0);
    }

    @Test
    void testBoundaryValues_MinimalDifference_Success() {
        // Valid: 0.75 < 0.750001
        HealthThresholds thresholds = new HealthThresholds(0.75, 0.750001, 0.70, 0.700001);

        assertThat(thresholds.heapStable()).isEqualTo(0.75);
        assertThat(thresholds.heapDegraded()).isEqualTo(0.750001);
        assertThat(thresholds.cpuStable()).isEqualTo(0.70);
        assertThat(thresholds.cpuDegraded()).isEqualTo(0.700001);
    }

    @Test
    void testHighThroughputCluster_CustomThresholds() {
        // Use case: High-throughput cluster tolerates higher heap
        HealthThresholds thresholds = new HealthThresholds(0.80, 0.95, 0.75, 0.90);

        assertThat(thresholds.heapStable()).isEqualTo(0.80);
        assertThat(thresholds.heapDegraded()).isEqualTo(0.95);
        assertThat(thresholds.cpuStable()).isEqualTo(0.75);
        assertThat(thresholds.cpuDegraded()).isEqualTo(0.90);
    }

    @Test
    void testMissionCriticalCluster_CustomThresholds() {
        // Use case: Mission-critical cluster with lower DEGRADED threshold
        HealthThresholds thresholds = new HealthThresholds(0.60, 0.75, 0.60, 0.75);

        assertThat(thresholds.heapStable()).isEqualTo(0.60);
        assertThat(thresholds.heapDegraded()).isEqualTo(0.75);
        assertThat(thresholds.cpuStable()).isEqualTo(0.60);
        assertThat(thresholds.cpuDegraded()).isEqualTo(0.75);
    }

    @Test
    void testDevelopmentCluster_RelaxedThresholds() {
        // Use case: Development cluster with relaxed CPU thresholds
        HealthThresholds thresholds = new HealthThresholds(0.75, 0.90, 0.80, 0.95);

        assertThat(thresholds.heapStable()).isEqualTo(0.75);
        assertThat(thresholds.heapDegraded()).isEqualTo(0.90);
        assertThat(thresholds.cpuStable()).isEqualTo(0.80);
        assertThat(thresholds.cpuDegraded()).isEqualTo(0.95);
    }
}
