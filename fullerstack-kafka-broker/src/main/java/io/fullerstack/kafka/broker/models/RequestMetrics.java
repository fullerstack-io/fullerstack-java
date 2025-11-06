package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing request metrics for a Kafka broker.
 *
 * <p>Tracks request rates, latency, queue times, and errors to detect
 * request processing issues and SLA violations.
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #isSlaViolation()} - Latency exceeds SLA threshold (critical)</li>
 *   <li>{@link #hasQueueBacklog()} - Request queue time is high (warning)</li>
 *   <li>{@link #hasErrors()} - Error rate is elevated</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * RequestMetrics metrics = new RequestMetrics(
 *     "broker-1",
 *     RequestType.PRODUCE,
 *     150.0,      // 150 requests/sec
 *     25.5,       // 25.5ms avg total time
 *     5.2,        // 5.2ms request queue time
 *     2.1,        // 2.1ms response queue time
 *     2.5,        // 2.5 errors/sec
 *     100.0,      // SLA threshold 100ms
 *     System.currentTimeMillis()
 * );
 *
 * if (metrics.isSlaViolation()) {
 *     alert("Request SLA violation detected!");
 * }
 * }</pre>
 *
 * @param brokerId             Broker identifier (e.g., "broker-1", "0")
 * @param requestType          Type of request (PRODUCE, FETCH, etc.)
 * @param requestsPerSec       Request rate (requests/second)
 * @param totalTimeMs          Average total request time in milliseconds
 * @param requestQueueTimeMs   Average time in request queue (milliseconds)
 * @param responseQueueTimeMs  Average time in response queue (milliseconds)
 * @param errorsPerSec         Error rate (errors/second)
 * @param slaThresholdMs       SLA threshold for latency (milliseconds)
 * @param timestamp            Collection time (epoch milliseconds)
 *
 * @see RequestType
 */
public record RequestMetrics(
    String brokerId,
    RequestType requestType,
    double requestsPerSec,
    double totalTimeMs,
    double requestQueueTimeMs,
    double responseQueueTimeMs,
    double errorsPerSec,
    double slaThresholdMs,
    long timestamp
) {
    /**
     * Compact constructor with validation rules.
     *
     * @throws NullPointerException     if brokerId or requestType is null
     * @throws IllegalArgumentException if any metric is negative
     */
    public RequestMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        Objects.requireNonNull(requestType, "requestType required");
        if (requestsPerSec < 0.0) {
            throw new IllegalArgumentException("requestsPerSec must be >= 0, got: " + requestsPerSec);
        }
        if (totalTimeMs < 0.0) {
            throw new IllegalArgumentException("totalTimeMs must be >= 0, got: " + totalTimeMs);
        }
        if (requestQueueTimeMs < 0.0) {
            throw new IllegalArgumentException("requestQueueTimeMs must be >= 0, got: " + requestQueueTimeMs);
        }
        if (responseQueueTimeMs < 0.0) {
            throw new IllegalArgumentException("responseQueueTimeMs must be >= 0, got: " + responseQueueTimeMs);
        }
        if (errorsPerSec < 0.0) {
            throw new IllegalArgumentException("errorsPerSec must be >= 0, got: " + errorsPerSec);
        }
        if (slaThresholdMs < 0.0) {
            throw new IllegalArgumentException("slaThresholdMs must be >= 0, got: " + slaThresholdMs);
        }
    }

    /**
     * Determines if request latency violates SLA (critical state).
     * <p>
     * SLA violation occurs when average total time exceeds the configured threshold.
     *
     * @return true if totalTimeMs >= slaThresholdMs
     */
    public boolean isSlaViolation() {
        return totalTimeMs >= slaThresholdMs;
    }

    /**
     * Determines if request queue has backlog (warning state).
     * <p>
     * Backlog is defined as request queue time exceeding 20ms,
     * indicating requests are waiting before processing.
     *
     * @return true if requestQueueTimeMs >= 20.0
     */
    public boolean hasQueueBacklog() {
        return requestQueueTimeMs >= 20.0;
    }

    /**
     * Determines if error rate is elevated (warning state).
     * <p>
     * Elevated errors occur when error rate exceeds 1.0 errors/sec.
     *
     * @return true if errorsPerSec >= 1.0
     */
    public boolean hasErrors() {
        return errorsPerSec >= 1.0;
    }

    /**
     * Calculates the percentage of time spent in queues.
     * <p>
     * Queue overhead represents the proportion of total time spent waiting
     * in request and response queues rather than actual processing.
     *
     * @return queue overhead percentage (0.0-1.0), or 0.0 if totalTimeMs is 0
     */
    public double queueOverheadPercent() {
        if (totalTimeMs == 0.0) {
            return 0.0;
        }
        double queueTime = requestQueueTimeMs + responseQueueTimeMs;
        return Math.min(1.0, queueTime / totalTimeMs);
    }
}
