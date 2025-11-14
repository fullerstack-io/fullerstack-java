package io.fullerstack.kafka.core.reporters;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 3 (DECIDE phase) reporter for consumer health assessment.
 *
 * <p>Subscribes to consumer Monitor conduit (flat namespace) and assesses
 * urgency based on consumer-specific Monitor.Sign patterns:
 *
 * <ul>
 *   <li><b>CRITICAL</b>: Consumer DOWN/DEFECTIVE, or sustained lag growth (DIVERGING)</li>
 *   <li><b>WARNING</b>: Consumer DEGRADED, ERRATIC consumption, intermittent lag spikes</li>
 *   <li><b>NORMAL</b>: Consumer STABLE, lag CONVERGING or stable</li>
 * </ul>
 *
 * <p><b>Pattern Detection</b>:
 * <ul>
 *   <li>Sustained lag growth: Tracks consecutive DIVERGING signals</li>
 *   <li>Intermittent spikes: ERRATIC indicates irregular consumption patterns</li>
 *   <li>Recovery: CONVERGING indicates lag is reducing (healthy)</li>
 * </ul>
 *
 * <p><b>Architecture</b>:
 * <pre>
 * Layer 2 (ORIENT): Monitor conduit emits Monitors.Sign
 *                       ↓
 * Layer 3 (DECIDE): ConsumerHealthReporter assesses urgency
 *                       ↓
 *                   Reporter conduit emits Reporters.Sign
 * </pre>
 *
 * <p><b>Example Usage</b>:
 * <pre>{@code
 * Circuit monitorCircuit = cortex().circuit(cortex().name("monitors"));
 * Conduit<Monitors.Monitor, Monitors.Signal> monitors =
 *     monitorCircuit.conduit(cortex().name("consumer-monitors"), Monitors::composer);
 *
 * Circuit reporterCircuit = cortex().circuit(cortex().name("reporters"));
 * Conduit<Reporters.Reporter, Reporters.Sign> reporters =
 *     reporterCircuit.conduit(cortex().name("reporters"), Reporters::composer);
 *
 * ConsumerHealthReporter reporter = new ConsumerHealthReporter(monitors, reporters);
 *
 * // Monitor emits DIVERGING for consumer lag
 * Monitors.Monitor lagMonitor = monitors.percept(cortex().name("order-processor.lag"));
 * lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
 *
 * // Reporter assesses urgency and emits CRITICAL
 * }</pre>
 *
 * @see Monitors
 * @see Reporters
 * @since 1.0.0
 */
