package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing JVM threading metrics for a Kafka broker.
 *
 * <p>Tracks total thread count and daemon thread count to detect thread leaks.
 *
 * @param brokerId         Broker identifier
 * @param threadCount      Total live threads (daemon + non-daemon)
 * @param daemonThreadCount Total live daemon threads
 * @param timestamp        Collection time (epoch milliseconds)
 */
public record JvmThreadingMetrics(
    String brokerId,
    int threadCount,
    int daemonThreadCount,
    long timestamp
) {
    /**
     * Compact constructor with validation.
     */
    public JvmThreadingMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        if (threadCount < 0) throw new IllegalArgumentException("threadCount must be >= 0");
        if (daemonThreadCount < 0) throw new IllegalArgumentException("daemonThreadCount must be >= 0");
        if (daemonThreadCount > threadCount) {
            throw new IllegalArgumentException("daemonThreadCount cannot exceed threadCount");
        }
    }

    /**
     * Calculates non-daemon thread count.
     *
     * @return non-daemon threads
     */
    public int nonDaemonThreadCount() {
        return threadCount - daemonThreadCount;
    }
}
