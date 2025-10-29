package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing thread pool metrics for a Kafka broker.
 *
 * <p>Tracks active threads, idle threads, capacity, and queue depth to detect
 * thread pool exhaustion and saturation conditions.
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #isExhausted()} - Less than 10% idle threads (critical)</li>
 *   <li>{@link #isDegraded()} - Less than 30% idle threads (warning)</li>
 *   <li>{@link #isHealthy()} - 30% or more idle threads (normal)</li>
 *   <li>{@link #utilizationPercent()} - Percentage of threads actively processing</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * ThreadPoolMetrics metrics = new ThreadPoolMetrics(
 *     "broker-1",
 *     ThreadPoolType.NETWORK,
 *     3,      // total threads
 *     2,      // active threads
 *     1,      // idle threads
 *     0.33,   // 33% average idle
 *     0L,     // no queue buildup
 *     1234L,  // tasks processed
 *     0L,     // no rejections
 *     System.currentTimeMillis()
 * );
 *
 * if (metrics.isExhausted()) {
 *     alert("Thread pool exhaustion detected!");
 * }
 * }</pre>
 *
 * @param brokerId             Broker identifier (e.g., "broker-1", "0")
 * @param poolType             Type of thread pool (NETWORK, IO, LOG_CLEANER)
 * @param totalThreads         Total thread pool capacity
 * @param activeThreads        Currently executing tasks
 * @param idleThreads          Available for work
 * @param avgIdlePercent       Average idle percentage (0.0-1.0)
 * @param queueSize            Pending requests in queue
 * @param totalTasksProcessed  Lifetime task count
 * @param rejectionCount       Tasks rejected due to saturation
 * @param timestamp            Collection time (epoch milliseconds)
 *
 * @see ThreadPoolType
 */
public record ThreadPoolMetrics(
    String brokerId,
    ThreadPoolType poolType,
    int totalThreads,
    int activeThreads,
    int idleThreads,
    double avgIdlePercent,
    long queueSize,
    long totalTasksProcessed,
    long rejectionCount,
    long timestamp
) {
    /**
     * Compact constructor with validation rules.
     *
     * @throws NullPointerException     if brokerId or poolType is null
     * @throws IllegalArgumentException if totalThreads or activeThreads is negative,
     *                                  or avgIdlePercent is outside [0.0, 1.0]
     */
    public ThreadPoolMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        Objects.requireNonNull(poolType, "poolType required");
        if (totalThreads < 0) {
            throw new IllegalArgumentException("totalThreads must be >= 0, got: " + totalThreads);
        }
        if (activeThreads < 0) {
            throw new IllegalArgumentException("activeThreads must be >= 0, got: " + activeThreads);
        }
        if (avgIdlePercent < 0.0 || avgIdlePercent > 1.0) {
            throw new IllegalArgumentException("avgIdlePercent must be 0.0-1.0, got: " + avgIdlePercent);
        }
    }

    /**
     * Determines if thread pool is exhausted (critical state).
     * <p>
     * Exhaustion is defined as less than 10% idle threads, indicating the pool
     * is operating near maximum capacity and at risk of request rejection.
     *
     * @return true if avgIdlePercent < 0.1 (less than 10% idle)
     */
    public boolean isExhausted() {
        return avgIdlePercent < 0.1;
    }

    /**
     * Determines if thread pool is degraded (warning state).
     * <p>
     * Degradation is defined as less than 30% idle threads, indicating the pool
     * is under moderate load and should be monitored for further degradation.
     *
     * @return true if avgIdlePercent < 0.3 (less than 30% idle)
     */
    public boolean isDegraded() {
        return avgIdlePercent < 0.3;
    }

    /**
     * Determines if thread pool is healthy (normal state).
     * <p>
     * Healthy state is defined as 30% or more idle threads, indicating the pool
     * has sufficient capacity for current workload.
     *
     * @return true if avgIdlePercent >= 0.3 (30% or more idle)
     */
    public boolean isHealthy() {
        return avgIdlePercent >= 0.3;
    }

    /**
     * Calculates thread pool utilization as a percentage.
     * <p>
     * Utilization represents the proportion of threads actively processing tasks.
     * For example, 0.75 means 75% of threads are busy.
     *
     * @return utilization percentage (0.0-1.0), or 0.0 if totalThreads is 0
     */
    public double utilizationPercent() {
        return totalThreads > 0 ? (double) activeThreads / totalThreads : 0.0;
    }
}
