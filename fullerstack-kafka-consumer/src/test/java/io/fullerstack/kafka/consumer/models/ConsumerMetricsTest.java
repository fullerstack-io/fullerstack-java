package io.fullerstack.kafka.consumer.models;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerMetricsTest {

    private static final Map<TopicPartition, Long> LAG_MAP = Map.of(
            new TopicPartition("test-topic", 0), 300L,
            new TopicPartition("test-topic", 1), 200L
    );

    @Test
    void shouldCreateValidConsumerMetrics() {
        // when
        ConsumerMetrics metrics = new ConsumerMetrics(
                "consumer-1",
                "test-group",
                500L,
                LAG_MAP,
                1000.0,
                10.0,
                50.0,
                5.0,
                80.0,
                2,
                System.currentTimeMillis() - 60000,
                2,
                System.currentTimeMillis()
        );

        // then
        assertThat(metrics.consumerId()).isEqualTo("consumer-1");
        assertThat(metrics.consumerGroup()).isEqualTo("test-group");
        assertThat(metrics.totalLag()).isEqualTo(500L);
        assertThat(metrics.lagByPartition()).hasSize(2);
        assertThat(metrics.fetchRate()).isEqualTo(1000.0);
        assertThat(metrics.assignedPartitionCount()).isEqualTo(2);
    }

    @Test
    void shouldIdentifyHealthyConsumer() {
        // given
        ConsumerMetrics healthy = new ConsumerMetrics(
                "consumer-1", "test-group",
                500L, LAG_MAP,
                1000.0, 10.0, 50.0, 5.0, 80.0,
                2, System.currentTimeMillis() - 60000, 2,
                System.currentTimeMillis()
        );

        // then
        assertThat(healthy.isHealthy()).isTrue();
    }

    @Test
    void shouldIdentifyUnhealthyConsumer_HighLag() {
        // given
        ConsumerMetrics unhealthy = new ConsumerMetrics(
                "consumer-1", "test-group",
                1500L, LAG_MAP,  // High lag
                1000.0, 10.0, 50.0, 5.0, 80.0,
                2, System.currentTimeMillis() - 60000, 2,
                System.currentTimeMillis()
        );

        // then
        assertThat(unhealthy.isHealthy()).isFalse();
    }

    @Test
    void shouldIdentifyUnhealthyConsumer_SlowProcessing() {
        // given
        ConsumerMetrics unhealthy = new ConsumerMetrics(
                "consumer-1", "test-group",
                500L, LAG_MAP,
                1000.0, 10.0, 50.0, 5.0, 150.0,  // Slow processing
                2, System.currentTimeMillis() - 60000, 2,
                System.currentTimeMillis()
        );

        // then
        assertThat(unhealthy.isHealthy()).isFalse();
    }

    @Test
    void shouldIdentifyUnhealthyConsumer_ManyRebalances() {
        // given
        ConsumerMetrics unhealthy = new ConsumerMetrics(
                "consumer-1", "test-group",
                500L, LAG_MAP,
                1000.0, 10.0, 50.0, 5.0, 80.0,
                2, System.currentTimeMillis() - 60000, 10,  // Many rebalances
                System.currentTimeMillis()
        );

        // then
        assertThat(unhealthy.isHealthy()).isFalse();
    }

    @Test
    void shouldDetectLag() {
        // given
        ConsumerMetrics withLag = new ConsumerMetrics(
                "consumer-1", "test-group",
                100L, LAG_MAP,
                1000.0, 10.0, 50.0, 5.0, 80.0,
                2, System.currentTimeMillis() - 60000, 2,
                System.currentTimeMillis()
        );

        ConsumerMetrics noLag = new ConsumerMetrics(
                "consumer-1", "test-group",
                0L, Map.of(),
                1000.0, 10.0, 50.0, 5.0, 80.0,
                0, System.currentTimeMillis() - 60000, 2,
                System.currentTimeMillis()
        );

        // then
        assertThat(withLag.hasLag()).isTrue();
        assertThat(noLag.hasLag()).isFalse();
    }

    @Test
    void shouldCalculateAvgLagPerPartition() {
        // given
        ConsumerMetrics metrics = new ConsumerMetrics(
                "consumer-1", "test-group",
                500L, LAG_MAP,
                1000.0, 10.0, 50.0, 5.0, 80.0,
                2,  // 2 partitions
                System.currentTimeMillis() - 60000, 2,
                System.currentTimeMillis()
        );

        // when/then
        assertThat(metrics.avgLagPerPartition()).isEqualTo(250.0);  // 500 / 2
    }

    @Test
    void shouldHandleZeroPartitionsInAvgLagCalculation() {
        // given
        ConsumerMetrics metrics = new ConsumerMetrics(
                "consumer-1", "test-group",
                0L, Map.of(),
                1000.0, 10.0, 50.0, 5.0, 80.0,
                0,  // No partitions
                System.currentTimeMillis() - 60000, 2,
                System.currentTimeMillis()
        );

        // when/then
        assertThat(metrics.avgLagPerPartition()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectNullConsumerId() {
        assertThatThrownBy(() ->
                new ConsumerMetrics(
                        null, "test-group",
                        500L, LAG_MAP,
                        1000.0, 10.0, 50.0, 5.0, 80.0,
                        2, System.currentTimeMillis(), 2,
                        System.currentTimeMillis()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("consumerId cannot be null");
    }

    @Test
    void shouldRejectBlankConsumerId() {
        assertThatThrownBy(() ->
                new ConsumerMetrics(
                        "  ", "test-group",
                        500L, LAG_MAP,
                        1000.0, 10.0, 50.0, 5.0, 80.0,
                        2, System.currentTimeMillis(), 2,
                        System.currentTimeMillis()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumerId cannot be blank");
    }

    @Test
    void shouldRejectNegativeTotalLag() {
        assertThatThrownBy(() ->
                new ConsumerMetrics(
                        "consumer-1", "test-group",
                        -100L, LAG_MAP,
                        1000.0, 10.0, 50.0, 5.0, 80.0,
                        2, System.currentTimeMillis(), 2,
                        System.currentTimeMillis()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalLag must be >= 0");
    }

    @Test
    void shouldRejectNegativeFetchRate() {
        assertThatThrownBy(() ->
                new ConsumerMetrics(
                        "consumer-1", "test-group",
                        500L, LAG_MAP,
                        -10.0, 10.0, 50.0, 5.0, 80.0,
                        2, System.currentTimeMillis(), 2,
                        System.currentTimeMillis()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fetchRate must be >= 0");
    }
}
