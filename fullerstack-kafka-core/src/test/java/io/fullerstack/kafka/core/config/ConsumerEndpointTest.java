package io.fullerstack.kafka.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerEndpointTest {

    @Test
    void shouldCreateValidConsumerEndpoint() {
        // when
        ConsumerEndpoint endpoint = new ConsumerEndpoint(
                "consumer-1",
                "test-group",
                "http://localhost:9999/metrics"
        );

        // then
        assertThat(endpoint.consumerId()).isEqualTo("consumer-1");
        assertThat(endpoint.consumerGroup()).isEqualTo("test-group");
        assertThat(endpoint.jmxUrl()).isEqualTo("http://localhost:9999/metrics");
    }

    @Test
    void shouldRejectNullConsumerId() {
        assertThatThrownBy(() ->
                new ConsumerEndpoint(null, "test-group", "http://localhost:9999/metrics")
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("consumerId cannot be null");
    }

    @Test
    void shouldRejectBlankConsumerId() {
        assertThatThrownBy(() ->
                new ConsumerEndpoint("", "test-group", "http://localhost:9999/metrics")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumerId cannot be blank");
    }

    @Test
    void shouldRejectNullConsumerGroup() {
        assertThatThrownBy(() ->
                new ConsumerEndpoint("consumer-1", null, "http://localhost:9999/metrics")
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("consumerGroup cannot be null");
    }

    @Test
    void shouldRejectBlankConsumerGroup() {
        assertThatThrownBy(() ->
                new ConsumerEndpoint("consumer-1", "", "http://localhost:9999/metrics")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consumerGroup cannot be blank");
    }

    @Test
    void shouldRejectNullJmxUrl() {
        assertThatThrownBy(() ->
                new ConsumerEndpoint("consumer-1", "test-group", null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("jmxUrl cannot be null");
    }

    @Test
    void shouldRejectBlankJmxUrl() {
        assertThatThrownBy(() ->
                new ConsumerEndpoint("consumer-1", "test-group", "  ")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jmxUrl cannot be blank");
    }
}
