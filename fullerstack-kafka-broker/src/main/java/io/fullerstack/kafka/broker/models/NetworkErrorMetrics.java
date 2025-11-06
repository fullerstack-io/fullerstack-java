package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing network error metrics for a Kafka broker.
 *
 * <p>Tracks network-level errors, connection failures, and authentication failures
 * to detect network reliability issues and security problems.
 *
 * <h3>Metrics Tracked</h3>
 * <ul>
 *   <li><b>Network Errors</b> - Errors per second (all request types)</li>
 *   <li><b>Connection Failures</b> - Failed authentication total</li>
 *   <li><b>Temporary Auth Failures</b> - Transient authentication failures</li>
 * </ul>
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #hasCriticalErrors()} - Error rate indicates critical issues</li>
 *   <li>{@link #hasAuthenticationIssues()} - Authentication failures detected</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * NetworkErrorMetrics metrics = new NetworkErrorMetrics(
 *     "broker-1",
 *     5.2,     // errors/sec
 *     150,     // total failed authentications
 *     25,      // temporary auth failures
 *     System.currentTimeMillis()
 * );
 *
 * if (metrics.hasCriticalErrors()) {
 *     alert("Critical network error rate!");
 * }
 * }</pre>
 *
 * @param brokerId                Broker identifier (e.g., "broker-1", "0")
 * @param errorsPerSec            Network errors per second (all request types)
 * @param failedAuthTotal         Cumulative failed authentication attempts
 * @param tempAuthFailureTotal    Cumulative temporary authentication failures
 * @param timestamp               Collection time (epoch milliseconds)
 */
public record NetworkErrorMetrics(
    String brokerId,
    double errorsPerSec,
    long failedAuthTotal,
    long tempAuthFailureTotal,
    long timestamp
) {
    // Error rate thresholds (configurable in production)
    private static final double CRITICAL_ERROR_RATE = 10.0;  // 10 errors/sec
    private static final double WARNING_ERROR_RATE = 5.0;    // 5 errors/sec

    /**
     * Compact constructor with validation rules.
     *
     * @throws NullPointerException     if brokerId is null
     * @throws IllegalArgumentException if errorsPerSec is negative or counts are negative
     */
    public NetworkErrorMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        if (errorsPerSec < 0.0) {
            throw new IllegalArgumentException("errorsPerSec must be >= 0.0, got: " + errorsPerSec);
        }
        if (failedAuthTotal < 0) {
            throw new IllegalArgumentException("failedAuthTotal must be >= 0, got: " + failedAuthTotal);
        }
        if (tempAuthFailureTotal < 0) {
            throw new IllegalArgumentException("tempAuthFailureTotal must be >= 0, got: " + tempAuthFailureTotal);
        }
    }

    /**
     * Determines if network has critical error rate.
     * <p>
     * Critical error rate indicates systemic network problems that may
     * cause service disruption.
     *
     * @return true if errorsPerSec >= CRITICAL_ERROR_RATE
     */
    public boolean hasCriticalErrors() {
        return errorsPerSec >= CRITICAL_ERROR_RATE;
    }

    /**
     * Determines if network has elevated error rate (warning).
     * <p>
     * Elevated error rate indicates network instability that should be monitored.
     *
     * @return true if errorsPerSec >= WARNING_ERROR_RATE
     */
    public boolean hasElevatedErrors() {
        return errorsPerSec >= WARNING_ERROR_RATE;
    }

    /**
     * Determines if broker has authentication issues.
     * <p>
     * Authentication issues indicate security configuration problems or
     * potential security attacks.
     *
     * @return true if failedAuthTotal > 0
     */
    public boolean hasAuthenticationIssues() {
        return failedAuthTotal > 0;
    }

    /**
     * Calculates error rate severity (0.0-1.0+).
     * <p>
     * Based on errors per second relative to critical threshold.
     *
     * @return severity ratio (0.0-1.0+), may exceed 1.0 if critical
     */
    public double errorSeverity() {
        return errorsPerSec / CRITICAL_ERROR_RATE;
    }
}
