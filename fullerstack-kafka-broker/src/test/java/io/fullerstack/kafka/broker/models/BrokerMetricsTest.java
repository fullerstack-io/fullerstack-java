package io.fullerstack.kafka.broker.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for BrokerMetrics record.
 */
class BrokerMetricsTest {

    @Test
    void shouldCreateValidBrokerMetrics() {
        // Given
        long timestamp = System.currentTimeMillis();

        // When
        BrokerMetrics metrics = new BrokerMetrics(
                "broker-1",
                1_000_000_000L,  // heapUsed
                2_000_000_000L,  // heapMax
                0.5,             // cpuUsage
                100L,            // requestRate
                1000L,           // byteInRate
                2000L,           // byteOutRate
                1,               // activeControllers
                0,               // underReplicatedPartitions
                0,               // offlinePartitionsCount
                50L,             // networkProcessorAvgIdlePercent
                60L,             // requestHandlerAvgIdlePercent
                10L,             // fetchConsumerTotalTimeMs
                5L,              // produceTotalTimeMs
                timestamp
        );

        // Then
        assertThat(metrics).isNotNull();
        assertThat(metrics.brokerId()).isEqualTo("broker-1");
        assertThat(metrics.heapUsed()).isEqualTo(1_000_000_000L);
        assertThat(metrics.heapMax()).isEqualTo(2_000_000_000L);
        assertThat(metrics.cpuUsage()).isEqualTo(0.5);
        assertThat(metrics.requestRate()).isEqualTo(100L);
        assertThat(metrics.byteInRate()).isEqualTo(1000L);
        assertThat(metrics.byteOutRate()).isEqualTo(2000L);
        assertThat(metrics.activeControllers()).isEqualTo(1);
        assertThat(metrics.underReplicatedPartitions()).isEqualTo(0);
        assertThat(metrics.offlinePartitionsCount()).isEqualTo(0);
        assertThat(metrics.networkProcessorAvgIdlePercent()).isEqualTo(50L);
        assertThat(metrics.requestHandlerAvgIdlePercent()).isEqualTo(60L);
        assertThat(metrics.fetchConsumerTotalTimeMs()).isEqualTo(10L);
        assertThat(metrics.produceTotalTimeMs()).isEqualTo(5L);
        assertThat(metrics.timestamp()).isEqualTo(timestamp);
    }

    @Test
    void shouldRejectNullBrokerId() {
        assertThatThrownBy(() -> new BrokerMetrics(
                null,
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("brokerId must not be null or blank");
    }

    @Test
    void shouldRejectBlankBrokerId() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "   ",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("brokerId must not be null or blank");
    }

    @Test
    void shouldRejectNegativeHeapUsed() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                -1L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heapUsed must be non-negative");
    }

    @Test
    void shouldRejectNegativeHeapMax() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                -1L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heapMax must be non-negative");
    }

    @Test
    void shouldRejectHeapUsedGreaterThanHeapMax() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                3_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heapUsed")
                .hasMessageContaining("cannot exceed heapMax");
    }

    @Test
    void shouldRejectCpuUsageBelowZero() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                -0.1,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cpuUsage must be in range [0.0, 1.0]");
    }

    @Test
    void shouldRejectCpuUsageAboveOne() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                1.1,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cpuUsage must be in range [0.0, 1.0]");
    }

    @Test
    void shouldRejectNegativeRequestRate() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                -1L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestRate must be non-negative");
    }

    @Test
    void shouldRejectNegativeByteInRate() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                -1L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byteInRate must be non-negative");
    }

    @Test
    void shouldRejectNegativeByteOutRate() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                -1L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byteOutRate must be non-negative");
    }

    @Test
    void shouldRejectActiveControllersLessThanZero() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                -1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeControllers must be 0 or 1");
    }

    @Test
    void shouldRejectActiveControllersGreaterThanOne() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                2,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeControllers must be 0 or 1");
    }

    @Test
    void shouldRejectNegativeUnderReplicatedPartitions() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                -1,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("underReplicatedPartitions must be non-negative");
    }

    @Test
    void shouldRejectNegativeOfflinePartitionsCount() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                -1,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offlinePartitionsCount must be non-negative");
    }

    @Test
    void shouldRejectNetworkProcessorIdleBelowZero() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                -1L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("networkProcessorAvgIdlePercent must be in range [0, 100]");
    }

    @Test
    void shouldRejectNetworkProcessorIdleAbove100() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                101L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("networkProcessorAvgIdlePercent must be in range [0, 100]");
    }

    @Test
    void shouldRejectRequestHandlerIdleBelowZero() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                -1L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestHandlerAvgIdlePercent must be in range [0, 100]");
    }

    @Test
    void shouldRejectRequestHandlerIdleAbove100() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                101L,
                10L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestHandlerAvgIdlePercent must be in range [0, 100]");
    }

    @Test
    void shouldRejectNegativeFetchConsumerTime() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                -1L,
                5L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fetchConsumerTotalTimeMs must be non-negative");
    }

    @Test
    void shouldRejectNegativeProduceTotalTime() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                -1L,
                System.currentTimeMillis()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("produceTotalTimeMs must be non-negative");
    }

    @Test
    void shouldRejectZeroTimestamp() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                0L
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp must be positive");
    }

    @Test
    void shouldRejectNegativeTimestamp() {
        assertThatThrownBy(() -> new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                -1L
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp must be positive");
    }

    @Test
    void shouldCalculateHeapUsagePercent() {
        // Given
        BrokerMetrics metrics = new BrokerMetrics(
                "broker-1",
                1_500_000_000L,  // heapUsed = 1.5GB
                2_000_000_000L,  // heapMax = 2GB
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        );

        // When
        double heapPercent = metrics.heapUsagePercent();

        // Then
        assertThat(heapPercent).isEqualTo(75.0);
    }

    @Test
    void shouldCalculateHeapUsagePercentWhenMaxIsZero() {
        // Given
        BrokerMetrics metrics = new BrokerMetrics(
                "broker-1",
                0L,
                0L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        );

        // When
        double heapPercent = metrics.heapUsagePercent();

        // Then
        assertThat(heapPercent).isEqualTo(0.0);
    }

    @Test
    void shouldCalculateCpuUsagePercent() {
        // Given
        BrokerMetrics metrics = new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.75,  // 75% CPU
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        );

        // When
        double cpuPercent = metrics.cpuUsagePercent();

        // Then
        assertThat(cpuPercent).isEqualTo(75.0);
    }

    @Test
    void shouldDetermineFreshnessWhenFresh() {
        // Given - metrics collected just now
        BrokerMetrics metrics = new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                System.currentTimeMillis()
        );

        // When/Then - 60 second threshold
        assertThat(metrics.isFresh(60_000)).isTrue();
    }

    @Test
    void shouldDetermineFreshnessWhenStale() {
        // Given - metrics collected 2 minutes ago
        long twoMinutesAgo = System.currentTimeMillis() - (2 * 60 * 1000);
        BrokerMetrics metrics = new BrokerMetrics(
                "broker-1",
                1_000_000_000L,
                2_000_000_000L,
                0.5,
                100L,
                1000L,
                2000L,
                1,
                0,
                0,
                50L,
                60L,
                10L,
                5L,
                twoMinutesAgo
        );

        // When/Then - 60 second threshold
        assertThat(metrics.isFresh(60_000)).isFalse();
    }
}
