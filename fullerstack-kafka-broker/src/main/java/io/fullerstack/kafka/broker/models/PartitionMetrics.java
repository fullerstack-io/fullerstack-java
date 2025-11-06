package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing partition state metrics for a Kafka topic partition.
 *
 * <p>Tracks partition size, offset progression, ISR health, and leader epoch
 * to detect under-replication, partition lag, and leadership changes.
 *
 * <h3>Metrics Tracked</h3>
 * <ul>
 *   <li><b>Partition Size</b> - Total bytes stored in partition</li>
 *   <li><b>Log End Offset</b> - Highest offset in partition log</li>
 *   <li><b>ISR Count</b> - Number of in-sync replicas</li>
 *   <li><b>Leader Epoch</b> - Current leader epoch (changes on leader election)</li>
 * </ul>
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #isUnderReplicated()} - ISR count is less than replication factor</li>
 *   <li>{@link #isHealthy()} - All replicas are in-sync</li>
 *   <li>{@link #hasLeaderChanged(int)} - Detects leader epoch changes</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * PartitionMetrics metrics = new PartitionMetrics(
 *     "broker-1",
 *     "orders",           // topic name
 *     0,                  // partition id
 *     10485760,           // 10 MB size
 *     150000,             // log end offset
 *     2,                  // ISR count
 *     3,                  // replication factor
 *     5,                  // leader epoch
 *     System.currentTimeMillis()
 * );
 *
 * if (metrics.isUnderReplicated()) {
 *     alert("Partition under-replicated!");
 * }
 * }</pre>
 *
 * @param brokerId            Broker identifier (e.g., "broker-1", "0")
 * @param topicName           Topic name
 * @param partitionId         Partition ID
 * @param sizeBytes           Total bytes stored in partition
 * @param logEndOffset        Highest offset in partition log
 * @param isrCount            Number of in-sync replicas
 * @param replicationFactor   Configured replication factor
 * @param leaderEpoch         Current leader epoch
 * @param timestamp           Collection time (epoch milliseconds)
 */
public record PartitionMetrics(
    String brokerId,
    String topicName,
    int partitionId,
    long sizeBytes,
    long logEndOffset,
    int isrCount,
    int replicationFactor,
    int leaderEpoch,
    long timestamp
) {
    /**
     * Compact constructor with validation rules.
     *
     * @throws NullPointerException     if brokerId or topicName is null
     * @throws IllegalArgumentException if partitionId, sizeBytes, or counts are negative
     */
    public PartitionMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        Objects.requireNonNull(topicName, "topicName required");
        if (partitionId < 0) {
            throw new IllegalArgumentException("partitionId must be >= 0, got: " + partitionId);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0, got: " + sizeBytes);
        }
        if (logEndOffset < 0) {
            throw new IllegalArgumentException("logEndOffset must be >= 0, got: " + logEndOffset);
        }
        if (isrCount < 0) {
            throw new IllegalArgumentException("isrCount must be >= 0, got: " + isrCount);
        }
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("replicationFactor must be >= 1, got: " + replicationFactor);
        }
        if (leaderEpoch < 0) {
            throw new IllegalArgumentException("leaderEpoch must be >= 0, got: " + leaderEpoch);
        }
    }

    /**
     * Determines if partition is under-replicated (critical state).
     * <p>
     * Under-replication means fewer replicas are in-sync than configured,
     * indicating replication lag or replica failures. This reduces fault tolerance.
     *
     * @return true if isrCount < replicationFactor
     */
    public boolean isUnderReplicated() {
        return isrCount < replicationFactor;
    }

    /**
     * Determines if partition is healthy (normal state).
     * <p>
     * Healthy state means all configured replicas are in-sync.
     *
     * @return true if isrCount == replicationFactor
     */
    public boolean isHealthy() {
        return isrCount == replicationFactor;
    }

    /**
     * Determines if leader has changed since previous epoch.
     * <p>
     * Leader changes can indicate broker failures, controlled shutdowns,
     * or partition reassignments.
     *
     * @param previousEpoch previous leader epoch value
     * @return true if leaderEpoch > previousEpoch
     */
    public boolean hasLeaderChanged(int previousEpoch) {
        return leaderEpoch > previousEpoch;
    }

    /**
     * Calculates replication health as a percentage.
     * <p>
     * Represents the proportion of replicas that are in-sync.
     *
     * @return health percentage (0.0-1.0)
     */
    public double replicationHealth() {
        return (double) isrCount / replicationFactor;
    }

    /**
     * Returns a human-readable partition identifier.
     *
     * @return partition identifier in format "topic-partition"
     */
    public String partitionIdentifier() {
        return topicName + "-" + partitionId;
    }
}
