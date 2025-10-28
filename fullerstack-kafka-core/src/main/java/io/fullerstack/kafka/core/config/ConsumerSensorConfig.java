package io.fullerstack.kafka.core.config;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for ConsumerSensor - JMX metric collection and lag monitoring.
 * <p>
 * Specifies which consumers to monitor, collection interval, and JMX pooling configuration.
 *
 * @param bootstrapServers      Kafka bootstrap servers for AdminClient lag collection
 * @param endpoints             List of consumer endpoints to monitor
 * @param collectionIntervalMs  Collection interval in milliseconds (default: 30000 = 30s)
 * @param jmxPoolConfig         JMX connection pooling configuration
 */
public record ConsumerSensorConfig(
        String bootstrapServers,
        List<ConsumerEndpoint> endpoints,
        long collectionIntervalMs,
        JmxConnectionPoolConfig jmxPoolConfig
) {
    /**
     * Compact constructor with validation.
     */
    public ConsumerSensorConfig {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers cannot be null");
        Objects.requireNonNull(endpoints, "endpoints cannot be null");
        Objects.requireNonNull(jmxPoolConfig, "jmxPoolConfig cannot be null");

        if (bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers cannot be blank");
        }
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("endpoints cannot be empty");
        }
        if (collectionIntervalMs <= 0) {
            throw new IllegalArgumentException("collectionIntervalMs must be > 0");
        }
    }

    /**
     * Create config with default collection interval (30 seconds) and pooling enabled.
     */
    public static ConsumerSensorConfig defaults(String bootstrapServers, List<ConsumerEndpoint> endpoints) {
        return new ConsumerSensorConfig(
                bootstrapServers,
                endpoints,
                30_000L,
                JmxConnectionPoolConfig.withPoolingEnabled()
        );
    }
}
