package io.fullerstack.kafka.broker.models;

/**
 * BrokerMetrics represents raw JMX metrics collected from a Kafka broker.
 * <p>
 * This immutable record contains 13 key health metrics collected via JMX:
 * <ul>
 *   <li>Heap Memory: used and max heap bytes</li>
 *   <li>CPU Usage: process CPU load (0.0-1.0)</li>
 *   <li>Request Rates: messages, bytes in/out per second</li>
 *   <li>Controller Status: active controller count (0 or 1)</li>
 *   <li>Replication Health: under-replicated and offline partitions</li>
 *   <li>Thread Pool Idle: network processor and request handler idle percentages</li>
 *   <li>Request Latencies: fetch consumer and produce total time in milliseconds</li>
 * </ul>
 * <p>
 * All metrics are collected at a specific timestamp for freshness assessment.
 *
 * @param brokerId                           Unique broker identifier
 * @param heapUsed                           Heap memory used in bytes
 * @param heapMax                            Maximum heap memory in bytes
 * @param cpuUsage                           CPU usage (0.0-1.0)
 * @param requestRate                        Request rate per second
 * @param byteInRate                         Bytes in rate per second
 * @param byteOutRate                        Bytes out rate per second
 * @param activeControllers                  Active controller count (0 or 1)
 * @param underReplicatedPartitions          Number of under-replicated partitions
 * @param offlinePartitionsCount             Number of offline partitions
 * @param networkProcessorAvgIdlePercent     Network processor average idle percentage (0-100)
 * @param requestHandlerAvgIdlePercent       Request handler average idle percentage (0-100)
 * @param fetchConsumerTotalTimeMs           Fetch consumer total time in milliseconds
 * @param produceTotalTimeMs                 Produce total time in milliseconds
 * @param timestamp                          Timestamp of metric collection (epoch milliseconds)
 */
public record BrokerMetrics(
        String brokerId,
        long heapUsed,
        long heapMax,
        double cpuUsage,
        long requestRate,
        long byteInRate,
        long byteOutRate,
        int activeControllers,
        int underReplicatedPartitions,
        int offlinePartitionsCount,
        long networkProcessorAvgIdlePercent,
        long requestHandlerAvgIdlePercent,
        long fetchConsumerTotalTimeMs,
        long produceTotalTimeMs,
        long timestamp
) {
    /**
     * Compact constructor with validation.
     * <p>
     * Validates all fields to ensure data integrity:
     * <ul>
     *   <li>brokerId must not be null or blank</li>
     *   <li>heap metrics must be non-negative and heapUsed &lt;= heapMax</li>
     *   <li>cpuUsage must be in range [0.0, 1.0]</li>
     *   <li>rate and count fields must be non-negative</li>
     *   <li>idle percentages must be in range [0, 100]</li>
     *   <li>timestamp must be positive</li>
     * </ul>
     *
     * @throws IllegalArgumentException if any validation constraint is violated
     */
    public BrokerMetrics {
        // Validate brokerId
        if (brokerId == null || brokerId.isBlank()) {
            throw new IllegalArgumentException("brokerId must not be null or blank");
        }

        // Validate heap metrics
        if (heapUsed < 0) {
            throw new IllegalArgumentException("heapUsed must be non-negative, got: " + heapUsed);
        }
        if (heapMax < 0) {
            throw new IllegalArgumentException("heapMax must be non-negative, got: " + heapMax);
        }
        if (heapUsed > heapMax) {
            throw new IllegalArgumentException("heapUsed (" + heapUsed + ") cannot exceed heapMax (" + heapMax + ")");
        }

        // Validate CPU usage
        if (cpuUsage < 0.0 || cpuUsage > 1.0) {
            throw new IllegalArgumentException("cpuUsage must be in range [0.0, 1.0], got: " + cpuUsage);
        }

        // Validate rate metrics
        if (requestRate < 0) {
            throw new IllegalArgumentException("requestRate must be non-negative, got: " + requestRate);
        }
        if (byteInRate < 0) {
            throw new IllegalArgumentException("byteInRate must be non-negative, got: " + byteInRate);
        }
        if (byteOutRate < 0) {
            throw new IllegalArgumentException("byteOutRate must be non-negative, got: " + byteOutRate);
        }

        // Validate controller status
        if (activeControllers < 0 || activeControllers > 1) {
            throw new IllegalArgumentException("activeControllers must be 0 or 1, got: " + activeControllers);
        }

        // Validate partition counts
        if (underReplicatedPartitions < 0) {
            throw new IllegalArgumentException("underReplicatedPartitions must be non-negative, got: " + underReplicatedPartitions);
        }
        if (offlinePartitionsCount < 0) {
            throw new IllegalArgumentException("offlinePartitionsCount must be non-negative, got: " + offlinePartitionsCount);
        }

        // Validate idle percentages
        if (networkProcessorAvgIdlePercent < 0 || networkProcessorAvgIdlePercent > 100) {
            throw new IllegalArgumentException("networkProcessorAvgIdlePercent must be in range [0, 100], got: " + networkProcessorAvgIdlePercent);
        }
        if (requestHandlerAvgIdlePercent < 0 || requestHandlerAvgIdlePercent > 100) {
            throw new IllegalArgumentException("requestHandlerAvgIdlePercent must be in range [0, 100], got: " + requestHandlerAvgIdlePercent);
        }

        // Validate latency metrics (non-negative)
        if (fetchConsumerTotalTimeMs < 0) {
            throw new IllegalArgumentException("fetchConsumerTotalTimeMs must be non-negative, got: " + fetchConsumerTotalTimeMs);
        }
        if (produceTotalTimeMs < 0) {
            throw new IllegalArgumentException("produceTotalTimeMs must be non-negative, got: " + produceTotalTimeMs);
        }

        // Validate timestamp
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be positive, got: " + timestamp);
        }
    }

    /**
     * Calculate heap usage percentage.
     *
     * @return heap usage percentage (0.0-100.0)
     */
    public double heapUsagePercent() {
        if (heapMax == 0) {
            return 0.0;
        }
        return (heapUsed * 100.0) / heapMax;
    }

    /**
     * Calculate CPU usage percentage.
     *
     * @return CPU usage percentage (0.0-100.0)
     */
    public double cpuUsagePercent() {
        return cpuUsage * 100.0;
    }

    /**
     * Check if metrics are fresh based on age threshold.
     *
     * @param maxAgeMs maximum age in milliseconds
     * @return true if metrics age is within threshold
     */
    public boolean isFresh(long maxAgeMs) {
        long age = System.currentTimeMillis() - timestamp;
        return age <= maxAgeMs;
    }
}
