package io.fullerstack.kafka.consumer.models;

import org.apache.kafka.common.TopicPartition;

import java.util.Map;
import java.util.Objects;

/**
 * Consumer performance metrics including lag, throughput, and rebalance history.
 * <p>
 * This record captures both JMX metrics (fetch rate, commit latency) and
 * AdminClient-based lag metrics for comprehensive consumer health monitoring.
 *
 * @param consumerId                Unique consumer identifier (client.id)
 * @param consumerGroup             Consumer group ID
 * @param totalLag                  Total lag across all assigned partitions
 * @param lagByPartition            Per-partition lag map
 * @param fetchRate                 Records fetched per second (1-minute rate)
 * @param commitRate                Commits per second
 * @param avgCommitLatencyMs        Average commit latency in milliseconds
 * @param avgPollLatencyMs          Average poll() duration in milliseconds
 * @param avgProcessingLatencyMs    Time between polls (processing time estimate)
 * @param assignedPartitionCount    Number of currently assigned partitions
 * @param lastRebalanceTimestamp    Last rebalance time (epoch milliseconds)
 * @param rebalanceCount            Total rebalances since consumer start
 * @param timestamp                 Collection time (epoch milliseconds)
 */
public record ConsumerMetrics(
        String consumerId,
        String consumerGroup,
        long totalLag,
        Map<TopicPartition, Long> lagByPartition,
        double fetchRate,
        double commitRate,
        double avgCommitLatencyMs,
        double avgPollLatencyMs,
        double avgProcessingLatencyMs,
        int assignedPartitionCount,
        long lastRebalanceTimestamp,
        int rebalanceCount,
        long timestamp
) {
    /**
     * Compact constructor with validation.
     */
    public ConsumerMetrics {
        Objects.requireNonNull(consumerId, "consumerId cannot be null");
        Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null");
        Objects.requireNonNull(lagByPartition, "lagByPartition cannot be null");

        if (consumerId.isBlank()) {
            throw new IllegalArgumentException("consumerId cannot be blank");
        }
        if (consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup cannot be blank");
        }
        if (totalLag < 0) {
            throw new IllegalArgumentException("totalLag must be >= 0, got: " + totalLag);
        }
        if (fetchRate < 0) {
            throw new IllegalArgumentException("fetchRate must be >= 0, got: " + fetchRate);
        }
        if (commitRate < 0) {
            throw new IllegalArgumentException("commitRate must be >= 0, got: " + commitRate);
        }
        if (avgCommitLatencyMs < 0) {
            throw new IllegalArgumentException("avgCommitLatencyMs must be >= 0, got: " + avgCommitLatencyMs);
        }
        if (assignedPartitionCount < 0) {
            throw new IllegalArgumentException("assignedPartitionCount must be >= 0, got: " + assignedPartitionCount);
        }
        if (rebalanceCount < 0) {
            throw new IllegalArgumentException("rebalanceCount must be >= 0, got: " + rebalanceCount);
        }
    }

    /**
     * Check if consumer is healthy based on conservative thresholds.
     * <p>
     * Healthy criteria:
     * <ul>
     *   <li>Total lag less than 1000 messages</li>
     *   <li>Average processing latency less than 100ms</li>
     *   <li>Fewer than 5 rebalances since start</li>
     * </ul>
     *
     * @return true if all health criteria are met
     */
    public boolean isHealthy() {
        return totalLag < 1000
                && avgProcessingLatencyMs < 100
                && rebalanceCount < 5;
    }

    /**
     * Check if consumer has any lag.
     *
     * @return true if totalLag is greater than 0
     */
    public boolean hasLag() {
        return totalLag > 0;
    }

    /**
     * Calculate average lag per assigned partition.
     *
     * @return average lag, or 0.0 if no partitions assigned
     */
    public double avgLagPerPartition() {
        return assignedPartitionCount > 0
                ? (double) totalLag / assignedPartitionCount
                : 0.0;
    }
}
