package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing log metrics for a Kafka topic partition.
 *
 * <p>Tracks log size, segment count, offsets, and flush rate to detect
 * storage issues and retention policy violations.
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #isNearRetentionLimit()} - Log size near retention limit (warning)</li>
 *   <li>{@link #hasExcessiveSegments()} - Too many log segments (cleanup needed)</li>
 *   <li>{@link #utilizationPercent()} - Storage utilization percentage</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * LogMetrics metrics = new LogMetrics(
 *     "broker-1",
 *     "orders",
 *     0,
 *     50.0,           // 50 flushes/sec
 *     25,             // 25 segments
 *     1_048_576_000L, // 1GB log size
 *     1_000_000L,     // end offset
 *     100_000L,       // start offset
 *     1_073_741_824L, // 1GB retention limit
 *     System.currentTimeMillis()
 * );
 *
 * if (metrics.isNearRetentionLimit()) {
 *     alert("Partition approaching retention limit!");
 * }
 * }</pre>
 *
 * @param brokerId          Broker identifier (e.g., "broker-1", "0")
 * @param topic             Topic name
 * @param partition         Partition number
 * @param flushRatePerSec   Log flush rate (flushes/second)
 * @param numSegments       Number of log segments
 * @param sizeBytes         Total log size in bytes
 * @param logEndOffset      Highest offset in log
 * @param logStartOffset    Lowest offset in log (after cleanup)
 * @param retentionBytes    Retention policy limit in bytes (0 = unlimited)
 * @param timestamp         Collection time (epoch milliseconds)
 *
 * @see RequestType
 */
public record LogMetrics(
    String brokerId,
    String topic,
    int partition,
    double flushRatePerSec,
    int numSegments,
    long sizeBytes,
    long logEndOffset,
    long logStartOffset,
    long retentionBytes,
    long timestamp
) {
    /**
     * Compact constructor with validation rules.
     *
     * @throws NullPointerException     if brokerId or topic is null
     * @throws IllegalArgumentException if partition is negative or offsets are invalid
     */
    public LogMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        Objects.requireNonNull(topic, "topic required");
        if (partition < 0) {
            throw new IllegalArgumentException("partition must be >= 0, got: " + partition);
        }
        if (flushRatePerSec < 0.0) {
            throw new IllegalArgumentException("flushRatePerSec must be >= 0, got: " + flushRatePerSec);
        }
        if (numSegments < 0) {
            throw new IllegalArgumentException("numSegments must be >= 0, got: " + numSegments);
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0, got: " + sizeBytes);
        }
        if (logEndOffset < 0) {
            throw new IllegalArgumentException("logEndOffset must be >= 0, got: " + logEndOffset);
        }
        if (logStartOffset < 0) {
            throw new IllegalArgumentException("logStartOffset must be >= 0, got: " + logStartOffset);
        }
        if (logStartOffset > logEndOffset) {
            throw new IllegalArgumentException(
                "logStartOffset (" + logStartOffset + ") cannot exceed logEndOffset (" + logEndOffset + ")"
            );
        }
        if (retentionBytes < 0) {
            throw new IllegalArgumentException("retentionBytes must be >= 0, got: " + retentionBytes);
        }
    }

    /**
     * Determines if log size is near retention limit (warning state).
     * <p>
     * Near-limit is defined as size exceeding 95% of retention policy,
     * indicating cleanup will occur soon.
     *
     * @return true if retentionBytes > 0 AND sizeBytes >= 95% of retentionBytes
     */
    public boolean isNearRetentionLimit() {
        if (retentionBytes == 0) {
            return false; // Unlimited retention
        }
        return sizeBytes >= (retentionBytes * 0.95);
    }

    /**
     * Determines if partition has excessive log segments (warning state).
     * <p>
     * Excessive segments (>50) can indicate slow log cleanup or very high
     * message rates requiring tuning.
     *
     * @return true if numSegments > 50
     */
    public boolean hasExcessiveSegments() {
        return numSegments > 50;
    }

    /**
     * Calculates log storage utilization as a percentage.
     * <p>
     * Utilization represents the proportion of retention limit consumed.
     * Returns 0.0 for unlimited retention (retentionBytes == 0).
     *
     * @return utilization percentage (0.0-1.0), or 0.0 if unlimited retention
     */
    public double utilizationPercent() {
        if (retentionBytes == 0) {
            return 0.0; // Unlimited retention
        }
        return Math.min(1.0, (double) sizeBytes / retentionBytes);
    }

    /**
     * Returns the number of messages in the log (approximation).
     * <p>
     * Calculated as logEndOffset - logStartOffset. This is an approximation
     * because compacted topics may have gaps in offsets.
     *
     * @return approximate message count
     */
    public long messageCount() {
        return logEndOffset - logStartOffset;
    }

    /**
     * Returns a partition identifier string for logging.
     *
     * @return partition identifier (e.g., "orders-0")
     */
    public String partitionId() {
        return topic + "-" + partition;
    }
}
