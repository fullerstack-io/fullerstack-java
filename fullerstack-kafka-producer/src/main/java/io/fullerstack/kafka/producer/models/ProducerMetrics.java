package io.fullerstack.kafka.producer.models;

import java.util.Objects;

/**
 * Immutable record representing Kafka producer performance metrics collected via JMX.
 * <p>
 * Contains comprehensive producer metrics including send rate, latency, batch statistics,
 * compression, buffer utilization, and error rates. Used for producer health assessment
 * and transformation into MonitorSignals via ProducerHealthCellComposer.
 *
 * <h3>Key Metrics:</h3>
 * <ul>
 *   <li><b>Send Rate</b>: Messages per second (1-minute rate)</li>
 *   <li><b>Latency</b>: Average and P99 latency for send requests</li>
 *   <li><b>Batch Size</b>: Average messages per batch (compression effectiveness)</li>
 *   <li><b>Buffer</b>: Available vs total buffer space (backpressure indicator)</li>
 *   <li><b>Errors</b>: Failed records per second</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * ProducerMetrics metrics = new ProducerMetrics(
 *     "producer-1",
 *     1000,      // 1000 msg/sec
 *     25.5,      // 25.5ms avg latency
 *     45.0,      // 45ms P99 latency
 *     50,        // 50 messages/batch
 *     0.65,      // 65% compression ratio
 *     800_000,   // 800KB available
 *     1_000_000, // 1MB total (20% utilized)
 *     5,         // 5% IO wait
 *     0,         // No errors
 *     System.currentTimeMillis()
 * );
 *
 * if (metrics.isHealthy()) {
 *     // Producer is performing optimally
 * }
 * }</pre>
 *
 * @param producerId Producer client ID (must be unique per producer instance)
 * @param sendRate Messages sent per second (1-minute rate)
 * @param avgLatencyMs Average send request latency in milliseconds
 * @param p99LatencyMs 99th percentile send request latency in milliseconds
 * @param batchSizeAvg Average number of messages per batch
 * @param compressionRatio Compression effectiveness (0.0 = no compression, 1.0 = 100% compression)
 * @param bufferAvailableBytes Available buffer space in bytes
 * @param bufferTotalBytes Total buffer capacity in bytes
 * @param ioWaitRatio Percentage of time waiting for I/O (0-100)
 * @param recordErrorRate Failed records per second
 * @param timestamp Collection timestamp (epoch milliseconds)
 *
 * @author Fullerstack
 * @see io.fullerstack.kafka.producer.composers.ProducerHealthCellComposer
 * @see io.fullerstack.kafka.producer.sensors.ProducerMetricsCollector
 */
