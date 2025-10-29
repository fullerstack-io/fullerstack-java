package io.fullerstack.kafka.broker.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ThreadPoolMetrics}.
 */
class ThreadPoolMetricsTest {

    @Test
    void constructorValidatesRequiredFields() {
        assertThatThrownBy(() -> new ThreadPoolMetrics(
            null,  // null brokerId
            ThreadPoolType.NETWORK,
            3, 2, 1, 0.33, 0L, 0L, 0L, System.currentTimeMillis()
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("brokerId required");

        assertThatThrownBy(() -> new ThreadPoolMetrics(
            "broker-1",
            null,  // null poolType
            3, 2, 1, 0.33, 0L, 0L, 0L, System.currentTimeMillis()
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("poolType required");
    }

    @Test
    void constructorValidatesTotalThreadsNonNegative() {
        assertThatThrownBy(() -> new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            -1,  // negative totalThreads
            2, 1, 0.33, 0L, 0L, 0L, System.currentTimeMillis()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("totalThreads must be >= 0");
    }

    @Test
    void constructorValidatesActiveThreadsNonNegative() {
        assertThatThrownBy(() -> new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            3,
            -2,  // negative activeThreads
            1, 0.33, 0L, 0L, 0L, System.currentTimeMillis()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("activeThreads must be >= 0");
    }

    @Test
    void constructorValidatesAvgIdlePercentRange() {
        assertThatThrownBy(() -> new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            3, 2, 1,
            -0.1,  // negative avgIdlePercent
            0L, 0L, 0L, System.currentTimeMillis()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("avgIdlePercent must be 0.0-1.0");

        assertThatThrownBy(() -> new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            3, 2, 1,
            1.5,  // >1.0 avgIdlePercent
            0L, 0L, 0L, System.currentTimeMillis()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("avgIdlePercent must be 0.0-1.0");
    }

    @Test
    void isExhaustedReturnsTrueWhenIdleBelowTenPercent() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 9, 1,
            0.09,  // 9% idle
            0L, 0L, 0L, System.currentTimeMillis()
        );

        assertThat(metrics.isExhausted()).isTrue();
    }

    @Test
    void isExhaustedReturnsFalseWhenIdleAboveTenPercent() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 8, 2,
            0.20,  // 20% idle
            0L, 0L, 0L, System.currentTimeMillis()
        );

        assertThat(metrics.isExhausted()).isFalse();
    }

    @Test
    void isDegradedReturnsTrueWhenIdleBelowThirtyPercent() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.IO,
            10, 8, 2,
            0.20,  // 20% idle
            0L, 0L, 0L, System.currentTimeMillis()
        );

        assertThat(metrics.isDegraded()).isTrue();
    }

    @Test
    void isDegradedReturnsFalseWhenIdleAboveThirtyPercent() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.IO,
            10, 6, 4,
            0.40,  // 40% idle
            0L, 0L, 0L, System.currentTimeMillis()
        );

        assertThat(metrics.isDegraded()).isFalse();
    }

    @Test
    void isHealthyReturnsTrueWhenIdleAboveThirtyPercent() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 5, 5,
            0.50,  // 50% idle
            0L, 0L, 0L, System.currentTimeMillis()
        );

        assertThat(metrics.isHealthy()).isTrue();
    }

    @Test
    void isHealthyReturnsFalseWhenIdleBelowThirtyPercent() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            10, 8, 2,
            0.20,  // 20% idle
            0L, 0L, 0L, System.currentTimeMillis()
        );

        assertThat(metrics.isHealthy()).isFalse();
    }

    @Test
    void utilizationPercentCalculatesCorrectly() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.IO,
            8, 6, 2,
            0.25,
            0L, 0L, 0L, System.currentTimeMillis()
        );

        assertThat(metrics.utilizationPercent()).isEqualTo(0.75);  // 6/8 = 0.75
    }

    @Test
    void utilizationPercentHandlesZeroTotalThreads() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.LOG_CLEANER,
            0, 0, 0,
            0.0,
            0L, 0L, 0L, System.currentTimeMillis()
        );

        assertThat(metrics.utilizationPercent()).isEqualTo(0.0);
    }

    @Test
    void utilizationPercentHandlesFullUtilization() {
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-1",
            ThreadPoolType.NETWORK,
            3, 3, 0,
            0.0,  // 0% idle = 100% utilized
            10L, 1000L, 5L, System.currentTimeMillis()
        );

        assertThat(metrics.utilizationPercent()).isEqualTo(1.0);
        assertThat(metrics.isExhausted()).isTrue();
    }

    @Test
    void recordIncludesAllMetadata() {
        long now = System.currentTimeMillis();
        ThreadPoolMetrics metrics = new ThreadPoolMetrics(
            "broker-2",
            ThreadPoolType.IO,
            8, 5, 3,
            0.375,
            15L,    // queueSize
            12345L, // totalTasksProcessed
            7L,     // rejectionCount
            now
        );

        assertThat(metrics.brokerId()).isEqualTo("broker-2");
        assertThat(metrics.poolType()).isEqualTo(ThreadPoolType.IO);
        assertThat(metrics.totalThreads()).isEqualTo(8);
        assertThat(metrics.activeThreads()).isEqualTo(5);
        assertThat(metrics.idleThreads()).isEqualTo(3);
        assertThat(metrics.avgIdlePercent()).isEqualTo(0.375);
        assertThat(metrics.queueSize()).isEqualTo(15L);
        assertThat(metrics.totalTasksProcessed()).isEqualTo(12345L);
        assertThat(metrics.rejectionCount()).isEqualTo(7L);
        assertThat(metrics.timestamp()).isEqualTo(now);
    }
}
