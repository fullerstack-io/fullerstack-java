package io.fullerstack.kafka.core.config;

import java.util.Objects;

/**
 * Represents a Kafka consumer endpoint with consumer identity and JMX address.
 * <p>
 * Used by ConsumerSensor to identify and connect to consumer instances for metric collection.
 *
 * @param consumerId    Unique consumer identifier (client.id configuration)
 * @param consumerGroup Consumer group ID
 * @param jmxUrl        JMX connection URL or HTTP metrics endpoint
 *                      Examples:
 *                      - JMX: "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi"
 *                      - HTTP: "http://app1.example.com:9999/metrics"
 */
public record ConsumerEndpoint(
        String consumerId,
        String consumerGroup,
        String jmxUrl
) {
    /**
     * Compact constructor with validation.
     */
    public ConsumerEndpoint {
        Objects.requireNonNull(consumerId, "consumerId cannot be null");
        Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null");
        Objects.requireNonNull(jmxUrl, "jmxUrl cannot be null");

        if (consumerId.isBlank()) {
            throw new IllegalArgumentException("consumerId cannot be blank");
        }
        if (consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup cannot be blank");
        }
        if (jmxUrl.isBlank()) {
            throw new IllegalArgumentException("jmxUrl cannot be blank");
        }
    }
}
