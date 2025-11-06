package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.LogMetrics;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Emits Counters and Gauges signals for log metrics using Serventis RC1 vocabulary.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This monitor emits signals with appropriate Serventis API vocabulary based on metric type:
 * <ul>
 *   <li><b>Counters</b> - Log flush rate, offsets (monotonic values)</li>
 *   <li><b>Gauges</b> - Log size, segment count (bidirectional values)</li>
 * </ul>
 *
 * <h3>Signal Emission Logic:</h3>
 * <pre>
 * Log Flush Rate (Counters):
 *   - INCREMENT: Each flush batch
 *
 * Log Size (Gauges):
 *   - INCREMENT: Log growing
 *   - OVERFLOW: Near retention limit (≥95%)
 *
 * Log Segments (Gauges):
 *   - INCREMENT: Segment count increasing
 *   - OVERFLOW: Excessive segments (&gt;50)
 *
 * Offsets (Counters):
 *   - INCREMENT: Offset advancing
 * </pre>
 *
 * <p><b>Multi-Entity Pattern:</b> This monitor handles metrics for multiple
 * topic-partition entities. Previous values are tracked per partition.
 *
 * <p><b>Note:</b> Simple threshold-based emission for Layer 2. Contextual assessment
 * will be added in Epic 2 via Observers (Layer 4).
 */
public class LogMetricsMonitor {

    private static final Logger logger = LoggerFactory.getLogger(LogMetricsMonitor.class);

    // Simple fixed thresholds
    private static final int EXCESSIVE_SEGMENTS_THRESHOLD = 50;
    private static final double RETENTION_WARNING_PERCENT = 0.95;

    private final Name circuitName;
    private final Channel<Counters.Sign> counterChannel;
    private final Channel<Gauges.Sign> gaugeChannel;

    // Instruments (created from channels via RC7 composers)
    private final Counters.Counter flushCounter;
    private final Counters.Counter endOffsetCounter;
    private final Counters.Counter startOffsetCounter;
    private final Gauges.Gauge sizeGauge;
    private final Gauges.Gauge segmentGauge;

    // Previous values for delta calculation (per partition)
    private final Map<String, PreviousLogMetrics> previousMetrics = new HashMap<>();

    /**
     * Creates a LogMetricsMonitor.
     *
     * @param circuitName    Circuit name for logging
     * @param counterChannel Channel to emit Counters.Sign
     * @param gaugeChannel   Channel to emit Gauges.Sign
     * @throws NullPointerException if any parameter is null
     */
    public LogMetricsMonitor(
        Name circuitName,
        Channel<Counters.Sign> counterChannel,
        Channel<Gauges.Sign> gaugeChannel
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.counterChannel = Objects.requireNonNull(counterChannel, "counterChannel cannot be null");
        this.gaugeChannel = Objects.requireNonNull(gaugeChannel, "gaugeChannel cannot be null");

        // Create instruments from channels via RC7 composers
        this.flushCounter = Counters.composer(counterChannel);
        this.endOffsetCounter = Counters.composer(counterChannel);
        this.startOffsetCounter = Counters.composer(counterChannel);
        this.sizeGauge = Gauges.composer(gaugeChannel);
        this.segmentGauge = Gauges.composer(gaugeChannel);
    }

    /**
     * Emits Counters/Gauges signals for log metrics.
     *
     * @param metrics Log metrics from JMX collector
     * @throws NullPointerException if metrics is null
     */
    public void emit(LogMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            String partitionId = metrics.partitionId();
            PreviousLogMetrics prev = previousMetrics.getOrDefault(
                partitionId,
                new PreviousLogMetrics(0.0, 0, 0L, 0L, 0L)
            );

            emitFlushRateSignals(metrics);
            emitLogSizeSignals(metrics, prev);
            emitSegmentCountSignals(metrics, prev);
            emitOffsetSignals(metrics, prev);

            // Update previous values for next delta
            previousMetrics.put(partitionId, new PreviousLogMetrics(
                metrics.flushRatePerSec(),
                metrics.numSegments(),
                metrics.sizeBytes(),
                metrics.logEndOffset(),
                metrics.logStartOffset()
            ));

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted log signals for {}.{}: size={}MB, segments={}, endOffset={}",
                    metrics.brokerId(),
                    partitionId,
                    metrics.sizeBytes() / 1_048_576,
                    metrics.numSegments(),
                    metrics.logEndOffset());
            }
        } catch (Exception e) {
            logger.error("Failed to emit log signals for {}.{}: {}",
                metrics.brokerId(),
                metrics.partitionId(),
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }

    /**
     * Emits Counters.Sign for log flush rate.
     */
    private void emitFlushRateSignals(LogMetrics metrics) {
        if (metrics.flushRatePerSec() > 0) {
            flushCounter.increment();
        }
    }

    /**
     * Emits Gauges.Sign for log size.
     */
    private void emitLogSizeSignals(LogMetrics metrics, PreviousLogMetrics prev) {
        long currentSize = metrics.sizeBytes();

        // Check for retention limit warning
        if (metrics.isNearRetentionLimit()) {
            sizeGauge.overflow();
        } else if (currentSize > prev.sizeBytes) {
            sizeGauge.increment();
        } else if (currentSize < prev.sizeBytes) {
            sizeGauge.decrement();
        } else {
            // No change - emit increment to show activity
            sizeGauge.increment();
        }
    }

    /**
     * Emits Gauges.Sign for segment count.
     */
    private void emitSegmentCountSignals(LogMetrics metrics, PreviousLogMetrics prev) {
        int currentSegments = metrics.numSegments();

        // Check for excessive segments
        if (metrics.hasExcessiveSegments()) {
            segmentGauge.overflow();
        } else if (currentSegments > prev.numSegments) {
            segmentGauge.increment();
        } else if (currentSegments < prev.numSegments) {
            segmentGauge.decrement();
        } else {
            // No change - emit increment to show activity
            segmentGauge.increment();
        }
    }

    /**
     * Emits Counters.Sign for log offsets.
     */
    private void emitOffsetSignals(LogMetrics metrics, PreviousLogMetrics prev) {
        // Log end offset (monotonically increasing)
        if (metrics.logEndOffset() > prev.logEndOffset) {
            endOffsetCounter.increment();
        }

        // Log start offset (can increase due to cleanup)
        if (metrics.logStartOffset() > prev.logStartOffset) {
            startOffsetCounter.increment();
        }
    }

    /**
     * Record to track previous metric values per partition.
     */
    private record PreviousLogMetrics(
        double flushRate,
        int numSegments,
        long sizeBytes,
        long logEndOffset,
        long logStartOffset
    ) {}
}
