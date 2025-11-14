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
 * Layer 2 (ORIENT): Assesses network throttle health by detecting patterns in throttle signals.
 *
 * <p><b>Responsibilities (ORIENTATION/ASSESSMENT):</b>
 * <ul>
 *   <li>Subscribe to throttle Gauge signals from Layer 1 Receptor</li>
 *   <li>Detect patterns: sustained OVERFLOW, intermittent INCREMENT, stable DECREMENT</li>
 *   <li>Emit Monitor conditions based on pattern recognition</li>
 *   <li>Track confidence dimensions: TENTATIVE → MEASURED → CONFIRMED</li>
 * </ul>
 *
 * <p><b>Pattern Detection Logic:</b>
 * <ul>
 *   <li>OVERFLOW signal → increment overflow counter
 *     <ul>
 *       <li>1 OVERFLOW → DIVERGING (TENTATIVE) - "might be a problem"</li>
 *       <li>2 OVERFLOW → DIVERGING (MEASURED) - "looks like a problem"</li>
 *       <li>3+ OVERFLOW → DEGRADED (CONFIRMED) - "definitely a problem"</li>
 *     </ul>
 *   </li>
 *   <li>INCREMENT signal → DIVERGING (MEASURED) - "elevated but not critical"</li>
 *   <li>DECREMENT signal → reset counter, emit STABLE (CONFIRMED)</li>
 * </ul>
 *
 * <p><b>Signal Flow:</b>
 * <pre>
 * Layer 1 Gauges → ThrottleHealthMonitor (subscribes) → Monitors
 *                                                            ↓
 *                                            (Layer 3 Reporters subscribe here)
 * </pre>
 *
 * <p><b>Example Usage:</b>
 * <pre>
 * ThrottleHealthMonitor monitor = new ThrottleHealthMonitor(circuit, receptor.gauges());
 * // Monitor automatically subscribes to gauge signals and emits condition assessments
 * </pre>
 */
public class ThrottleHealthMonitor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ThrottleHealthMonitor.class);

    private final Circuit circuit;
    private final Conduit<Monitor, Monitors.Sign> monitors;
    private final Subscription gaugeSubscription;

    // Track overflow counts per throttle gauge (for pattern detection)
    private final Map<String, Integer> overflowCounts = new ConcurrentHashMap<>();

    // Monitor instances per throttle type
    private final Map<String, Monitor> throttleMonitors = new ConcurrentHashMap<>();

    /**
     * Creates a ThrottleHealthMonitor (Layer 2 - ORIENT).
     *
     * @param circuit Circuit for Substrates infrastructure
     * @param gauges  Gauges conduit from Layer 1 Observer to subscribe to
     * @throws NullPointerException if any parameter is null
     */
    public ThrottleHealthMonitor(
        Circuit circuit,
        Conduit<Gauges.Gauge, Gauges.Sign> gauges
    ) {
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        Objects.requireNonNull(gauges, "gauges cannot be null");

        // Create Layer 2 monitors conduit
        this.monitors = circuit.conduit(cortex().name("monitors"), Monitors::composer);

        // Subscribe to ALL gauge signals from Layer 1
        this.gaugeSubscription = gauges.subscribe(cortex().subscriber(
            cortex().name("throttle-health-monitor"),
            this::handleGaugeSignal
        ));

        logger.info("ThrottleHealthMonitor started (Layer 2 - ORIENT, subscribed to gauges)");
    }

    /**
     * Handles gauge signals from Layer 1 and emits Monitor conditions.
     * This is where PATTERN DETECTION happens.
     */
    private void handleGaugeSignal(Subject<Channel<Gauges.Sign>> subject, Registrar<Gauges.Sign> registrar) {
        registrar.register(signal -> {
            // Extract entity name from Subject (e.g., "network.throttle.produce")
            String gaugeName = subject.name().toString();

            // Only process throttle-related gauges
            if (!gaugeName.contains("throttle")) {
                return;
            }

            // Get or create Monitor for this throttle type
            Monitor monitor = throttleMonitors.computeIfAbsent(gaugeName,
                name -> monitors.percept(cortex().name(name.replace("gauges/network.throttle.", "monitor.throttle."))));

            // Pattern detection based on signal type
            switch (signal.sign()) {
                case OVERFLOW -> {
                    // Increment overflow counter for pattern detection
                    int count = overflowCounts.compute(gaugeName, (k, v) -> (v == null) ? 1 : v + 1);

                    // Emit condition based on sustained pattern
                    if (count >= 3) {
                        // Sustained overflow (3+ consecutive) → DEGRADED with CONFIRMED confidence
                        monitor.degraded(CONFIRMED);
                        logger.warn("Throttle DEGRADED (CONFIRMED): {} - sustained overflow ({} consecutive)",
                            gaugeName, count);

                    } else if (count == 2) {
                        // Second overflow → DIVERGING with MEASURED confidence
                        monitor.diverging(MEASURED);
                        logger.warn("Throttle DIVERGING (MEASURED): {} - {} consecutive overflows",
                            gaugeName, count);

                    } else {
                        // First overflow → DIVERGING with TENTATIVE confidence
                        monitor.diverging(TENTATIVE);
                        logger.info("Throttle DIVERGING (TENTATIVE): {} - initial overflow detected",
                            gaugeName);
                    }
                }

                case INCREMENT -> {
                    // Elevated throttling (not critical yet) → DIVERGING with MEASURED confidence
                    // Reset overflow counter (not sustained overflow pattern)
                    overflowCounts.put(gaugeName, 0);
                    monitor.diverging(MEASURED);
                    logger.info("Throttle DIVERGING (MEASURED): {} - elevated throttling",
                        gaugeName);
                }

                case DECREMENT -> {
                    // Throttling returning to normal → STABLE or CONVERGING
                    int count = overflowCounts.getOrDefault(gaugeName, 0);
                    overflowCounts.put(gaugeName, 0);  // Reset counter

                    if (count > 0) {
                        // Was degraded, now improving → CONVERGING
                        monitor.converging(MEASURED);
                        logger.info("Throttle CONVERGING (MEASURED): {} - recovering from degradation",
                            gaugeName);
                    } else {
                        // Normal operation → STABLE
                        monitor.stable(CONFIRMED);
                        logger.debug("Throttle STABLE (CONFIRMED): {} - normal operation",
                            gaugeName);
                    }
                }

                case UNDERFLOW -> {
                    // Very low throttling → STABLE with CONFIRMED confidence
                    overflowCounts.put(gaugeName, 0);
                    monitor.stable(CONFIRMED);
                    logger.debug("Throttle STABLE (CONFIRMED): {} - very low throttling",
                        gaugeName);
                }

                default -> {
                    // Other signals - no action
                    logger.trace("Throttle signal ignored: {} - {}", gaugeName, signal.sign());
                }
            }
        });
    }

    /**
     * Get monitors conduit for Layer 3 components to subscribe to.
     *
     * @return Monitors conduit
     */
    public Conduit<Monitor, Monitors.Sign> monitors() {
        return monitors;
    }

    @Override
    public void close() {
        logger.info("Shutting down ThrottleHealthMonitor...");
        if (gaugeSubscription != null) {
            gaugeSubscription.close();
        }
        logger.info("ThrottleHealthMonitor stopped");
    }
}
