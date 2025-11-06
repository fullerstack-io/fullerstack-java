package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing system-level metrics for a Kafka broker.
 *
 * <p>Tracks CPU usage and file descriptor utilization to detect resource exhaustion.
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #fdUtilization()} - File descriptor utilization (0.0-1.0)</li>
 *   <li>{@link #isFdCritical()} - FD utilization > 95%</li>
 *   <li>{@link #isCpuSaturated()} - Process CPU > 90%</li>
 * </ul>
 *
 * @param brokerId                Broker identifier
 * @param processCpuLoad          Process CPU load (0.0-1.0)
 * @param systemCpuLoad           System CPU load (0.0-1.0)
 * @param openFileDescriptorCount Current open file descriptors
 * @param maxFileDescriptorCount  Maximum file descriptors allowed
 * @param timestamp               Collection time (epoch milliseconds)
 */
public record SystemMetrics(
    String brokerId,
    double processCpuLoad,
    double systemCpuLoad,
    long openFileDescriptorCount,
    long maxFileDescriptorCount,
    long timestamp
) {
    /**
     * Compact constructor with validation.
     */
    public SystemMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        if (processCpuLoad < 0.0 || processCpuLoad > 1.0) {
            throw new IllegalArgumentException("processCpuLoad must be 0.0-1.0");
        }
        if (systemCpuLoad < 0.0 || systemCpuLoad > 1.0) {
            throw new IllegalArgumentException("systemCpuLoad must be 0.0-1.0");
        }
        if (openFileDescriptorCount < 0) {
            throw new IllegalArgumentException("openFileDescriptorCount must be >= 0");
        }
        if (maxFileDescriptorCount < 0) {
            throw new IllegalArgumentException("maxFileDescriptorCount must be >= 0");
        }
    }

    /**
     * Calculates file descriptor utilization.
     *
     * @return FD utilization (0.0-1.0), or 0.0 if max is 0
     */
    public double fdUtilization() {
        return maxFileDescriptorCount > 0
            ? (double) openFileDescriptorCount / maxFileDescriptorCount
            : 0.0;
    }

    /**
     * Determines if file descriptor utilization is critical (>95%).
     *
     * @return true if FD utilization > 0.95
     */
    public boolean isFdCritical() {
        return fdUtilization() > 0.95;
    }

    /**
     * Determines if process CPU is saturated (>90%).
     *
     * @return true if process CPU > 0.90
     */
    public boolean isCpuSaturated() {
        return processCpuLoad > 0.90;
    }
}
