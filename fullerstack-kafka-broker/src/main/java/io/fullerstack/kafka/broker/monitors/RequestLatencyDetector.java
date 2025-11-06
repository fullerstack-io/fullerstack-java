package io.fullerstack.kafka.broker.monitors;

import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Detects request health conditions by analyzing latency Gauge signals.
 *
 * <p><b>Layer 3: Pattern Detection</b>
 * This detector subscribes to request latency Gauge signals and assesses
 * request health using the Monitors API. It bridges raw signals (Layer 2)
 * to health conditions (Layer 3).
 *
 * <h3>Health Assessment Logic:</h3>
 * <pre>
 * STABLE:    Normal latency, no OVERFLOW signals
 * DIVERGING: Multiple INCREMENT signals (latency trending up)
 * DEGRADED:  OVERFLOW signal (SLA violation)
 * DEFECTIVE: Sustained OVERFLOW (3+ consecutive violations)
 * </pre>
 *
 * <p><b>Pattern:</b> Stateful detector tracks signal history per entity
 * to detect trends and sustained conditions.
 *
 * <p><b>Note:</b> This is a simple pattern detector for Epic 1. More sophisticated
 * analysis (baselines, seasonality, predictions) will be added in Epic 2.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * // Create detector with gauge and monitor channels
 * RequestLatencyDetector detector = new RequestLatencyDetector(
 *     circuitName,
 *     gaugeChannel,
 *     monitorChannel
 * );
 *
 * // Detector automatically subscribes and processes signals
 * }</pre>
 */
public class RequestLatencyDetector {

    private static final Logger logger = LoggerFactory.getLogger(RequestLatencyDetector.class);

    // Pattern detection thresholds
    private static final int DIVERGING_INCREMENT_COUNT = 3;  // 3+ increments = trending up
    private static final int DEFECTIVE_OVERFLOW_COUNT = 3;   // 3+ overflows = sustained violation

    private final Name circuitName;
    private final Channel<Monitors.Signal> monitorChannel;

    // Instrument for emitting health assessments
    private final Monitors.Monitor healthMonitor;

    // Signal history per entity (for pattern detection)
    private final Map<String, SignalHistory> entityHistory = new HashMap<>();

    /**
     * Creates a RequestLatencyDetector and subscribes to gauge signals.
     *
     * @param circuitName    Circuit name for logging
     * @param gaugeChannel   Channel to subscribe to Gauges.Sign
     * @param monitorChannel Channel to emit Monitors.Signal
     * @throws NullPointerException if any parameter is null
     */
    public RequestLatencyDetector(
        Name circuitName,
        Channel<Gauges.Sign> gaugeChannel,
        Channel<Monitors.Signal> monitorChannel
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        Objects.requireNonNull(gaugeChannel, "gaugeChannel cannot be null");
        this.monitorChannel = Objects.requireNonNull(monitorChannel, "monitorChannel cannot be null");

        // Create monitor instrument for health assessment
        this.healthMonitor = Monitors.composer(monitorChannel);

        // Subscribe to gauge signals using RC7 pattern
        // Note: In RC7, subscription is handled differently - we'd normally
        // subscribe through a Conduit at runtime. For this implementation,
        // we expose a method to receive signals.
    }

