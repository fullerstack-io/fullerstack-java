package io.fullerstack.kafka.producer.composers;

import io.fullerstack.kafka.producer.models.ProducerMetrics;
import io.fullerstack.serventis.signals.MonitorSignal;
import io.humainary.modules.serventis.monitors.api.Monitors;
import io.humainary.substrates.api.Substrates.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Cell Composer that transforms ProducerMetrics into MonitorSignals with health assessment.
 * <p>
 * Implements M18 Cell Composer pattern by providing Pipe<ProducerMetrics> that accepts
 * producer metrics and emits MonitorSignals with appropriate health conditions based on
 * latency, buffer utilization, and error rates.
 *
 * <h3>Health Assessment Thresholds:</h3>
 * <ul>
 *   <li><b>STABLE</b>: avgLatencyMs &lt; 50ms, buffer &lt; 80%, no errors</li>
 *   <li><b>DEGRADED</b>: avgLatencyMs 50-200ms, buffer 80-95%, errors &lt; 1/sec</li>
 *   <li><b>DOWN</b>: avgLatencyMs &gt; 200ms, buffer &gt; 95%, errors ≥ 1/sec</li>
 * </ul>
 *
 * <h3>Confidence Levels:</h3>
 * <ul>
 *   <li><b>CONFIRMED</b>: Metrics &lt; 5 seconds old</li>
 *   <li><b>MEASURED</b>: Metrics 5-30 seconds old</li>
 *   <li><b>TENTATIVE</b>: Metrics &gt; 30 seconds old</li>
 * </ul>
 *
 * @author Fullerstack
 * @see io.fullerstack.kafka.producer.models.ProducerMetrics
 * @see io.humainary.substrates.api.Substrates.Composer
 */
public class ProducerHealthCellComposer implements Composer<Pipe<ProducerMetrics>, MonitorSignal> {

    private static final Logger logger = LoggerFactory.getLogger(ProducerHealthCellComposer.class);

    // Health assessment thresholds
    private static final double LATENCY_STABLE_THRESHOLD_MS = 50.0;
    private static final double LATENCY_DOWN_THRESHOLD_MS = 200.0;
    private static final double BUFFER_STABLE_THRESHOLD = 0.80;    // 80%
    private static final double BUFFER_DOWN_THRESHOLD = 0.95;      // 95%
    private static final long ERROR_RATE_DOWN_THRESHOLD = 1;       // 1 error/sec

    // Confidence thresholds (milliseconds)
    private static final long FRESH_METRICS_MS = 5_000;            // 5 seconds
    private static final long RECENT_METRICS_MS = 30_000;          // 30 seconds

    @Override
    public Pipe<ProducerMetrics> compose(Channel<MonitorSignal> channel) {
        // Get infrastructure-provided Subject and output Pipe
        Subject<Channel<MonitorSignal>> channelSubject = channel.subject();
        Pipe<MonitorSignal> outputPipe = channel.pipe();

        logger.debug("Creating ProducerHealthCellComposer for subject: {}", channelSubject.name());

        // Return input Pipe that transforms ProducerMetrics → MonitorSignal
        return new Pipe<>() {
            @Override
            public void emit(ProducerMetrics metrics) {
                try {
                    // Assess producer health condition
                    Monitors.Condition condition = assessCondition(metrics);
                    Monitors.Confidence confidence = assessConfidence(metrics);
                    Map<String, String> context = buildContext(metrics);

                    // Create MonitorSignal with assessed health
                    MonitorSignal signal = switch (condition) {
                        case STABLE -> MonitorSignal.stable(channelSubject, context);
                        case DEGRADED -> MonitorSignal.degraded(channelSubject, confidence, context);
                        case DOWN -> MonitorSignal.down(channelSubject, confidence, context);
                        default -> MonitorSignal.create(channelSubject, condition, confidence, context);
                    };

                    // Log signal emission
                    if (logger.isDebugEnabled()) {
                        logger.debug(
                            "Producer {} health: {} ({}), latency: {}ms, buffer: {}%",
                            metrics.producerId(),
                            condition,
                            confidence,
                            String.format("%.1f", metrics.avgLatencyMs()),
                            String.format("%.1f", metrics.bufferUtilization() * 100)
                        );
                    }

                    // Emit transformed signal
                    outputPipe.emit(signal);

                } catch (Exception e) {
                    logger.error("Failed to transform ProducerMetrics for producer {}",
                        metrics.producerId(), e);
                }
            }
        };
    }

