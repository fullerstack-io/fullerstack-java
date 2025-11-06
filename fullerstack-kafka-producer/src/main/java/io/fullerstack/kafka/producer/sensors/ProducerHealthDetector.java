package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
import io.humainary.substrates.ext.serventis.ext.Queues;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Probes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects producer health conditions by analyzing signal patterns.
 * <p>
 * Subscribes to producer signals (buffer pressure, errors, latency) and emits health assessments:
 * <ul>
 *   <li><b>STABLE</b>: Normal operation (low errors, good latency, no buffer pressure)</li>
 *   <li><b>CONVERGING</b>: Recovering from issues</li>
 *   <li><b>DIVERGING</b>: Early warning signs (increasing errors/latency)</li>
 *   <li><b>DEGRADED</b>: Significant issues (high error rate or buffer exhaustion)</li>
 *   <li><b>DEFECTIVE</b>: Critical failure (sustained errors and buffer overflow)</li>
 * </ul>
 *
 * <h3>Detection Patterns:</h3>
 * <ul>
 *   <li><b>High Error Rate</b>: Multiple error signals → DEGRADED</li>
 *   <li><b>Buffer Exhaustion</b>: OVERFLOW + exhausted counter → DEGRADED</li>
 *   <li><b>High Latency</b>: Sustained latency overflow → DIVERGING</li>
 *   <li><b>Combined Failures</b>: Errors + overflow → DEFECTIVE</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * Monitor healthMonitor = ...;
 *
 * ProducerHealthDetector detector = new ProducerHealthDetector(
 *     "producer-1",
 *     healthMonitor
 * );
 *
 * // Register as subscriber to producer signals
 * circuit.subscribe(detector);
 *
 * // Detector analyzes signals and emits health status
 * // Later...
 * detector.close();
 * }</pre>
 *
 * @author Fullerstack
 * @see Monitor
 */
