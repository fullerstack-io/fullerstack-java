package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing JVM memory metrics for a Kafka broker.
 *
 * <p>Tracks heap and non-heap memory usage to detect memory pressure
 * and out-of-memory conditions.
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #heapUtilization()} - Heap utilization percentage (0.0-1.0)</li>
 *   <li>{@link #isHeapCritical()} - Heap > 95% (critical memory pressure)</li>
 *   <li>{@link #isHeapDegraded()} - Heap > 85% (degraded performance)</li>
 *   <li>{@link #isHeapHealthy()} - Heap < 75% (normal operation)</li>
 * </ul>
 *
 * @param brokerId         Broker identifier
 * @param heapUsed         Heap memory used (bytes)
 * @param heapMax          Heap memory maximum (bytes)
 * @param heapCommitted    Heap memory committed (bytes)
 * @param nonHeapUsed      Non-heap memory used (bytes)
 * @param nonHeapMax       Non-heap memory maximum (bytes)
 * @param nonHeapCommitted Non-heap memory committed (bytes)
 * @param timestamp        Collection time (epoch milliseconds)
 */
public record JvmMemoryMetrics(
    String brokerId,
    long heapUsed,
    long heapMax,
    long heapCommitted,
    long nonHeapUsed,
    long nonHeapMax,
    long nonHeapCommitted,
    long timestamp
) {
    /**
     * Compact constructor with validation.
     */
    public JvmMemoryMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        if (heapUsed < 0) throw new IllegalArgumentException("heapUsed must be >= 0");
        if (heapMax < 0) throw new IllegalArgumentException("heapMax must be >= 0");
        if (nonHeapUsed < 0) throw new IllegalArgumentException("nonHeapUsed must be >= 0");
    }

    /**
     * Calculates heap utilization as a percentage.
     *
     * @return utilization (0.0-1.0), or 0.0 if heapMax is 0
     */
    public double heapUtilization() {
        return heapMax > 0 ? (double) heapUsed / heapMax : 0.0;
    }

    /**
     * Determines if heap is critical (>95% used).
     *
     * @return true if heap utilization > 0.95
     */
    public boolean isHeapCritical() {
        return heapUtilization() > 0.95;
    }

    /**
     * Determines if heap is degraded (>85% used).
     *
     * @return true if heap utilization > 0.85
     */
    public boolean isHeapDegraded() {
        return heapUtilization() > 0.85;
    }

    /**
     * Determines if heap is healthy (<75% used).
     *
     * @return true if heap utilization < 0.75
     */
    public boolean isHeapHealthy() {
        return heapUtilization() < 0.75;
    }
}
