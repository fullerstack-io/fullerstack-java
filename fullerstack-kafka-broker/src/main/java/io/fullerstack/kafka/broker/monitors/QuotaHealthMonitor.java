package io.fullerstack.kafka.broker.monitors;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.api.Substrates.cortex;
import static io.humainary.substrates.ext.serventis.ext.Monitors.Dimension.*;

/**
 * Layer 2 (ORIENT): Assesses quota health by detecting patterns in quota usage signals.
 *
 * <p><b>Responsibilities (ORIENTATION/ASSESSMENT):</b>
 * <ul>
 *   <li>Subscribe to quota usage Gauge signals from Layer 1 Receptor</li>
 *   <li>Detect patterns: sustained violations, approaching quota, normal usage</li>
 *   <li>Emit Monitor conditions based on pattern recognition</li>
 *   <li>Track confidence dimensions: TENTATIVE → MEASURED → CONFIRMED</li>
 * </ul>
 *
 * <p><b>Pattern Detection Logic:</b>
 * <ul>
 *   <li>OVERFLOW signal (>100% quota) → quota violation detected
 *     <ul>
 *       <li>1 OVERFLOW → DEGRADED (TENTATIVE) - "might be violating"</li>
 *       <li>2 OVERFLOW → DEGRADED (MEASURED) - "likely violating"</li>
 *       <li>3+ OVERFLOW → DEGRADED (CONFIRMED) - "definitely violating"</li>
 *     </ul>
 *   </li>
 *   <li>INCREMENT signal (90-100% quota) → DIVERGING (MEASURED) - "approaching quota"</li>
 *   <li>DECREMENT signal (<90% quota) → STABLE or CONVERGING</li>
 * </ul>
 *
 * <p><b>Signal Flow:</b>
 * <pre>
 * Layer 1 Gauges → QuotaHealthMonitor (subscribes) → Monitors
 *                                                          ↓
 *                                          (Layer 3 Reporters subscribe here)
 * </pre>
 *
 * <p><b>Example Usage:</b>
 * <pre>
 * QuotaHealthMonitor monitor = new QuotaHealthMonitor(circuit, receptor.gauges());
 * // Monitor automatically subscribes to quota gauge signals and emits condition assessments
 * </pre>
 */
public class QuotaHealthMonitor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(QuotaHealthMonitor.class);

    private final Circuit circuit;
    private final Conduit<Monitor, Monitors.Signal> monitors;
    private final Subscription gaugeSubscription;

    // Track violation counts per client (for pattern detection)
    private final Map<String, Integer> violationCounts = new ConcurrentHashMap<>();

    // Monitor instances per client
    private final Map<String, Monitor> quotaMonitors = new ConcurrentHashMap<>();

    /**
     * Creates a QuotaHealthMonitor (Layer 2 - ORIENT).
     *
     * @param circuit Circuit for Substrates infrastructure
     * @param gauges  Gauges conduit from Layer 1 Observer to subscribe to
     * @throws NullPointerException if any parameter is null
     */
    public QuotaHealthMonitor(
        Circuit circuit,
        Conduit<Gauges.Gauge, Gauges.Signal> gauges
    ) {
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        Objects.requireNonNull(gauges, "gauges cannot be null");

        // Create Layer 2 monitors conduit
        this.monitors = circuit.conduit(cortex().name("monitors"), Monitors::composer);

        // Subscribe to ALL gauge signals from Layer 1 (filter for quota gauges in handler)
        this.gaugeSubscription = gauges.subscribe(cortex().subscriber(
            cortex().name("quota-health-monitor"),
            this::handleGaugeSignal
        ));

        logger.info("QuotaHealthMonitor started (Layer 2 - ORIENT, subscribed to quota gauges)");
    }

    /**
     * Handles gauge signals from Layer 1 and emits Monitor conditions.
     * This is where PATTERN DETECTION happens.
     */
    private void handleGaugeSignal(Subject<Channel<Gauges.Sign>> subject, Registrar<Gauges.Sign> registrar) {
        registrar.register(signal -> {
            // Extract entity name from Subject (e.g., "network.quota.usage.client-1")
            String gaugeName = subject.name().toString();

            // Only process quota-related gauges
            if (!gaugeName.contains("quota.usage")) {
                return;
            }

            // Extract client ID from gauge name (e.g., "network.quota.usage.client-1" → "client-1")
            String clientId = gaugeName.substring(gaugeName.lastIndexOf('.') + 1);

            // Get or create Monitor for this client
            Monitor monitor = quotaMonitors.computeIfAbsent(clientId,
                id -> monitors.percept(cortex().name("monitor.quota.health." + id)));

            // Pattern detection based on signal type (signal IS the Sign enum)
            switch (signal) {
                case OVERFLOW -> {
                    // Quota violation detected (>100% usage)
                    int count = violationCounts.compute(clientId, (k, v) -> (v == null) ? 1 : v + 1);

                    // Emit condition based on sustained pattern
                    if (count >= 3) {
                        // Sustained violations (3+ consecutive) → DEGRADED with CONFIRMED confidence
                        monitor.degraded(CONFIRMED);
                        logger.warn("Quota DEGRADED (CONFIRMED): client {} - sustained violations ({} consecutive)",
                            clientId, count);

                    } else if (count == 2) {
                        // Second violation → DEGRADED with MEASURED confidence
                        monitor.degraded(MEASURED);
                        logger.warn("Quota DEGRADED (MEASURED): client {} - {} consecutive violations",
                            clientId, count);

                    } else {
                        // First violation → DEGRADED with TENTATIVE confidence
                        monitor.degraded(TENTATIVE);
                        logger.info("Quota DEGRADED (TENTATIVE): client {} - initial violation detected",
                            clientId);
                    }
                }

                case INCREMENT -> {
                    // Approaching quota (90-100% usage) → DIVERGING with MEASURED confidence
                    // Reset violation counter (not sustained violation)
                    violationCounts.put(clientId, 0);
                    monitor.diverging(MEASURED);
                    logger.info("Quota DIVERGING (MEASURED): client {} - approaching quota",
                        clientId);
                }

                case DECREMENT -> {
                    // Usage dropping (<90%) → STABLE or CONVERGING
                    int count = violationCounts.getOrDefault(clientId, 0);
                    violationCounts.put(clientId, 0);  // Reset counter

                    if (count > 0) {
                        // Was violating, now improving → CONVERGING
                        monitor.converging(MEASURED);
                        logger.info("Quota CONVERGING (MEASURED): client {} - recovering from violations",
                            clientId);
                    } else {
                        // Normal operation → STABLE
                        monitor.stable(CONFIRMED);
                        logger.debug("Quota STABLE (CONFIRMED): client {} - normal usage",
                            clientId);
                    }
                }

                case UNDERFLOW -> {
                    // Very low usage → STABLE with CONFIRMED confidence
                    violationCounts.put(clientId, 0);
                    monitor.stable(CONFIRMED);
                    logger.debug("Quota STABLE (CONFIRMED): client {} - very low usage",
                        clientId);
                }

                default -> {
                    // Other signals - no action
                    logger.trace("Quota signal ignored: client {} - {}", clientId, signal);
                }
            }
        });
    }

    /**
     * Get monitors conduit for Layer 3 components to subscribe to.
     *
     * @return Monitors conduit
     */
    public Conduit<Monitor, Monitors.Signal> monitors() {
        return monitors;
    }

    @Override
    public void close() {
        logger.info("Shutting down QuotaHealthMonitor...");
        if (gaugeSubscription != null) {
            gaugeSubscription.close();
        }
        logger.info("QuotaHealthMonitor stopped");
    }
}
