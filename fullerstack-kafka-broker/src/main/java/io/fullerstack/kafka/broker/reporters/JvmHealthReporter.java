package io.fullerstack.kafka.broker.reporters;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 3 Reporter: Subscribes to Monitors → assesses urgency → emits Reporters signals.
 * <p>
 * This is the urgency assessment layer - it subscribes to Monitors signals from Layer 2 (JvmHealthMonitor)
 * and determines the urgency level (normal, warning, critical) based on condition + confidence.
 * <p>
 * <b>Signal-Flow Pattern:</b>
 * <pre>
 * Layer 1: JvmMetricsObserver → Gauges (heap: overflow, cpu: increment, etc.)
 *                                   ↓
 * Layer 2: JvmHealthMonitor → Monitors (DEGRADED + CONFIRMED)
 *                                   ↓
 * Layer 3: JvmHealthReporter → Reporters (CRITICAL)
 *                                   ↓
 * Layer 4: CoordinationLayer → Route to Agents (autonomous) or Actors (human approval)
 * </pre>
 *
 * <h3>Urgency Assessment Logic:</h3>
 * <table border="1">
 * <tr><th>Condition</th><th>Confidence</th><th>Urgency</th><th>Rationale</th></tr>
 * <tr><td>DEGRADED</td><td>CONFIRMED</td><td><b>CRITICAL</b></td><td>Immediate action required</td></tr>
 * <tr><td>DEGRADED</td><td>MEASURED</td><td><b>WARNING</b></td><td>Action likely required</td></tr>
 * <tr><td>DEGRADED</td><td>TENTATIVE</td><td><b>WARNING</b></td><td>Monitor closely</td></tr>
 * <tr><td>DEGRADING</td><td>CONFIRMED</td><td><b>WARNING</b></td><td>Trending toward degraded</td></tr>
 * <tr><td>DEGRADING</td><td>MEASURED</td><td><b>WARNING</b></td><td>Early warning</td></tr>
 * <tr><td>DIVERGING</td><td>*</td><td><b>WARNING</b></td><td>Drifting from stable</td></tr>
 * <tr><td>ERRATIC</td><td>*</td><td><b>WARNING</b></td><td>Unstable behavior</td></tr>
 * <tr><td>DEFECTIVE</td><td>*</td><td><b>CRITICAL</b></td><td>Component failure</td></tr>
 * <tr><td>DOWN</td><td>*</td><td><b>CRITICAL</b></td><td>Complete failure</td></tr>
 * <tr><td>CONVERGING</td><td>*</td><td><b>NORMAL</b></td><td>Recovering</td></tr>
 * <tr><td>STABLE</td><td>*</td><td><b>NORMAL</b></td><td>Healthy operation</td></tr>
 * </table>
 */
public class JvmHealthReporter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(JvmHealthReporter.class);

    private final Circuit circuit;
    private final Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private final Subscription monitorSubscription;

    /**
     * Creates a new JVM health reporter.
     *
     * @param circuit parent circuit for reporters conduit
     * @param monitors monitors conduit from JvmHealthMonitor (Layer 2)
     */
    public JvmHealthReporter(Circuit circuit, Conduit<Monitors.Monitor, Monitors.Signal> monitors) {
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        Objects.requireNonNull(monitors, "monitors cannot be null");

        // Create Reporters conduit
        this.reporters = circuit.conduit(
            cortex().name("jvm.reporters"),
            Reporters::composer
        );

        // Subscribe to Monitors signals from Layer 2
        this.monitorSubscription = monitors.subscribe(cortex().subscriber(
            cortex().name("jvm-health-reporter"),
            this::handleMonitorSignal
        ));

        logger.info("JvmHealthReporter started - subscribing to Monitors signals");
    }

    /**
     * Handle Monitors signals from Layer 2 (JvmHealthMonitor).
     * <p>
     * Assesses urgency based on condition + confidence and emits Reporters signals.
     */
    private void handleMonitorSignal(
        Subject<Channel<Monitors.Signal>> subject,
        Registrar<Monitors.Signal> registrar
    ) {
        registrar.register(signal -> {
            String monitorName = subject.name().toString();

            logger.debug("Received monitor signal: {} -> {} ({})",
                monitorName, signal.sign(), signal.dimension());

            // Determine reporter name (e.g., "monitor.jvm.heap" -> "reporter.jvm.heap")
            String reporterName = monitorName.replace("monitor.", "reporter.");
            Reporters.Reporter reporter = reporters.percept(cortex().name(reporterName));

            // Assess urgency and emit
            assessUrgency(reporterName, reporter, signal);
        });
    }

    /**
     * Assess urgency based on monitor condition + confidence.
     * <p>
     * This is where we translate technical health conditions into urgency levels
     * that drive Layer 4 actions (Agents for automation, Actors for human coordination).
     */
    private void assessUrgency(String reporterName, Reporters.Reporter reporter, Monitors.Signal signal) {
        Monitors.Sign condition = signal.sign();
        Monitors.Dimension confidence = signal.dimension();

        // Urgency assessment logic
        switch (condition) {
            // CRITICAL urgency - Immediate action required
            case DEGRADED -> {
                if (confidence == Monitors.Dimension.CONFIRMED) {
                    reporter.critical();
                    logger.error("[REPORTER] {} emits CRITICAL - DEGRADED (CONFIRMED) detected",
                        reporterName);
                } else {
                    reporter.warning();
                    logger.warn("[REPORTER] {} emits WARNING - DEGRADED ({}) detected",
                        reporterName, confidence);
                }
            }

            case DEFECTIVE, DOWN -> {
                reporter.critical();
                logger.error("[REPORTER] {} emits CRITICAL - {} detected",
                    reporterName, condition);
            }

            // WARNING urgency - Action likely required
            case DEGRADING -> {
                reporter.warning();
                logger.warn("[REPORTER] {} emits WARNING - DEGRADING ({}) detected",
                    reporterName, confidence);
            }

            case DIVERGING -> {
                reporter.warning();
                logger.warn("[REPORTER] {} emits WARNING - DIVERGING ({}) detected (drifting from stable)",
                    reporterName, confidence);
            }

            case ERRATIC -> {
                reporter.warning();
                logger.warn("[REPORTER] {} emits WARNING - ERRATIC ({}) detected (unstable behavior)",
                    reporterName, confidence);
            }

            // NORMAL urgency - Healthy operation
            case STABLE -> {
                reporter.normal();
                logger.info("[REPORTER] {} emits NORMAL - STABLE ({}) detected",
                    reporterName, confidence);
            }

            case CONVERGING -> {
                reporter.normal();
                logger.info("[REPORTER] {} emits NORMAL - CONVERGING ({}) detected (recovering)",
                    reporterName, confidence);
            }

            default -> {
                // Fallback for unknown conditions
                reporter.warning();
                logger.warn("[REPORTER] {} emits WARNING - Unknown condition: {} ({})",
                    reporterName, condition, confidence);
            }
        }
    }

    /**
     * Get reporters conduit for Layer 4 subscription.
     *
     * @return reporters conduit emitting urgency assessment signals
     */
    public Conduit<Reporters.Reporter, Reporters.Sign> reporters() {
        return reporters;
    }

    @Override
    public void close() {
        logger.info("Shutting down JVM health reporter");

        if (monitorSubscription != null) {
            monitorSubscription.close();
        }

        logger.info("JVM health reporter stopped");
    }
}