public class ProducerHealthDetector implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ProducerHealthDetector.class);

    // Health assessment thresholds
    private static final int ERROR_THRESHOLD_DEGRADED = 3;      // 3+ errors → DEGRADED
    private static final int ERROR_THRESHOLD_DEFECTIVE = 10;    // 10+ errors → DEFECTIVE
    private static final int OVERFLOW_THRESHOLD_DEGRADED = 2;   // 2+ overflows → DEGRADED
    private static final int LATENCY_THRESHOLD_DIVERGING = 3;   // 3+ latency spikes → DIVERGING

    private final String producerId;
    private final Monitor healthMonitor;

    // Health state tracking
    private int errorCount = 0;
    private int overflowCount = 0;
    private int latencyOverflowCount = 0;
    private int exhaustedCount = 0;
    private boolean bufferPressure = false;
    private Monitors.Sign currentCondition = Monitors.Sign.STABLE;

    /**
     * Creates a new producer health detector.
     *
     * @param producerId    Producer identifier (e.g., "producer-1")
     * @param healthMonitor Monitor instrument for emitting health assessments
     */
    public ProducerHealthDetector(
        String producerId,
        Monitor healthMonitor
    ) {
        this.producerId = producerId;
        this.healthMonitor = healthMonitor;
    }

    /**
     * Processes buffer overflow events.
     */
    public void onBufferOverflow() {
        overflowCount++;
        bufferPressure = true;
        logger.warn("Producer {} buffer OVERFLOW (count={})", producerId, overflowCount);
        assessHealth();
    }

    /**
     * Processes normal buffer operation.
     */
    public void onBufferNormal() {
        // Normal buffer operation - reset pressure if sustained
        if (overflowCount == 0) {
            bufferPressure = false;
        }
    }

    /**
     * Processes buffer underflow (draining).
     */
    public void onBufferUnderflow() {
        // Buffer draining - positive sign
        bufferPressure = false;
        overflowCount = Math.max(0, overflowCount - 1);
        assessHealth();
    }

    /**
     * Processes error count increase.
     */
    public void onErrorIncrement() {
        errorCount++;
        logger.warn("Producer {} error count INCREASED (count={})", producerId, errorCount);
        assessHealth();
    }

    /**
     * Processes buffer exhaustion event.
     */
    public void onBufferExhaustion() {
        exhaustedCount++;
        logger.warn("Producer {} buffer exhaustion count INCREASED (count={})",
            producerId, exhaustedCount);
        assessHealth();
    }

    /**
     * Processes latency overflow.
     */
    public void onLatencyOverflow() {
        latencyOverflowCount++;
        logger.warn("Producer {} latency OVERFLOW (count={})",
            producerId, latencyOverflowCount);
        assessHealth();
    }

    /**
     * Processes latency improvement.
     */
    public void onLatencyImprovement() {
        // Latency improving
        latencyOverflowCount = Math.max(0, latencyOverflowCount - 1);
        assessHealth();
    }

    /**
     * Assesses overall producer health based on accumulated signals.
     */
    private void assessHealth() {
        Monitors.Sign newCondition = determineCondition();
        Monitors.Dimension confidence = determineConfidence();

        if (newCondition != currentCondition) {
            // Condition changed - emit status
            healthMonitor.status(newCondition, confidence);
            logger.info("Producer {} health status: {} → {} (confidence: {})",
                producerId, currentCondition, newCondition, confidence);
            currentCondition = newCondition;
        }
    }

    private Monitors.Sign determineCondition() {
        // DEFECTIVE: Critical failure (sustained errors + buffer overflow)
        if (errorCount >= ERROR_THRESHOLD_DEFECTIVE ||
            (errorCount >= ERROR_THRESHOLD_DEGRADED && overflowCount >= OVERFLOW_THRESHOLD_DEGRADED)) {
            return Monitors.Sign.DEFECTIVE;
        }

        // DEGRADED: Significant issues
        if (errorCount >= ERROR_THRESHOLD_DEGRADED ||
            overflowCount >= OVERFLOW_THRESHOLD_DEGRADED ||
            exhaustedCount >= 2) {
            return Monitors.Sign.DEGRADED;
        }

        // DIVERGING: Early warning signs
        if (errorCount > 0 ||
            latencyOverflowCount >= LATENCY_THRESHOLD_DIVERGING ||
            bufferPressure) {
            return Monitors.Sign.DIVERGING;
        }

        // CONVERGING: Recovering (decreasing issues)
        if (currentCondition != Monitors.Sign.STABLE &&
            errorCount == 0 &&
            overflowCount == 0 &&
            latencyOverflowCount == 0) {
            return Monitors.Sign.CONVERGING;
        }

        // STABLE: Normal operation
        return Monitors.Sign.STABLE;
    }

    private Monitors.Dimension determineConfidence() {
        // HIGH: Clear evidence of condition
        if (errorCount >= ERROR_THRESHOLD_DEFECTIVE ||
            overflowCount >= OVERFLOW_THRESHOLD_DEGRADED + 2) {
            return Monitors.Dimension.CONFIRMED;
        }

        // MEDIUM: Moderate evidence
        if (errorCount >= ERROR_THRESHOLD_DEGRADED ||
            overflowCount >= OVERFLOW_THRESHOLD_DEGRADED) {
            return Monitors.Dimension.MEASURED;
        }

        // LOW: Initial signals
        return Monitors.Dimension.TENTATIVE;
    }

    /**
     * Resets health state (useful for testing or manual reset).
     */
    public void reset() {
        errorCount = 0;
        overflowCount = 0;
        latencyOverflowCount = 0;
        exhaustedCount = 0;
        bufferPressure = false;
        currentCondition = Monitors.Sign.STABLE;
        healthMonitor.status(Monitors.Sign.STABLE, Monitors.Dimension.CONFIRMED);
        logger.info("Producer {} health state RESET", producerId);
    }

    @Override
    public void close() {
        logger.info("Closing producer health detector for {}", producerId);
    }

    // ========================================
    // Getters for Testing
    // ========================================

    public int getErrorCount() {
        return errorCount;
    }

    public int getOverflowCount() {
        return overflowCount;
    }

    public int getLatencyOverflowCount() {
        return latencyOverflowCount;
    }

    public Monitors.Sign getCurrentCondition() {
        return currentCondition;
    }
}
