package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing In-Sync Replica (ISR) replication metrics for a Kafka broker.
 *
 * <p>Tracks ISR shrinks/expands, replica lag, and fetch rates to detect replication health issues.
 *
 * <h3>JMX Sources</h3>
 * <ul>
 *   <li><b>ISR Shrinks</b> - {@code kafka.server:type=ReplicaManager,name=IsrShrinksPerSec}</li>
 *   <li><b>ISR Expands</b> - {@code kafka.server:type=ReplicaManager,name=IsrExpandsPerSec}</li>
 *   <li><b>Replica Lag Max</b> - {@code kafka.server:type=ReplicaFetcherManager,name=MaxLag,clientId=Replica}</li>
 *   <li><b>Replica Fetch Rate Min</b> - {@code kafka.server:type=ReplicaFetcherManager,name=MinFetchRate,clientId=Replica}</li>
 * </ul>
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #hasShrinkEvents()} - ISR shrinks occurred since last poll</li>
 *   <li>{@link #hasExpandEvents()} - ISR expands occurred since last poll</li>
 *   <li>{@link #isReplicaLagging()} - Replica lag exceeds warning threshold (1000 messages)</li>
 *   <li>{@link #isReplicaCritical()} - Replica lag exceeds critical threshold (10000 messages)</li>
 *   <li>{@link #isFetchRateDegraded()} - Fetch rate below healthy threshold (1.0 msg/sec)</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * IsrMetrics metrics = new IsrMetrics(
 *     "broker-1",
 *     5L,      // ISR shrink count
 *     2L,      // ISR expand count
 *     5000L,   // max lag (5000 messages)
 *     0.5,     // min fetch rate (0.5 msg/sec)
 *     System.currentTimeMillis()
 * );
 *
 * if (metrics.isReplicaLagging()) {
 *     alert("Replica lag detected on broker-1!");
 * }
 * }</pre>
 *
 * @param brokerId           Broker identifier (e.g., "broker-1", "0")
 * @param isrShrinkCount     Total ISR shrinks since broker start
 * @param isrExpandCount     Total ISR expands since broker start
 * @param replicaMaxLag      Maximum lag across all follower replicas (messages)
 * @param replicaMinFetchRate Minimum fetch rate across all follower replicas (msg/sec)
 * @param timestamp          Collection time (epoch milliseconds)
 *
 * @see ReplicationHealthMetrics
 */
public record IsrMetrics(
    String brokerId,
    long isrShrinkCount,
    long isrExpandCount,
    long replicaMaxLag,
    double replicaMinFetchRate,
    long timestamp
) {
    // Thresholds for lag assessment
    private static final long LAG_WARNING_THRESHOLD = 1000L;    // 1K messages
    private static final long LAG_CRITICAL_THRESHOLD = 10000L;  // 10K messages

    // Threshold for fetch rate assessment
    private static final double FETCH_RATE_HEALTHY_THRESHOLD = 1.0;  // 1 msg/sec

    /**
     * Compact constructor with validation rules.
     *
     * @throws NullPointerException     if brokerId is null
     * @throws IllegalArgumentException if counts or lag are negative, or fetch rate is negative
     */
    public IsrMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        if (isrShrinkCount < 0) {
            throw new IllegalArgumentException("isrShrinkCount must be >= 0, got: " + isrShrinkCount);
        }
        if (isrExpandCount < 0) {
            throw new IllegalArgumentException("isrExpandCount must be >= 0, got: " + isrExpandCount);
        }
        if (replicaMaxLag < 0) {
            throw new IllegalArgumentException("replicaMaxLag must be >= 0, got: " + replicaMaxLag);
        }
        if (replicaMinFetchRate < 0.0) {
            throw new IllegalArgumentException("replicaMinFetchRate must be >= 0.0, got: " + replicaMinFetchRate);
        }
    }

    /**
     * Determines if ISR shrink events occurred.
     * <p>
     * This should be called with delta (current - previous) to detect new shrinks.
     *
     * @return true if isrShrinkCount > 0
     */
    public boolean hasShrinkEvents() {
        return isrShrinkCount > 0;
    }

    /**
     * Determines if ISR expand events occurred.
     * <p>
     * This should be called with delta (current - previous) to detect new expands.
     *
     * @return true if isrExpandCount > 0
     */
    public boolean hasExpandEvents() {
        return isrExpandCount > 0;
    }

    /**
     * Determines if replica lag exceeds warning threshold (1000 messages).
     * <p>
     * Warning level indicates a follower is falling behind but not yet critical.
     *
     * @return true if replicaMaxLag >= 1000
     */
    public boolean isReplicaLagging() {
        return replicaMaxLag >= LAG_WARNING_THRESHOLD;
    }

    /**
     * Determines if replica lag exceeds critical threshold (10000 messages).
     * <p>
     * Critical level indicates a follower is at risk of being dropped from ISR.
     *
     * @return true if replicaMaxLag >= 10000
     */
    public boolean isReplicaCritical() {
        return replicaMaxLag >= LAG_CRITICAL_THRESHOLD;
    }

    /**
     * Determines if fetch rate is below healthy threshold (1.0 msg/sec).
     * <p>
     * Low fetch rate indicates replication is slowing down, possibly due to
     * network issues or follower performance problems.
     *
     * @return true if replicaMinFetchRate < 1.0
     */
    public boolean isFetchRateDegraded() {
        return replicaMinFetchRate < FETCH_RATE_HEALTHY_THRESHOLD;
    }

    /**
     * Creates a delta metrics instance by subtracting previous counts.
     * <p>
     * Used to calculate shrinks/expands since last poll cycle.
     *
     * @param previous Previous ISR metrics snapshot
     * @return Delta metrics with count differences
     */
    public IsrMetrics delta(IsrMetrics previous) {
        Objects.requireNonNull(previous, "previous metrics required");

        return new IsrMetrics(
            brokerId,
            Math.max(0, isrShrinkCount - previous.isrShrinkCount),
            Math.max(0, isrExpandCount - previous.isrExpandCount),
            replicaMaxLag,  // Lag is absolute, not cumulative
            replicaMinFetchRate,  // Rate is absolute, not cumulative
            timestamp
        );
    }
}
