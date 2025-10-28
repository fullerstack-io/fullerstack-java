package io.fullerstack.kafka.producer.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ProducerMetrics} record validation and helper methods.
 */
class ProducerMetricsTest {

    @Test
    void testValidMetrics_Success() {
        // Arrange & Act
        ProducerMetrics metrics = new ProducerMetrics(
            "test-producer",
            1000,      // sendRate
            25.5,      // avgLatencyMs
            45.0,      // p99LatencyMs
            50,        // batchSizeAvg
            0.65,      // compressionRatio
            800_000,   // bufferAvailableBytes
            1_000_000, // bufferTotalBytes
            5,         // ioWaitRatio
            0,         // recordErrorRate
            System.currentTimeMillis()
        );

        // Assert
        assertThat(metrics.producerId()).isEqualTo("test-producer");
        assertThat(metrics.sendRate()).isEqualTo(1000);
        assertThat(metrics.avgLatencyMs()).isEqualTo(25.5);
        assertThat(metrics.isHealthy()).isTrue();
    }

    @Test
    void testValidation_NullProducerId() {
        assertThatThrownBy(() ->
            new ProducerMetrics(
                null,
                1000, 25.5, 45.0, 50, 0.65,
                800_000, 1_000_000, 5, 0,
                System.currentTimeMillis()
            )
        )
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("producerId cannot be null");
    }

    @Test
    void testValidation_BlankProducerId() {
        assertThatThrownBy(() ->
            new ProducerMetrics(
                "   ",
                1000, 25.5, 45.0, 50, 0.65,
                800_000, 1_000_000, 5, 0,
                System.currentTimeMillis()
            )
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("producerId cannot be blank");
    }

    @Test
    void testValidation_NegativeSendRate() {
        assertThatThrownBy(() ->
            new ProducerMetrics(
                "producer-1",
                -10,  // Invalid: negative
                25.5, 45.0, 50, 0.65,
                800_000, 1_000_000, 5, 0,
                System.currentTimeMillis()
            )
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sendRate must be >= 0");
    }

    @Test
    void testValidation_NegativeAvgLatency() {
        assertThatThrownBy(() ->
            new ProducerMetrics(
                "producer-1",
                1000,
                -5.0,  // Invalid: negative
                45.0, 50, 0.65,
                800_000, 1_000_000, 5, 0,
                System.currentTimeMillis()
            )
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("avgLatencyMs must be >= 0");
    }

    @Test
    void testValidation_NegativeP99Latency() {
        assertThatThrownBy(() ->
            new ProducerMetrics(
                "producer-1",
                1000, 25.5,
                -10.0,  // Invalid: negative
                50, 0.65,
                800_000, 1_000_000, 5, 0,
                System.currentTimeMillis()
            )
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("p99LatencyMs must be >= 0");
    }

    @Test
    void testValidation_CompressionRatioBelowZero() {
        assertThatThrownBy(() ->
            new ProducerMetrics(
                "producer-1",
                1000, 25.5, 45.0, 50,
                -0.1,  // Invalid: below 0.0
                800_000, 1_000_000, 5, 0,
                System.currentTimeMillis()
            )
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("compressionRatio must be 0.0-1.0");
    }

    @Test
    void testValidation_CompressionRatioAboveOne() {
        assertThatThrownBy(() ->
            new ProducerMetrics(
                "producer-1",
                1000, 25.5, 45.0, 50,
                1.1,  // Invalid: above 1.0
                800_000, 1_000_000, 5, 0,
                System.currentTimeMillis()
            )
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("compressionRatio must be 0.0-1.0");
    }

    @Test
    void testValidation_BufferAvailableExceedsTotal() {
        assertThatThrownBy(() ->
            new ProducerMetrics(
                "producer-1",
                1000, 25.5, 45.0, 50, 0.65,
                1_100_000,  // Available exceeds total
                1_000_000,  // Total
                5, 0,
                System.currentTimeMillis()
            )
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bufferAvailableBytes")
        .hasMessageContaining("cannot exceed bufferTotalBytes");
    }

    @Test
    void testValidation_InvalidIoWaitRatio() {
        assertThatThrownBy(() ->
            new ProducerMetrics(
                "producer-1",
                1000, 25.5, 45.0, 50, 0.65,
                800_000, 1_000_000,
                150,  // Invalid: above 100
                0,
                System.currentTimeMillis()
            )
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ioWaitRatio must be 0-100");
    }

    @Test
    void testBufferUtilization_LowUsage() {
        // Arrange
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000, 25.5, 45.0, 50, 0.65,
            800_000,   // 800KB available
            1_000_000, // 1MB total
            5, 0,
            System.currentTimeMillis()
        );

        // Act & Assert
        assertThat(metrics.bufferUtilization()).isCloseTo(0.2, within(0.01));  // 20% utilized
    }

    @Test
    void testBufferUtilization_HighUsage() {
        // Arrange
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000, 25.5, 45.0, 50, 0.65,
            50_000,    // 50KB available
            1_000_000, // 1MB total
            5, 0,
            System.currentTimeMillis()
        );

        // Act & Assert
        assertThat(metrics.bufferUtilization()).isCloseTo(0.95, within(0.01));  // 95% utilized
    }

    @Test
    void testBufferUtilization_FullBuffer() {
        // Arrange
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000, 25.5, 45.0, 50, 0.65,
            0,         // 0 bytes available
            1_000_000, // 1MB total
            5, 0,
            System.currentTimeMillis()
        );

        // Act & Assert
        assertThat(metrics.bufferUtilization()).isEqualTo(1.0);  // 100% utilized
    }

    @Test
    void testIsHealthy_AllConditionsMet() {
        // Arrange
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000,
            50.0,      // < 100ms (healthy)
            75.0,
            50, 0.65,
            500_000,   // 50% buffer utilization (healthy)
            1_000_000,
            5,
            0,         // No errors (healthy)
            System.currentTimeMillis()
        );

        // Act & Assert
        assertThat(metrics.isHealthy()).isTrue();
    }

    @Test
    void testIsHealthy_HighLatency() {
        // Arrange
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000,
            150.0,     // > 100ms (unhealthy)
            200.0,
            50, 0.65,
            500_000,
            1_000_000,
            5, 0,
            System.currentTimeMillis()
        );

        // Act & Assert
        assertThat(metrics.isHealthy()).isFalse();
    }

    @Test
    void testIsHealthy_HighBufferUtilization() {
        // Arrange
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000, 50.0, 75.0, 50, 0.65,
            50_000,    // 95% buffer utilization (unhealthy)
            1_000_000,
            5, 0,
            System.currentTimeMillis()
        );

        // Act & Assert
        assertThat(metrics.isHealthy()).isFalse();
    }

    @Test
    void testIsHealthy_RecordErrors() {
        // Arrange
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000, 50.0, 75.0, 50, 0.65,
            500_000,
            1_000_000,
            5,
            10,  // Errors present (unhealthy)
            System.currentTimeMillis()
        );

        // Act & Assert
        assertThat(metrics.isHealthy()).isFalse();
    }

    @Test
    void testAgeMs_Fresh() {
        // Arrange
        long now = System.currentTimeMillis();
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000, 25.5, 45.0, 50, 0.65,
            800_000, 1_000_000, 5, 0,
            now
        );

        // Act & Assert
        assertThat(metrics.ageMs()).isLessThan(100);  // Should be very recent
    }

