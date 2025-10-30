package io.fullerstack.kafka.broker.baseline;

import java.time.Instant;
import java.time.Duration;

/**
 * Service for determining baseline expectations and trends for Kafka entities.
 * <p>
 * Critical for signal-first architecture: sensors must compare current values
 * against baselines to emit meaningful signals rather than raw data.
 * <p>
 * <b>Why This Matters:</b>
 * Traditional monitoring emits raw metrics (heap=85%) without context.
 * Semiotic observability emits interpreted signals ("DEGRADED - heap 85% vs 60% baseline").
 * <p>
 * Baselines provide the context needed for interpretation AT THE POINT OF OBSERVATION.
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * // In sensor, compare to baseline
 * double currentHeap = jmx.getHeapUsedPercent();
 * double expectedHeap = baselineService.getExpectedHeap("broker-1", Instant.now());
 *
 * if (currentHeap > expectedHeap * 1.3) {
 *     // Emit DEGRADED signal with context
 *     emit(MonitorSignal.degraded(subject, Map.of(
 *         "value", currentHeap,
 *         "baseline", expectedHeap,
 *         "assessment", "Memory pressure detected"
 *     )));
 * }
 * }</pre>
 *
 * <h3>Implementation Strategies:</h3>
 * <ul>
 *   <li><b>Simple</b>: Static thresholds (heap baseline always 70%)</li>
 *   <li><b>Time-aware</b>: Hourly patterns (peak hours vs off-peak)</li>
 *   <li><b>Historical</b>: Rolling averages from past data</li>
 *   <li><b>ML-based</b>: Learned patterns with anomaly detection</li>
 * </ul>
 *
 * @author Fullerstack
 * @see io.fullerstack.kafka.broker.baseline.SimpleBaselineService
 */
public interface BaselineService {

    /**
     * Get expected heap usage percentage for a broker at a given time.
     * <p>
     * Considers temporal patterns (peak hours), historical averages, and
     * entity-specific characteristics.
     *
     * @param brokerId The broker identifier
     * @param time     The time to get baseline for
     * @return Expected heap usage percentage (0.0-100.0)
     */
    double getExpectedHeapPercent(String brokerId, Instant time);

    /**
     * Get expected CPU usage for a broker at a given time.
     *
     * @param brokerId The broker identifier
     * @param time     The time to get baseline for
     * @return Expected CPU usage (0.0-1.0)
     */
    double getExpectedCpuUsage(String brokerId, Instant time);

    /**
     * Get expected request rate for a broker at a given time.
     *
     * @param brokerId The broker identifier
     * @param time     The time to get baseline for
     * @return Expected requests per second
     */
    long getExpectedRequestRate(String brokerId, Instant time);

    /**
     * Get expected producer send latency for a topic.
     *
     * @param producerId The producer identifier
     * @param topic      The topic name
     * @return Expected latency in milliseconds
     */
    long getExpectedProducerLatency(String producerId, String topic);

    /**
     * Get trend for a metric over a time window.
     * <p>
     * Useful for detecting transitions: "heap was stable at 60%, now increasing to 85%"
     *
     * @param entityId The entity identifier (broker, producer, etc.)
     * @param metric   The metric name (heap, cpu, latency, etc.)
     * @param window   The time window to analyze
     * @return Trend description: "stable", "increasing", "decreasing", "erratic"
     */
    String getTrend(String entityId, String metric, Duration window);

    /**
     * Get confidence level for a baseline prediction.
     * <p>
     * Higher confidence when:
     * - More historical data available
     * - Stable patterns observed
     * - Recent data fresh
     * <p>
     * Lower confidence when:
     * - Limited historical data
     * - Erratic patterns
     * - Data stale or missing
     *
     * @param entityId The entity identifier
     * @param metric   The metric name
     * @return Confidence (0.0-1.0)
     */
    double getConfidence(String entityId, String metric);

    /**
     * Record an observed value for future baseline calculations.
     * <p>
     * Implementations may use this to build historical baselines,
     * learn patterns, or train ML models.
     *
     * @param entityId  The entity identifier
     * @param metric    The metric name
     * @param value     The observed value
     * @param timestamp When the value was observed
     */
    void recordObservation(String entityId, String metric, double value, Instant timestamp);

    /**
     * Check if baseline data is available for an entity.
     *
     * @param entityId The entity identifier
     * @return true if baseline data exists
     */
    boolean hasBaseline(String entityId);
}