    /**
     * Assess producer health condition based on metrics.
     * <p>
     * Evaluates latency, buffer utilization, and error rates to determine
     * overall producer health condition.
     *
     * @param metrics ProducerMetrics to assess
     * @return Health condition (STABLE, DEGRADED, or DOWN)
     */
    private Monitors.Condition assessCondition(ProducerMetrics metrics) {
        double avgLatency = metrics.avgLatencyMs();
        double bufferUtil = metrics.bufferUtilization();
        long errorRate = metrics.recordErrorRate();

        // DOWN: Critical issues - producer may be blocking or failing
        if (avgLatency > LATENCY_DOWN_THRESHOLD_MS
            || bufferUtil > BUFFER_DOWN_THRESHOLD
            || errorRate >= ERROR_RATE_DOWN_THRESHOLD) {
            return Monitors.Condition.DOWN;
        }

        // DEGRADED: Performance degradation - producer under stress
        if (avgLatency > LATENCY_STABLE_THRESHOLD_MS
            || bufferUtil > BUFFER_STABLE_THRESHOLD
            || errorRate > 0) {
            return Monitors.Condition.DEGRADED;
        }

        // STABLE: Healthy producer
        return Monitors.Condition.STABLE;
    }

    /**
     * Assess confidence level based on metric age.
     * <p>
     * Fresh metrics warrant higher confidence than stale metrics.
     *
     * @param metrics ProducerMetrics to assess
     * @return Confidence level (CONFIRMED, MEASURED, or TENTATIVE)
     */
    private Monitors.Confidence assessConfidence(ProducerMetrics metrics) {
        long ageMs = metrics.ageMs();

        if (ageMs < FRESH_METRICS_MS) {
            return Monitors.Confidence.CONFIRMED;
        } else if (ageMs < RECENT_METRICS_MS) {
            return Monitors.Confidence.MEASURED;
        } else {
            return Monitors.Confidence.TENTATIVE;
        }
    }

    /**
     * Build signal context payload with key metrics.
     * <p>
     * Includes all relevant metrics for downstream interpretation and debugging.
     *
     * @param metrics ProducerMetrics to extract context from
     * @return Context map with string representations of metrics
     */
    private Map<String, String> buildContext(ProducerMetrics metrics) {
        Map<String, String> context = new HashMap<>();

        context.put("producerId", metrics.producerId());
        context.put("sendRate", String.valueOf(metrics.sendRate()));
        context.put("avgLatencyMs", String.format("%.2f", metrics.avgLatencyMs()));
        context.put("p99LatencyMs", String.format("%.2f", metrics.p99LatencyMs()));
        context.put("batchSizeAvg", String.valueOf(metrics.batchSizeAvg()));
        context.put("compressionRatio", String.format("%.2f", metrics.compressionRatio()));
        context.put("bufferUtilization", String.format("%.2f", metrics.bufferUtilization()));
        context.put("bufferAvailableBytes", String.valueOf(metrics.bufferAvailableBytes()));
        context.put("bufferTotalBytes", String.valueOf(metrics.bufferTotalBytes()));
        context.put("ioWaitRatio", String.valueOf(metrics.ioWaitRatio()));
        context.put("recordErrorRate", String.valueOf(metrics.recordErrorRate()));
        context.put("timestamp", Instant.ofEpochMilli(metrics.timestamp()).toString());
        context.put("ageMs", String.valueOf(metrics.ageMs()));

        return context;
    }
}
