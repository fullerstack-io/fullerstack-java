package io.fullerstack.kafka.broker.models;

/**
 * Enumeration of Kafka request types tracked for metrics.
 *
 * <p>Each type corresponds to a specific Kafka protocol operation
 * and has distinct performance characteristics and SLA requirements.
 *
 * <h3>Request Types</h3>
 * <ul>
 *   <li><b>PRODUCE</b> - Client write requests (most latency-sensitive)</li>
 *   <li><b>FETCH</b> - Client read requests and follower replication</li>
 *   <li><b>METADATA</b> - Topic/partition metadata requests</li>
 *   <li><b>OFFSET_FETCH</b> - Consumer offset retrieval</li>
 * </ul>
 *
 * <h3>JMX Metric Naming</h3>
 * The {@link #getJmxRequestName()} method returns the exact name used in
 * Kafka JMX MBean object names:
 * <pre>
 * kafka.network:type=RequestMetrics,name=RequestsPerSec,request={jmxName}
 * </pre>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * RequestType type = RequestType.PRODUCE;
 * String jmxName = type.getJmxRequestName(); // "Produce"
 *
 * ObjectName mbean = new ObjectName(
 *     "kafka.network:type=RequestMetrics,name=RequestsPerSec,request=" + jmxName
 * );
 * }</pre>
 */
public enum RequestType {
    /**
     * Producer write requests.
     * <p>
     * Most latency-sensitive operation. Producers typically have tight SLAs
     * (50-100ms) and high throughput requirements.
     * <p>
     * JMX name: {@code Produce}
     */
    PRODUCE("Produce"),

    /**
     * Consumer read requests and follower replication fetches.
     * <p>
     * Moderately latency-sensitive. Consumers can tolerate higher latency
     * than producers (100-500ms typical).
     * <p>
     * JMX name: {@code Fetch}
     */
    FETCH("Fetch"),

    /**
     * Topic and partition metadata requests.
     * <p>
     * Low frequency but important for client initialization and rebalancing.
     * <p>
     * JMX name: {@code Metadata}
     */
    METADATA("Metadata"),

    /**
     * Consumer group offset fetch requests.
     * <p>
     * Used during consumer startup and rebalancing to retrieve committed offsets.
     * <p>
     * JMX name: {@code OffsetFetch}
     */
    OFFSET_FETCH("OffsetFetch");

    private final String jmxRequestName;

    /**
     * Creates a RequestType with its corresponding JMX metric name.
     *
     * @param jmxRequestName The name used in Kafka JMX metrics (case-sensitive)
     */
    RequestType(String jmxRequestName) {
        this.jmxRequestName = jmxRequestName;
    }

    /**
     * Returns the exact name used in Kafka JMX MBean object names.
     * <p>
     * This value is used to construct JMX ObjectName patterns like:
     * {@code kafka.network:type=RequestMetrics,name=*,request={jmxName}}
     *
     * @return JMX request name (case-sensitive, e.g., "Produce", "Fetch")
     */
    public String getJmxRequestName() {
        return jmxRequestName;
    }
}
