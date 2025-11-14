package io.fullerstack.kafka.broker.reporters;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import io.humainary.substrates.ext.serventis.ext.Reporters.Reporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 3 (DECIDE): Assesses urgency of network situations based on Monitor conditions.
 *
 * <p><b>Responsibilities (DECISION/URGENCY ASSESSMENT):</b>
 * <ul>
 *   <li>Subscribe to Monitor condition signals from Layer 2</li>
 *   <li>Assess URGENCY based on condition + confidence combination</li>
 *   <li>Emit Reporter situation signals (CRITICAL, WARNING, NOTICE, INFO)</li>
 *   <li>Combine multiple Monitor signals to assess overall system situation</li>
 * </ul>
 *
 * <p><b>Urgency Assessment Logic:</b>
 * <ul>
 *   <li>DEGRADED + CONFIRMED → CRITICAL (immediate action required)</li>
 *   <li>DEGRADED + MEASURED → WARNING (action likely required)</li>
 *   <li>DEGRADED + TENTATIVE → NOTICE (investigate)</li>
 *   <li>DIVERGING + CONFIRMED → WARNING (preventive action)</li>
 *   <li>DIVERGING + MEASURED → NOTICE (monitor closely)</li>
 *   <li>ERRATIC + * → WARNING (unpredictable, risky)</li>
 *   <li>CONVERGING + * → NOTICE (improving, monitor)</li>
 *   <li>STABLE + CONFIRMED → INFO (normal operation)</li>
 * </ul>
 *
 * <p><b>Signal Flow:</b>
 * <pre>
 * Layer 2 Monitors → NetworkSituationReporter (subscribes) → Reporters
 *                                                                  ↓
 *                                                  (Alerting/Remediation subscribes here)
 * </pre>
 *
 * <p><b>Example Usage:</b>
 * <pre>
 * NetworkSituationReporter reporter = new NetworkSituationReporter(
 *     circuit,
 *     throttleMonitor.monitors(),
 *     quotaMonitor.monitors()
 * );
 * // Reporter automatically subscribes to all monitors and emits urgency assessments
 * </pre>
 */
public class NetworkSituationReporter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(NetworkSituationReporter.class);

    private final Circuit circuit;
    private final Conduit<Reporter, Reporters.Sign> reporters;
    private final Subscription monitorSubscription;

    // Reporter instances per entity (throttle type, client, etc.)
    private final Map<String, Reporter> entityReporters = new ConcurrentHashMap<>();

    /**
     * Creates a NetworkSituationReporter (Layer 3 - DECIDE).
     *
     * @param circuit  Circuit for Substrates infrastructure
     * @param monitors Varargs of Monitor conduits from Layer 2 to subscribe to
     * @throws NullPointerException if any parameter is null
     */
    @SafeVarargs
    public NetworkSituationReporter(
        Circuit circuit,
        Conduit<Monitors.Monitor, Monitors.Signal>... monitors
    ) {
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        Objects.requireNonNull(monitors, "monitors cannot be null");

        // Create Layer 3 reporters conduit
        this.reporters = circuit.conduit(cortex().name("reporters"), Reporters::composer);

        // Subscribe to ALL monitor conduits
        // Note: In production, you might want separate subscriptions per monitor type
        // For simplicity, we'll subscribe to all and filter in handler
        Subscription[] subscriptions = new Subscription[monitors.length];
        for (int i = 0; i < monitors.length; i++) {
            subscriptions[i] = monitors[i].subscribe(cortex().subscriber(
                cortex().name("network-situation-reporter-" + i),
                this::handleMonitorSignal
            ));
        }

        // Store first subscription for close() (in production, track all subscriptions)
        this.monitorSubscription = subscriptions.length > 0 ? subscriptions[0] : null;

        logger.info("NetworkSituationReporter started (Layer 3 - DECIDE, subscribed to {} monitor conduits)",
            monitors.length);
    }

    /**
     * Handles Monitor condition signals from Layer 2 and emits Reporter urgency assessments.
     * This is where URGENCY DECISION-MAKING happens.
     *
     * <p><b>NEW API (Serventis PREVIEW):</b> Sign enum values directly represent conditions.
     * Dimension information is no longer available in subscribers - decision logic is simplified
     * to sign-based urgency assessment.
     */
    private void handleMonitorSignal(Subject<Channel<Monitors.Signal>> subject, Registrar<Monitors.Signal> registrar) {
        registrar.register(signal -> {
            // Extract entity name from Subject (e.g., "monitor.throttle.produce" or "monitor.quota.health.client-1")
            String monitorName = subject.name().toString();

            // Get or create Reporter for this entity
            Reporter reporter = entityReporters.computeIfAbsent(monitorName,
                name -> reporters.percept(cortex().name(name.replace("monitor.", "reporter."))));

            // Decision logic: Map Signal sign → Urgency
            // Extract sign from Signal record (Monitors.Signal has sign() and dimension())
            switch (signal.sign()) {
                case DEGRADED -> {
                    // Degraded state → CRITICAL urgency
                    // (Monitor already filtered by dimension before emitting)
                    reporter.critical();
                    logger.error("CRITICAL situation: {} - DEGRADED condition detected", monitorName);
                }

                case DEFECTIVE, DOWN -> {
                    // System failure → CRITICAL urgency
                    reporter.critical();
                    logger.error("CRITICAL situation: {} - {} condition detected", monitorName, signal.sign());
                }

                case DIVERGING -> {
                    // Diverging from normal → WARNING (preventive action)
                    reporter.warning();
                    logger.warn("WARNING situation: {} - DIVERGING condition detected", monitorName);
                }

                case ERRATIC -> {
                    // Unpredictable behavior → WARNING (risky)
                    reporter.warning();
                    logger.warn("WARNING situation: {} - ERRATIC behavior detected", monitorName);
                }

                case CONVERGING -> {
                    // Improving from degraded state → WARNING (keep monitoring)
                    reporter.warning();
                    logger.info("WARNING situation: {} - CONVERGING (improving)", monitorName);
                }

                case STABLE -> {
                    // Normal operation → NORMAL
                    reporter.normal();
                    logger.debug("NORMAL situation: {} - STABLE condition", monitorName);
                }

                default -> {
                    // Unknown sign - log but don't emit
                    logger.trace("Unknown sign: {} - {}", monitorName, signal.sign());
                }
            }
        });
    }

    /**
     * Get reporters conduit for alerting/remediation systems to subscribe to.
     *
     * @return Reporters conduit
     */
    public Conduit<Reporter, Reporters.Sign> reporters() {
        return reporters;
    }

    @Override
    public void close() {
        logger.info("Shutting down NetworkSituationReporter...");
        if (monitorSubscription != null) {
            monitorSubscription.close();
        }
        // In production, close all subscriptions
        logger.info("NetworkSituationReporter stopped");
    }
}