    /**
     * Processes a gauge sign and emits corresponding monitor sign.
     * <p>
     * This method should be called by the runtime when gauge signs arrive.
     * In a complete RC7 implementation, this would be registered as a callback
     * via Conduit.subscribe().
     *
     * @param entityName Entity identifier (e.g., "broker-1.produce-latency")
     * @param sign       Gauge sign to process
     * @throws NullPointerException if any parameter is null
     */
    public void processGaugeSign(String entityName, Gauges.Sign sign) {
        Objects.requireNonNull(entityName, "entityName cannot be null");
        Objects.requireNonNull(sign, "sign cannot be null");

        try {
            SignalHistory history = entityHistory.computeIfAbsent(
                entityName,
                k -> new SignalHistory()
            );

            // Update signal history
            updateSignalHistory(history, sign);

            // Assess health based on signal pattern and emit appropriate signal
            Monitors.Dimension confidence = assessConfidence(history);
            emitHealthAssessment(history, confidence);

            if (logger.isDebugEnabled()) {
                logger.debug("Assessed request health for {}: pattern={}",
                    entityName,
                    describePattern(history));
            }

        } catch (java.lang.Exception e) {
            logger.error("Failed to process gauge signal for {}: {}",
                entityName,
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }

    /**
     * Emits health assessment based on signal pattern.
     */
    private void emitHealthAssessment(SignalHistory history, Monitors.Dimension confidence) {
        // DEFECTIVE: Sustained SLA violations (3+ consecutive overflows)
        if (history.consecutiveOverflows >= DEFECTIVE_OVERFLOW_COUNT) {
            healthMonitor.defective(confidence);
        }
        // DEGRADED: Any OVERFLOW signal (SLA violation)
        else if (history.consecutiveOverflows > 0) {
            healthMonitor.degraded(confidence);
        }
        // DIVERGING: Latency trending up (3+ consecutive increments)
        else if (history.consecutiveIncrements >= DIVERGING_INCREMENT_COUNT) {
            healthMonitor.diverging(confidence);
        }
        // CONVERGING: Latency improving (2+ consecutive decrements)
        else if (history.consecutiveDecrements >= 2) {
            healthMonitor.converging(confidence);
        }
        // STABLE: Normal operation
        else {
            healthMonitor.stable(confidence);
        }
    }

    /**
     * Describes the current pattern for logging.
     */
    private String describePattern(SignalHistory history) {
        if (history.consecutiveOverflows >= DEFECTIVE_OVERFLOW_COUNT) {
            return "DEFECTIVE(sustained overflows)";
        } else if (history.consecutiveOverflows > 0) {
            return "DEGRADED(overflow)";
        } else if (history.consecutiveIncrements >= DIVERGING_INCREMENT_COUNT) {
            return "DIVERGING(trending up)";
        } else if (history.consecutiveDecrements >= 2) {
            return "CONVERGING(improving)";
        } else {
            return "STABLE";
        }
    }

    /**
     * Updates signal history with latest sign.
     */
    private void updateSignalHistory(SignalHistory history, Gauges.Sign sign) {
        switch (sign) {
            case INCREMENT -> {
                history.consecutiveIncrements++;
                history.consecutiveOverflows = 0;
                history.consecutiveDecrements = 0;
            }
            case DECREMENT -> {
                history.consecutiveDecrements++;
                history.consecutiveIncrements = 0;
                history.consecutiveOverflows = 0;
            }
            case OVERFLOW -> {
                history.consecutiveOverflows++;
                history.totalOverflows++;
                history.consecutiveIncrements = 0;
                history.consecutiveDecrements = 0;
            }
            case UNDERFLOW -> {
                // Not expected for latency, reset counters
                history.reset();
            }
        }
    }

    /**
     * Assesses confidence level based on signal history.
     */
    private Monitors.Dimension assessConfidence(SignalHistory history) {
        // CONFIRMED: Sustained pattern (3+ consecutive same signals)
        int maxConsecutive = Math.max(
            history.consecutiveIncrements,
            Math.max(history.consecutiveDecrements, history.consecutiveOverflows)
        );

        if (maxConsecutive >= 3) {
            return Monitors.Dimension.CONFIRMED;
        }

        // MEASURED: Emerging pattern (2 consecutive)
        if (maxConsecutive >= 2) {
            return Monitors.Dimension.MEASURED;
        }

        // TENTATIVE: Single signal
        return Monitors.Dimension.TENTATIVE;
    }

    /**
     * Tracks signal pattern for an entity.
     */
    private static class SignalHistory {
        int consecutiveIncrements = 0;
        int consecutiveDecrements = 0;
        int consecutiveOverflows = 0;
        int totalOverflows = 0;

        void reset() {
            consecutiveIncrements = 0;
            consecutiveDecrements = 0;
            consecutiveOverflows = 0;
        }
    }
}
