package io.fullerstack.kafka.core.config;

import java.util.Objects;

/**
 * Represents a Kafka producer endpoint for JMX metric collection.
 * <p>
 * Producers are typically running in application JVMs and expose JMX
 * on localhost or via JMX Exporter HTTP endpoints.
 *
 * @param producerId  Unique producer identifier (client.id from producer config)
 * @param jmxUrl      JMX connection URL or HTTP metrics endpoint
 *                    Examples:
 *                    - JMX: "service:jmx:rmi:///jndi/rmi://app1.mycompany.com:9999/jmxrmi"
 *                    - HTTP: "http://app1.mycompany.com:9999/metrics"
 */
public record ProducerEndpoint(
        String producerId,
        String jmxUrl
) {
    /**
     * Compact constructor with validation.
     */
    public ProducerEndpoint {
        Objects.requireNonNull(producerId, "producerId cannot be null");
        Objects.requireNonNull(jmxUrl, "jmxUrl cannot be null");

        if (producerId.isBlank()) {
            throw new IllegalArgumentException("producerId cannot be blank");
        }
        if (jmxUrl.isBlank()) {
            throw new IllegalArgumentException("jmxUrl cannot be blank");
        }
    }
}
