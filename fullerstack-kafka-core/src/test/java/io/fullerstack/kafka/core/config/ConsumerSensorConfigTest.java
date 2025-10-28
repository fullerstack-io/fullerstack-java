package io.fullerstack.kafka.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerSensorConfigTest {

    private static final ConsumerEndpoint ENDPOINT_1 = new ConsumerEndpoint(
            "consumer-1",
            "test-group",
            "http://localhost:9999/metrics"
    );

    private static final ConsumerEndpoint ENDPOINT_2 = new ConsumerEndpoint(
            "consumer-2",
            "test-group",
            "http://localhost:9998/metrics"
    );

    @Test
    void shouldCreateValidConfig() {
        // when
        ConsumerSensorConfig config = new ConsumerSensorConfig(
                "localhost:9092",
                List.of(ENDPOINT_1, ENDPOINT_2),
                30_000L,
                JmxConnectionPoolConfig.withPoolingEnabled()
        );

        // then
        assertThat(config.bootstrapServers()).isEqualTo("localhost:9092");
        assertThat(config.endpoints()).hasSize(2);
        assertThat(config.collectionIntervalMs()).isEqualTo(30_000L);
        assertThat(config.jmxPoolConfig()).isNotNull();
    }

    @Test
    void shouldCreateDefaultConfig() {
        // when
        ConsumerSensorConfig config = ConsumerSensorConfig.defaults(
                "localhost:9092",
                List.of(ENDPOINT_1)
        );

        // then
        assertThat(config.bootstrapServers()).isEqualTo("localhost:9092");
        assertThat(config.endpoints()).hasSize(1);
        assertThat(config.collectionIntervalMs()).isEqualTo(30_000L);
        assertThat(config.jmxPoolConfig().enabled()).isTrue();
    }

    @Test
    void shouldRejectNullBootstrapServers() {
        assertThatThrownBy(() ->
                new ConsumerSensorConfig(
                        null,
                        List.of(ENDPOINT_1),
                        30_000L,
                        JmxConnectionPoolConfig.withPoolingEnabled()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("bootstrapServers cannot be null");
    }

    @Test
    void shouldRejectBlankBootstrapServers() {
        assertThatThrownBy(() ->
                new ConsumerSensorConfig(
                        "  ",
                        List.of(ENDPOINT_1),
                        30_000L,
                        JmxConnectionPoolConfig.withPoolingEnabled()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootstrapServers cannot be blank");
    }

    @Test
    void shouldRejectNullEndpoints() {
        assertThatThrownBy(() ->
                new ConsumerSensorConfig(
                        "localhost:9092",
                        null,
                        30_000L,
                        JmxConnectionPoolConfig.withPoolingEnabled()
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("endpoints cannot be null");
    }

    @Test
    void shouldRejectEmptyEndpoints() {
        assertThatThrownBy(() ->
                new ConsumerSensorConfig(
                        "localhost:9092",
                        List.of(),
                        30_000L,
                        JmxConnectionPoolConfig.withPoolingEnabled()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoints cannot be empty");
    }

    @Test
    void shouldRejectInvalidCollectionInterval() {
        assertThatThrownBy(() ->
                new ConsumerSensorConfig(
                        "localhost:9092",
                        List.of(ENDPOINT_1),
                        0L,
                        JmxConnectionPoolConfig.withPoolingEnabled()
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionIntervalMs must be > 0");
    }

    @Test
    void shouldRejectNullJmxPoolConfig() {
        assertThatThrownBy(() ->
                new ConsumerSensorConfig(
                        "localhost:9092",
                        List.of(ENDPOINT_1),
                        30_000L,
                        null
                )
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("jmxPoolConfig cannot be null");
    }
}