public class ConsumerHealthReporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsumerHealthReporter.class);

    /**
     * Threshold for sustained lag growth detection.
     * If DIVERGING seen this many times consecutively → CRITICAL.
     */
    private static final int SUSTAINED_DIVERGING_THRESHOLD = 3;

    private final Conduit<Monitors.Monitor, Monitors.Signal> monitors;
    private final Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private final Reporters.Reporter reporter;
    private final Subscription subscription;

    /**
     * Tracks consecutive DIVERGING signals per consumer for pattern detection.
     * Key: consumer subject name, Value: consecutive DIVERGING count
     */
    private final Map<String, Integer> divergingCounts = new ConcurrentHashMap<>();

    /**
     * Creates a new ConsumerHealthReporter.
     *
     * @param monitors Monitor conduit (receives consumer-specific Monitors.Sign)
     * @param reporters Reporter conduit (emits Reporters.Sign)
     */
    public ConsumerHealthReporter(
        Conduit<Monitors.Monitor, Monitors.Signal> monitors,
        Conduit<Reporters.Reporter, Reporters.Sign> reporters
    ) {
        this.monitors = monitors;
        this.reporters = reporters;
        this.reporter = reporters.percept(cortex().name("consumer-health"));

        // Subscribe to all consumer monitor signals
        this.subscription = monitors.subscribe(cortex().subscriber(
            cortex().name("consumer-health-reporter"),
            this::handleMonitorSignal
        ));

        log.info("ConsumerHealthReporter initialized - subscribed to consumer monitors");
    }

    /**
     * Handles incoming Monitor signals from consumer monitors.
     *
     * @param subject subject containing consumer context
     * @param registrar registrar for signal handling
     */
    private void handleMonitorSignal(
        Subject<Channel<Monitors.Signal>> subject,
        Registrar<Monitors.Signal> registrar
    ) {
        registrar.register(signal -> {
            String consumerName = subject.name().toString();
            Monitors.Sign sign = signal.sign();

            // Update pattern tracking
            updateDivergingTracking(consumerName, sign);

            // Assess urgency based on Sign
            Reporters.Sign urgency = assessUrgency(consumerName, sign);

            // Emit reporter signal
            emitReporterSignal(urgency, consumerName, sign);
        });
    }

    /**
     * Tracks consecutive DIVERGING signals for sustained lag growth detection.
     *
     * @param consumerName consumer identifier
     * @param sign current Monitor.Sign
     */
    private void updateDivergingTracking(String consumerName, Monitors.Sign sign) {
        if (sign == Monitors.Sign.DIVERGING) {
            divergingCounts.merge(consumerName, 1, Integer::sum);
        } else {
            // Reset count on any other signal
            divergingCounts.remove(consumerName);
        }
    }

    /**
     * Assesses urgency based on consumer Monitor.Sign and pattern detection.
     *
     * <p><b>Detection Rules</b>:
     * <ul>
     *   <li>DOWN, DEFECTIVE → CRITICAL (consumer unavailable)</li>
     *   <li>Sustained DIVERGING (3+ consecutive) → CRITICAL (lag growth trend)</li>
     *   <li>DEGRADED → WARNING (performance degradation)</li>
     *   <li>ERRATIC → WARNING (irregular consumption)</li>
     *   <li>Single DIVERGING → WARNING (early lag growth detection)</li>
     *   <li>CONVERGING, STABLE → NORMAL (healthy)</li>
     * </ul>
     *
     * @param consumerName consumer identifier
     * @param sign current Monitor.Sign
     * @return assessed urgency level
     */
    private Reporters.Sign assessUrgency(String consumerName, Monitors.Sign sign) {
        // CRITICAL: Consumer down or defective
        if (sign == Monitors.Sign.DOWN || sign == Monitors.Sign.DEFECTIVE) {
            log.warn("Consumer '{}' CRITICAL: {}", consumerName, sign);
            return Reporters.Sign.CRITICAL;
        }

        // CRITICAL: Sustained lag growth (3+ consecutive DIVERGING)
        int divergingCount = divergingCounts.getOrDefault(consumerName, 0);
        if (sign == Monitors.Sign.DIVERGING && divergingCount >= SUSTAINED_DIVERGING_THRESHOLD) {
            log.warn("Consumer '{}' CRITICAL: Sustained lag growth ({} consecutive DIVERGING)",
                consumerName, divergingCount);
            return Reporters.Sign.CRITICAL;
        }

        // WARNING: Degraded performance
        if (sign == Monitors.Sign.DEGRADED) {
            log.warn("Consumer '{}' WARNING: Degraded performance", consumerName);
            return Reporters.Sign.WARNING;
        }

        // WARNING: Erratic consumption patterns
        if (sign == Monitors.Sign.ERRATIC) {
            log.warn("Consumer '{}' WARNING: Erratic consumption detected", consumerName);
            return Reporters.Sign.WARNING;
        }

        // WARNING: Early lag growth detection (single DIVERGING)
        if (sign == Monitors.Sign.DIVERGING) {
            log.info("Consumer '{}' WARNING: Lag growth detected (count: {})",
                consumerName, divergingCount);
            return Reporters.Sign.WARNING;
        }

        // NORMAL: Healthy operation
        log.debug("Consumer '{}' NORMAL: {}", consumerName, sign);
        return Reporters.Sign.NORMAL;
    }

    /**
     * Emits Reporter signal with appropriate method call.
     *
     * @param urgency assessed urgency level
     * @param consumerName consumer identifier for logging
     * @param monitorSign original Monitor.Sign for logging
     */
    private void emitReporterSignal(Reporters.Sign urgency, String consumerName, Monitors.Sign monitorSign) {
        switch (urgency) {
            case CRITICAL -> {
                reporter.critical();
                log.info("Emitted CRITICAL for consumer '{}' (monitor: {})", consumerName, monitorSign);
            }
            case WARNING -> {
                reporter.warning();
                log.debug("Emitted WARNING for consumer '{}' (monitor: {})", consumerName, monitorSign);
            }
            case NORMAL -> {
                reporter.normal();
                log.trace("Emitted NORMAL for consumer '{}' (monitor: {})", consumerName, monitorSign);
            }
        }
    }

    /**
     * Clears pattern tracking state for a specific consumer.
     * Useful for testing or when a consumer is deregistered.
     *
     * @param consumerName consumer identifier
     */
    public void clearTracking(String consumerName) {
        divergingCounts.remove(consumerName);
        log.debug("Cleared tracking for consumer '{}'", consumerName);
    }

    /**
     * Clears all pattern tracking state.
     * Useful for testing or system reset.
     */
    public void clearAllTracking() {
        divergingCounts.clear();
        log.debug("Cleared all consumer tracking state");
    }

    /**
     * Closes the reporter and unsubscribes from monitor conduit.
     */
    @Override
    public void close() {
        if (subscription != null) {
            subscription.close();
        }
        log.info("ConsumerHealthReporter closed");
    }
}
