package io.fullerstack.kafka.core.config;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for ProducerSensor - JMX metric collection from Kafka producers.
 * <p>
 * Specifies which producers to monitor and collection interval.
 *
 * @param endpoints             List of producer endpoints to monitor
 * @param collectionIntervalMs  Collection interval in milliseconds (default: 30000 = 30s)
 * @param useConnectionPooling  Whether to use JMX connection pooling for high-frequency monitoring
 */
public record ProducerSensorConfig(
        List<ProducerEndpoint> endpoints,
        long collectionIntervalMs,
        boolean useConnectionPooling
) {
    /**
     * Compact constructor with validation.
     */
    public ProducerSensorConfig {
        Objects.requireNonNull(endpoints, "endpoints cannot be null");
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints cannot be empty");
        }
        if (collectionIntervalMs <= 0) {
            throw new IllegalArgumentException("collectionIntervalMs must be > 0");
        }
    }

    /**
     * Create config without JMX connection pooling (standard mode).
     */
    public static ProducerSensorConfig withoutPooling(
            List<ProducerEndpoint> endpoints,
            long collectionIntervalMs
    ) {
        return new ProducerSensorConfig(endpoints, collectionIntervalMs, false);
    }

    /**
     * Create config with JMX connection pooling (high-frequency mode).
     */
    public static ProducerSensorConfig withPooling(
            List<ProducerEndpoint> endpoints,
            long collectionIntervalMs
    ) {
        return new ProducerSensorConfig(endpoints, collectionIntervalMs, true);
    }

    /**
     * Create config with default collection interval (30 seconds) and no pooling.
     */
    public static ProducerSensorConfig defaults(List<ProducerEndpoint> endpoints) {
        return new ProducerSensorConfig(endpoints, 30_000, false);
    }
}
