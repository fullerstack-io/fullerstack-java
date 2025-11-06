package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing network I/O metrics for a Kafka broker.
 *
 * <p>Tracks request/response rates, throughput (bytes and messages), to detect
 * network saturation and throughput bottlenecks.
 *
 * <h3>Metrics Tracked</h3>
 * <ul>
 *   <li><b>Request Rate</b> - Requests per second (all types)</li>
 *   <li><b>Response Rate</b> - Responses per second (all types)</li>
 *   <li><b>Bytes In Rate</b> - Incoming bytes per second</li>
 *   <li><b>Bytes Out Rate</b> - Outgoing bytes per second</li>
 *   <li><b>Messages In Rate</b> - Incoming messages per second</li>
 * </ul>
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #isSaturated()} - Bytes in rate exceeds saturation threshold (critical)</li>
 *   <li>{@link #isDegraded()} - Bytes in rate exceeds warning threshold</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * NetworkMetrics metrics = new NetworkMetrics(
 *     "broker-1",
 *     1250.5,    // requests/sec
 *     1248.2,    // responses/sec
 *     52428800,  // 50 MB/s in
 *     104857600, // 100 MB/s out
 *     25000,     // messages/sec
 *     System.currentTimeMillis()
 * );
 *
 * if (metrics.isSaturated()) {
 *     alert("Network saturation detected!");
 * }
 * }</pre>
 *
 * @param brokerId         Broker identifier (e.g., "broker-1", "0")
 * @param requestRate      Requests per second (all request types)
 * @param responseRate     Responses per second (all response types)
 * @param bytesInRate      Incoming bytes per second
 * @param bytesOutRate     Outgoing bytes per second
 * @param messagesInRate   Incoming messages per second
 * @param timestamp        Collection time (epoch milliseconds)
 */
public record NetworkMetrics(
    String brokerId,
    double requestRate,
    double responseRate,
    double bytesInRate,
    double bytesOutRate,
    double messagesInRate,
    long timestamp
) {
    // Network saturation thresholds (configurable in production)
    // Assuming 1 Gbps = 125 MB/s, saturation at 80% = 100 MB/s
    private static final double SATURATION_THRESHOLD = 100_000_000; // 100 MB/s
    private static final double WARNING_THRESHOLD = 75_000_000;     // 75 MB/s

    /**
     * Compact constructor with validation rules.
     *
     * @throws NullPointerException     if brokerId is null
     * @throws IllegalArgumentException if any rate is negative
     */
    public NetworkMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        if (requestRate < 0.0) {
            throw new IllegalArgumentException("requestRate must be >= 0.0, got: " + requestRate);
        }
        if (responseRate < 0.0) {
            throw new IllegalArgumentException("responseRate must be >= 0.0, got: " + responseRate);
        }
        if (bytesInRate < 0.0) {
            throw new IllegalArgumentException("bytesInRate must be >= 0.0, got: " + bytesInRate);
        }
        if (bytesOutRate < 0.0) {
            throw new IllegalArgumentException("bytesOutRate must be >= 0.0, got: " + bytesOutRate);
        }
        if (messagesInRate < 0.0) {
            throw new IllegalArgumentException("messagesInRate must be >= 0.0, got: " + messagesInRate);
        }
    }

    /**
     * Determines if network is saturated (critical state).
     * <p>
     * Saturation is defined as bytes in rate exceeding 80% of network capacity.
     * This indicates the broker may be dropping packets or experiencing high latency.
     *
     * @return true if bytesInRate >= SATURATION_THRESHOLD
     */
    public boolean isSaturated() {
        return bytesInRate >= SATURATION_THRESHOLD;
    }

    /**
     * Determines if network is degraded (warning state).
     * <p>
     * Degradation is defined as bytes in rate exceeding 60% of network capacity.
     * This indicates the broker is under moderate load and should be monitored.
     *
     * @return true if bytesInRate >= WARNING_THRESHOLD
     */
    public boolean isDegraded() {
        return bytesInRate >= WARNING_THRESHOLD;
    }

    /**
     * Calculates network utilization as a percentage.
     * <p>
     * Based on incoming bytes rate relative to saturation threshold.
     *
     * @return utilization percentage (0.0-1.0+), may exceed 1.0 if saturated
     */
    public double utilizationPercent() {
        return bytesInRate / SATURATION_THRESHOLD;
    }
}
