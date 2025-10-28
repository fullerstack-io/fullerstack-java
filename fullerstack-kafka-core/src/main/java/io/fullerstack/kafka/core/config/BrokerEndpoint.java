package io.fullerstack.kafka.core.config;

import java.util.Objects;

/**
 * Represents a Kafka broker endpoint with both Kafka and JMX addresses.
 * <p>
 * Used by BrokerSensor to identify and connect to broker instances for metric collection.
 *
 * @param brokerId    Unique broker identifier (e.g., "b-1", "broker-1")
 * @param kafkaHost   Kafka endpoint hostname (e.g., "b-1.cluster.kafka.us-east-1.amazonaws.com")
 * @param kafkaPort   Kafka port (default: 9092)
 * @param jmxUrl      JMX connection URL or HTTP metrics endpoint
 *                    Examples:
 *                    - JMX: "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi"
 *                    - HTTP: "http://b-1.cluster.kafka.us-east-1.amazonaws.com:11001/metrics"
 */
public record BrokerEndpoint(
        String brokerId,
        String kafkaHost,
        int kafkaPort,
        String jmxUrl
) {
    /**
     * Compact constructor with validation.
     */
    public BrokerEndpoint {
        Objects.requireNonNull(brokerId, "brokerId cannot be null");
        Objects.requireNonNull(kafkaHost, "kafkaHost cannot be null");
        Objects.requireNonNull(jmxUrl, "jmxUrl cannot be null");

        if (brokerId.isBlank()) {
            throw new IllegalArgumentException("brokerId cannot be blank");
        }
        if (kafkaHost.isBlank()) {
            throw new IllegalArgumentException("kafkaHost cannot be blank");
        }
        if (kafkaPort <= 0 || kafkaPort > 65535) {
            throw new IllegalArgumentException("kafkaPort must be between 1 and 65535");
        }
        if (jmxUrl.isBlank()) {
            throw new IllegalArgumentException("jmxUrl cannot be blank");
        }
    }

    /**
     * Create broker endpoint with default Kafka port (9092).
     */
    public static BrokerEndpoint of(String brokerId, String kafkaHost, String jmxUrl) {
        return new BrokerEndpoint(brokerId, kafkaHost, 9092, jmxUrl);
    }

    /**
     * Get full Kafka endpoint (host:port).
     */
    public String kafkaEndpoint() {
        return kafkaHost + ":" + kafkaPort;
    }
}
