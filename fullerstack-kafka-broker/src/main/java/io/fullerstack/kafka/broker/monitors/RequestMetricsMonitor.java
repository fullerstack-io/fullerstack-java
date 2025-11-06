package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.RequestMetrics;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Probes;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Emits Counters, Gauges, and Probes signals for request metrics using Serventis RC1 vocabulary.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This monitor emits signals with appropriate Serventis API vocabulary based on metric type:
 * <ul>
 *   <li><b>Counters</b> - Request rates (Produce/Fetch requests per second)</li>
 *   <li><b>Gauges</b> - Latency and queue times (bidirectional values)</li>
 *   <li><b>Probes</b> - Error tracking (Operation=REQUEST, Outcome=FAILURE)</li>
 * </ul>
 *
 * <h3>Signal Emission Logic:</h3>
 * <pre>
 * Request Rate (Counters):
 *   - INCREMENT: Each request batch
 *   - OVERFLOW: Rate spike (&gt;2x baseline)
 *
 * Latency (Gauges):
 *   - INCREMENT: Latency rising
 *   - DECREMENT: Latency improving
 *   - OVERFLOW: SLA violation (exceeds threshold)
 *
 * Errors (Probes):
 *   - Operation=REQUEST, Role=SERVER, Outcome=FAILURE
 * </pre>
 *
 * <p><b>Note:</b> Simple threshold-based emission for Layer 2. Contextual assessment
 * with baselines and trends will be added in Epic 2 via Observers (Layer 4).
 */
public class RequestMetricsMonitor {

    private static final Logger logger = LoggerFactory.getLogger(RequestMetricsMonitor.class);

    // Simple fixed thresholds (no baseline - that's Layer 4)
    private static final double QUEUE_TIME_WARNING_MS = 20.0;  // 20ms queue time = warning

    private final Name circuitName;
    private final Channel<Counters.Sign> counterChannel;
    private final Channel<Gauges.Sign> gaugeChannel;
    private final Channel<Probes.Signal> probeChannel;

    // Instruments (created from channels via RC7 composers)
    private final Counters.Counter requestRateCounter;
    private final Counters.Counter errorCounter;
    private final Gauges.Gauge latencyGauge;
    private final Gauges.Gauge requestQueueGauge;
    private final Gauges.Gauge responseQueueGauge;
    private final Probes.Probe errorProbe;

    // Previous values for delta calculation
    private double previousRequestRate = 0.0;
    private double previousLatency = 0.0;

    /**
     * Creates a RequestMetricsMonitor.
     *
     * @param circuitName    Circuit name for logging
     * @param counterChannel Channel to emit Counters.Sign
     * @param gaugeChannel   Channel to emit Gauges.Sign
     * @param probeChannel   Channel to emit Probes.Signal
     * @throws NullPointerException if any parameter is null
     */
    public RequestMetricsMonitor(
        Name circuitName,
        Channel<Counters.Sign> counterChannel,
        Channel<Gauges.Sign> gaugeChannel,
        Channel<Probes.Signal> probeChannel
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.counterChannel = Objects.requireNonNull(counterChannel, "counterChannel cannot be null");
        this.gaugeChannel = Objects.requireNonNull(gaugeChannel, "gaugeChannel cannot be null");
        this.probeChannel = Objects.requireNonNull(probeChannel, "probeChannel cannot be null");

        // Create instruments from channels via RC7 composers
        this.requestRateCounter = Counters.composer(counterChannel);
        this.errorCounter = Counters.composer(counterChannel);
        this.latencyGauge = Gauges.composer(gaugeChannel);
        this.requestQueueGauge = Gauges.composer(gaugeChannel);
        this.responseQueueGauge = Gauges.composer(gaugeChannel);
        this.errorProbe = Probes.composer(probeChannel);
    }

    /**
     * Emits Counters/Gauges/Probes signals for request metrics.
     *
     * @param metrics Request metrics from JMX collector
     * @throws NullPointerException if metrics is null
     */
    public void emit(RequestMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            emitRequestRateSignals(metrics);
            emitLatencySignals(metrics);
            emitQueueTimeSignals(metrics);
            emitErrorSignals(metrics);

            // Update previous values for next delta
            previousRequestRate = metrics.requestsPerSec();
            previousLatency = metrics.totalTimeMs();

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted request signals for {}.{}: rate={}/s, latency={}ms, errors={}/s",
                    metrics.brokerId(),
                    metrics.requestType().name(),
                    String.format("%.1f", metrics.requestsPerSec()),
                    String.format("%.1f", metrics.totalTimeMs()),
                    String.format("%.1f", metrics.errorsPerSec()));
            }
        } catch (java.lang.Exception e) {
            logger.error("Failed to emit request signals for {}.{}: {}",
                metrics.brokerId(),
                metrics.requestType().name(),
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }

    /**
     * Emits Counters.Sign for request rate.
     */
    private void emitRequestRateSignals(RequestMetrics metrics) {
        double currentRate = metrics.requestsPerSec();

        // Simple spike detection: >2x previous rate = OVERFLOW
        if (previousRequestRate > 0 && currentRate > previousRequestRate * 2.0) {
            requestRateCounter.overflow();
        } else if (currentRate > 0) {
            requestRateCounter.increment();
        }
    }

    /**
     * Emits Gauges.Sign for request latency.
     */
    private void emitLatencySignals(RequestMetrics metrics) {
        double currentLatency = metrics.totalTimeMs();

        // SLA violation check
        if (metrics.isSlaViolation()) {
            latencyGauge.overflow();
        } else if (currentLatency > previousLatency) {
            latencyGauge.increment();
        } else if (currentLatency < previousLatency) {
            latencyGauge.decrement();
        } else {
            // No change - emit increment to show activity
            latencyGauge.increment();
        }
    }

    /**
     * Emits Gauges.Sign for queue times (request and response queues).
     */
    private void emitQueueTimeSignals(RequestMetrics metrics) {
        // Request queue time
        if (metrics.requestQueueTimeMs() >= QUEUE_TIME_WARNING_MS) {
            requestQueueGauge.overflow();
        } else {
            requestQueueGauge.increment();
        }

        // Response queue time
        if (metrics.responseQueueTimeMs() >= QUEUE_TIME_WARNING_MS) {
            responseQueueGauge.overflow();
        } else {
            responseQueueGauge.increment();
        }
    }

    /**
     * Emits Counters.Sign and Probes.Signal for errors.
     */
    private void emitErrorSignals(RequestMetrics metrics) {
        if (metrics.errorsPerSec() > 0) {
            // Emit counter for error rate
            errorCounter.increment();

            // Emit probe for error tracking (request processing failed)
            errorProbe.fail();
        }
    }
}