public record ProducerMetrics(
    String producerId,
    long sendRate,
    double avgLatencyMs,
    double p99LatencyMs,
    long batchSizeAvg,
    double compressionRatio,
    long bufferAvailableBytes,
    long bufferTotalBytes,
    int ioWaitRatio,
    long recordErrorRate,
    long timestamp
) {

    /**
     * Compact constructor with validation rules.
     * <p>
     * Validates all metric values to ensure data integrity and catch collection errors early.
     *
     * @throws NullPointerException if producerId is null
     * @throws IllegalArgumentException if any metric violates its constraints
     */
    public ProducerMetrics {
        Objects.requireNonNull(producerId, "producerId cannot be null");

        if (producerId.isBlank()) {
            throw new IllegalArgumentException("producerId cannot be blank");
        }
        if (sendRate < 0) {
            throw new IllegalArgumentException("sendRate must be >= 0, got: " + sendRate);
        }
        if (avgLatencyMs < 0) {
            throw new IllegalArgumentException("avgLatencyMs must be >= 0, got: " + avgLatencyMs);
        }
        if (p99LatencyMs < 0) {
            throw new IllegalArgumentException("p99LatencyMs must be >= 0, got: " + p99LatencyMs);
        }
        if (batchSizeAvg < 0) {
            throw new IllegalArgumentException("batchSizeAvg must be >= 0, got: " + batchSizeAvg);
        }
        if (compressionRatio < 0.0 || compressionRatio > 1.0) {
            throw new IllegalArgumentException(
                "compressionRatio must be 0.0-1.0, got: " + compressionRatio
            );
        }
        if (bufferAvailableBytes < 0) {
            throw new IllegalArgumentException(
                "bufferAvailableBytes must be >= 0, got: " + bufferAvailableBytes
            );
        }
        if (bufferTotalBytes <= 0) {
            throw new IllegalArgumentException(
                "bufferTotalBytes must be > 0, got: " + bufferTotalBytes
            );
        }
        if (bufferAvailableBytes > bufferTotalBytes) {
            throw new IllegalArgumentException(
                "bufferAvailableBytes (" + bufferAvailableBytes +
                ") cannot exceed bufferTotalBytes (" + bufferTotalBytes + ")"
            );
        }
        if (ioWaitRatio < 0 || ioWaitRatio > 100) {
            throw new IllegalArgumentException("ioWaitRatio must be 0-100, got: " + ioWaitRatio);
        }
        if (recordErrorRate < 0) {
            throw new IllegalArgumentException(
                "recordErrorRate must be >= 0, got: " + recordErrorRate
            );
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("timestamp must be > 0, got: " + timestamp);
        }
    }

    /**
     * Calculate buffer utilization as a percentage (0.0 to 1.0).
     * <p>
     * Buffer utilization is a critical metric for backpressure detection:
     * <ul>
     *   <li>&lt; 0.8 (80%) - Healthy (STABLE)</li>
     *   <li>0.8 - 0.95 (80-95%) - Warning (DEGRADED)</li>
     *   <li>&gt; 0.95 (95%) - Critical (DOWN) - Producer may block on send()</li>
     * </ul>
     *
     * @return buffer utilization ratio (0.0 = empty, 1.0 = full)
     */
    public double bufferUtilization() {
        return 1.0 - ((double) bufferAvailableBytes / bufferTotalBytes);
    }

    /**
     * Quick health check based on simple thresholds.
     * <p>
     * A producer is considered healthy if:
     * <ul>
     *   <li>Average latency &lt; 100ms (responsive)</li>
     *   <li>Buffer utilization &lt; 90% (no backpressure)</li>
     *   <li>No record errors (reliable)</li>
     * </ul>
     *
     * <p>
     * <b>Note</b>: For more sophisticated health assessment with confidence levels
     * and multiple conditions (STABLE/DEGRADED/DOWN), use ProducerHealthCellComposer.
     *
     * @return true if producer is performing optimally, false otherwise
     * @see io.fullerstack.kafka.producer.composers.ProducerHealthCellComposer#assessCondition
     */
    public boolean isHealthy() {
        return avgLatencyMs < 100
            && bufferUtilization() < 0.9
            && recordErrorRate == 0;
    }

    /**
     * Calculate the age of these metrics in milliseconds.
     * <p>
     * Stale metrics (&gt; 30 seconds old) should be treated with lower confidence
     * in health assessment.
     *
     * @return age of metrics in milliseconds since collection
     */
    public long ageMs() {
        return System.currentTimeMillis() - timestamp;
    }

    /**
     * Check if metrics are fresh (collected within last 5 seconds).
     * <p>
     * Fresh metrics warrant CONFIRMED confidence level in health assessment.
     *
     * @return true if metrics are less than 5 seconds old
     */
    public boolean isFresh() {
        return ageMs() < 5000;
    }

    /**
     * Check if metrics are recent (collected within last 30 seconds).
     * <p>
     * Recent but not fresh metrics warrant MEASURED confidence level.
     *
     * @return true if metrics are less than 30 seconds old
     */
    public boolean isRecent() {
        return ageMs() < 30000;
    }
}
