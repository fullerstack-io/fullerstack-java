package io.fullerstack.kafka.broker.reporters;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Situations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 3 Situation: Subscribes to Monitors → assesses urgency → emits Reporters signals.
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
 * <tr><td>DEFECTIVE</td><td>*</td><td><b>CRITICAL</b></td><td>Component failure</td></tr>
 * <tr><td>DIVERGING</td><td>*</td><td><b>WARNING</b></td><td>Drifting from stable</td></tr>
 * <tr><td>STABLE</td><td>*</td><td><b>NORMAL</b></td><td>Healthy operation</td></tr>
 * </table>
 */
public class JvmHealthReporter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(JvmHealthReporter.class);

    private final Circuit circuit;
    private final Conduit<Situations.Situation, Situations.Signal> reporters;
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
            Situations::composer
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
            Situations.Situation reporter = reporters.percept(cortex().name(reporterName));

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
    private void assessUrgency(String reporterName, Situations.Situation reporter, Monitors.Signal signal) {
        Monitors.Sign condition = signal.sign();
        Monitors.Dimension confidence = signal.dimension();

        // Urgency assessment logic
        switch (condition) {
            // CRITICAL urgency - Immediate action required
            case DEGRADED -> {
                if (confidence == Monitors.Dimension.CONFIRMED) {
                    reporter.critical(Situations.Dimension.CONSTANT);
                    logger.error("[REPORTER] {} emits CRITICAL - DEGRADED (CONFIRMED) detected",
                        reporterName);
                } else {
                    reporter.warning(Situations.Dimension.VARIABLE);
                    logger.warn("[REPORTER] {} emits WARNING - DEGRADED ({}) detected",
                        reporterName, confidence);
                }
            }

            case DEFECTIVE -> {
                reporter.critical(Situations.Dimension.CONSTANT);
                logger.error("[REPORTER] {} emits CRITICAL - {} detected",
                    reporterName, condition);
            }

            // WARNING urgency - Action likely required
            case DIVERGING -> {
                reporter.warning(Situations.Dimension.VARIABLE);
                logger.warn("[REPORTER] {} emits WARNING - DIVERGING ({}) detected (drifting from stable)",
                    reporterName, confidence);
            }

            // NORMAL urgency - Healthy operation
            case STABLE -> {
                reporter.normal(Situations.Dimension.CONSTANT);
                logger.info("[REPORTER] {} emits NORMAL - STABLE ({}) detected",
                    reporterName, confidence);
            }

            default -> {
                // Fallback for unknown conditions
                reporter.warning(Situations.Dimension.VARIABLE);
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
    public Conduit<Situations.Situation, Situations.Signal> reporters() {
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