    @Test
    void testIsFresh_RecentMetrics() {
        // Arrange
        long now = System.currentTimeMillis();
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000, 25.5, 45.0, 50, 0.65,
            800_000, 1_000_000, 5, 0,
            now
        );

        // Act & Assert
        assertThat(metrics.isFresh()).isTrue();
    }

    @Test
    void testIsFresh_StaleMetrics() {
        // Arrange
        long tenSecondsAgo = System.currentTimeMillis() - 10_000;
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000, 25.5, 45.0, 50, 0.65,
            800_000, 1_000_000, 5, 0,
            tenSecondsAgo
        );

        // Act & Assert
        assertThat(metrics.isFresh()).isFalse();
        assertThat(metrics.isRecent()).isTrue();
    }

    @Test
    void testIsRecent_VeryStaleMetrics() {
        // Arrange
        long oneMinuteAgo = System.currentTimeMillis() - 60_000;
        ProducerMetrics metrics = new ProducerMetrics(
            "producer-1",
            1000, 25.5, 45.0, 50, 0.65,
            800_000, 1_000_000, 5, 0,
            oneMinuteAgo
        );

        // Act & Assert
        assertThat(metrics.isRecent()).isFalse();
    }

    @Test
    void testZeroValues_ValidCase() {
        // Arrange & Act - Producer with no activity is valid
        ProducerMetrics metrics = new ProducerMetrics(
            "idle-producer",
            0,    // No sends
            0.0,  // No latency
            0.0,  // No P99
            0,    // No batches
            0.0,  // No compression
            1_000_000,  // Full buffer available
            1_000_000,
            0,    // No IO wait
            0,    // No errors
            System.currentTimeMillis()
        );

        // Assert
        assertThat(metrics.sendRate()).isEqualTo(0);
        assertThat(metrics.bufferUtilization()).isEqualTo(0.0);
        assertThat(metrics.isHealthy()).isTrue();
    }
}
