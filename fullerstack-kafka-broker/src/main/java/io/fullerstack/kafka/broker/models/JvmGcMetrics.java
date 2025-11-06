package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing JVM garbage collection metrics for a Kafka broker.
 *
 * <p>Tracks GC collection count and time to detect GC storms and pauses.
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #gcRate(long)} - GC count delta per second</li>
 *   <li>{@link #isGcStorm(long)} - GC rate > 10/sec (GC storm)</li>
 *   <li>{@link #gcTimePercent(long)} - GC time as percentage of wall time</li>
 * </ul>
 *
 * @param brokerId        Broker identifier
 * @param collectorName   GC collector name (e.g., "G1 Young Generation")
 * @param collectionCount Total GC collections since JVM start
 * @param collectionTime  Total GC time in milliseconds
 * @param timestamp       Collection time (epoch milliseconds)
 */
public record JvmGcMetrics(
    String brokerId,
    String collectorName,
    long collectionCount,
    long collectionTime,
    long timestamp
) {
    /**
     * Compact constructor with validation.
     */
    public JvmGcMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        Objects.requireNonNull(collectorName, "collectorName required");
        if (collectionCount < 0) throw new IllegalArgumentException("collectionCount must be >= 0");
        if (collectionTime < 0) throw new IllegalArgumentException("collectionTime must be >= 0");
    }

    /**
     * Calculates GC rate (collections per second) since last observation.
     *
     * @param previousCount Previous collection count
     * @param intervalMs    Time interval in milliseconds
     * @return GC rate (collections/second)
     */
    public double gcRate(long previousCount, long intervalMs) {
        if (intervalMs <= 0) return 0.0;
        long delta = collectionCount - previousCount;
        return (delta * 1000.0) / intervalMs;
    }

    /**
     * Determines if GC storm is occurring (>10 GC/sec).
     *
     * @param previousCount Previous collection count
     * @param intervalMs    Time interval in milliseconds
     * @return true if GC rate > 10/sec
     */
    public boolean isGcStorm(long previousCount, long intervalMs) {
        return gcRate(previousCount, intervalMs) > 10.0;
    }

    /**
     * Calculates GC time as percentage of wall time.
     *
     * @param previousTime Previous GC time
     * @param intervalMs   Time interval in milliseconds
     * @return GC time percentage (0.0-1.0)
     */
    public double gcTimePercent(long previousTime, long intervalMs) {
        if (intervalMs <= 0) return 0.0;
        long timeDelta = collectionTime - previousTime;
        return Math.min(1.0, (double) timeDelta / intervalMs);
    }
}
