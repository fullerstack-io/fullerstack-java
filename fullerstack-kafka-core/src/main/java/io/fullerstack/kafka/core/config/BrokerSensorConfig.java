package io.fullerstack.kafka.core.config;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for BrokerSensor - JMX metric collection from Kafka brokers.
 * <p>
 * Specifies which brokers to monitor and collection interval.
 *
 * @param endpoints             List of broker endpoints to monitor
 * @param collectionIntervalMs  Collection interval in milliseconds (default: 30000 = 30s)
 * @param useConnectionPooling  Whether to use JMX connection pooling for high-frequency monitoring
 */
public record BrokerSensorConfig(
        List<BrokerEndpoint> endpoints,
        long collectionIntervalMs,
        boolean useConnectionPooling
) {
    /**
     * Compact constructor with validation.
     */
    public BrokerSensorConfig {
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
    public static BrokerSensorConfig withoutPooling(
            List<BrokerEndpoint> endpoints,
            long collectionIntervalMs
    ) {
        return new BrokerSensorConfig(endpoints, collectionIntervalMs, false);
    }

    /**
     * Create config with JMX connection pooling (high-frequency mode).
     */
    public static BrokerSensorConfig withPooling(
            List<BrokerEndpoint> endpoints,
            long collectionIntervalMs
    ) {
        return new BrokerSensorConfig(endpoints, collectionIntervalMs, true);
    }

    /**
     * Create config with default collection interval (30 seconds) and no pooling.
     */
    public static BrokerSensorConfig defaults(List<BrokerEndpoint> endpoints) {
        return new BrokerSensorConfig(endpoints, 30_000, false);
    }
}
