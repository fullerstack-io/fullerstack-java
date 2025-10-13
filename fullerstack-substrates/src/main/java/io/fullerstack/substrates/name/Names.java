package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

/**
 * Utility class for working with Substrates Name type.
 *
 * <p>Provides factory methods for creating hierarchical names commonly used in Kafka observability.
 */
public final class Names {

    private Names() {
        // Utility class, prevent instantiation
    }

    /**
     * Creates a simple name from a string.
     *
     * @param name the name string
     * @return Substrates Name
     */
    public static Name of(String name) {
        return NameImpl.of(name);
    }

    /**
     * Creates a hierarchical name from parts joined with dots.
     *
     * @param parts the name parts
     * @return Substrates Name with parts joined by "."
     */
    public static Name hierarchical(String... parts) {
        return NameImpl.of(String.join(".", parts));
    }

    /**
     * Creates a broker metric name.
     * Example: kafka.broker.1.jvm.heap
     *
     * @param brokerId the broker ID
     * @param metric the metric name
     * @return hierarchical name for broker metric
     */
    public static Name broker(String brokerId, String metric) {
        return hierarchical("kafka", "broker", brokerId, metric);
    }

    /**
     * Creates a partition metric name.
     * Example: kafka.partition.topic-a.0.lag
     *
     * @param topic the topic name
     * @param partition the partition number
     * @param metric the metric name
     * @return hierarchical name for partition metric
     */
    public static Name partition(String topic, int partition, String metric) {
        return hierarchical("kafka", "partition", topic, String.valueOf(partition), metric);
    }

    /**
     * Creates a client operation name.
     * Example: kafka.client.producer-1.send
     *
     * @param clientId the client ID
     * @param operation the operation name
     * @return hierarchical name for client operation
     */
    public static Name client(String clientId, String operation) {
        return hierarchical("kafka", "client", clientId, operation);
    }
}
