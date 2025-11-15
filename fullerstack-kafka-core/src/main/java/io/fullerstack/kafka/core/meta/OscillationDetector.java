package io.fullerstack.kafka.core.meta;

import io.humainary.substrates.ext.serventis.ext.monitors.Monitors;
import io.humainary.substrates.ext.serventis.ext.reporters.Reporters;
import io.humainary.substrates.ext.serventis.ext.reporters.Reporters.Reporter;
import io.humainary.substrates.api.Substrates.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Meta-Monitoring: Detects oscillation in the monitoring system itself.
 * <p>
 * When monitors rapidly alternate between conditions (e.g., DEGRADED ↔ STABLE), it indicates:
 * <ul>
 *   <li>Thresholds are too sensitive</li>
 *   <li>System is in metastable state</li>
 *   <li>Feedback loops are too aggressive</li>
 *   <li>Self-regulation is thrashing</li>
 * </ul>
 * <p>
 * <b>Signal-Flow Pattern (Meta-Layer):</b>
 * <pre>
 * Layer 2: Monitors → emit condition changes (DEGRADED, STABLE, etc.)
 *                          ↓
 * Meta-Layer: OscillationDetector → detect oscillation pattern
 *                          ↓
 * Layer 3: MetaReporter → emit WARNING "Monitoring system unstable"
 * </pre>
 *
 * <h3>Oscillation Detection Algorithm:</h3>
 * <ul>
 *   <li>Track condition changes per monitor over sliding 60-second window</li>
 *   <li>If 3+ condition changes with 2+ distinct conditions → OSCILLATING</li>
 *   <li>Emit meta-reporter WARNING to alert operators</li>
 *   <li>Suggests: Adjust thresholds, increase cooldown periods, tune feedback loops</li>
 * </ul>
 *
 * <h3>Example Oscillation:</h3>
 * <pre>
 * t=0s:  STABLE
 * t=5s:  DEGRADED     ← Change #1
 * t=10s: STABLE       ← Change #2 (back to STABLE)
 * t=15s: DEGRADED     ← Change #3 (OSCILLATING!)
 *
 * → OscillationDetector emits WARNING
 * → Suggests: Increase confidence threshold, add hysteresis
 * </pre>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("kafka.observability"));
 *
 * // Create monitors (Layer 2)
 * JvmHealthMonitor jvmMonitor = new JvmHealthMonitor(circuit, gauges);
 *
 * // Create meta-monitoring (watches ALL monitors)
 * OscillationDetector oscillationDetector = new OscillationDetector(
 *     circuit,
 *     jvmMonitor.monitors()  // Subscribe to monitor signals
 * );
 *
 * // Subscribe to oscillation warnings
 * oscillationDetector.reporters().subscribe(...);
 *
 * // ... later ...
 * oscillationDetector.close();
 * }</pre>
 *
 * @see io.humainary.substrates.ext.serventis.ext.monitors.Monitors
 * @see io.humainary.substrates.ext.serventis.ext.reporters.Reporters
 */
public class OscillationDetector implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(OscillationDetector.class);

    private static final int OSCILLATION_THRESHOLD = 3;  // Number of changes to trigger
    private static final long OSCILLATION_WINDOW_MS = 60_000;  // 60 seconds
    private static final int MIN_UNIQUE_CONDITIONS = 2;  // Must alternate between conditions

    private final Cortex cortex;
    private final Circuit circuit;
    private final Conduit<Reporter, Reporters.Sign> reporters;
    private final Reporter metaReporter;
    private final Subscription monitorSubscription;

    // Track condition changes per monitor
    private final Map<String, Deque<ConditionChange>> changeHistory = new ConcurrentHashMap<>();

    /**
     * Creates a new oscillation detector.
     *
     * @param circuit parent circuit for reporters conduit
     * @param monitors monitors conduit to watch for oscillation
     */
    public OscillationDetector(
        Circuit circuit,
        Conduit<Monitors.Monitor, Monitors.Signal> monitors
    ) {
        this.cortex = cortex();
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        Objects.requireNonNull(monitors, "monitors cannot be null");

        // Create Reporters conduit for meta-monitoring warnings
        this.reporters = circuit.conduit(
            cortex.name("meta.reporters"),
            Reporters::composer
        );

        // Create meta-reporter for oscillation warnings
        this.metaReporter = reporters.channel(cortex.name("meta.oscillation"));

        // Subscribe to ALL monitor signals
        this.monitorSubscription = monitors.subscribe(cortex.subscriber(
            cortex.name("oscillation-detector"),
            this::handleMonitorSignal
        ));

        logger.info("OscillationDetector started - meta-monitoring enabled");
    }

    /**
     * Handle monitor signals and detect oscillation patterns.
     */
    private void handleMonitorSignal(
        Subject<Channel<Monitors.Signal>> subject,
        Registrar<Monitors.Signal> registrar
    ) {
        registrar.register(signal -> {
            String monitorName = subject.name().toString();
            Monitors.Sign condition = signal.sign();

            logger.debug("Meta-monitoring: {} -> {}", monitorName, condition);

            // Track condition change
            Deque<ConditionChange> history = changeHistory.computeIfAbsent(
                monitorName,
                k -> new ArrayDeque<>()
            );

            // Add new change
            history.addLast(new ConditionChange(condition));

            // Remove old changes outside window
            Instant cutoff = Instant.now().minusMillis(OSCILLATION_WINDOW_MS);
            while (!history.isEmpty() && history.peekFirst().timestamp.isBefore(cutoff)) {
                history.pollFirst();
            }

            // Detect oscillation
            if (isOscillating(history)) {
                handleOscillation(monitorName, history);
            }
        });
    }

    /**
     * Check if monitor is oscillating.
     * <p>
     * Criteria:
     * - 3+ condition changes within 60 seconds
     * - 2+ distinct conditions (must be alternating, not just repeating same condition)
     */
    private boolean isOscillating(Deque<ConditionChange> history) {
        if (history.size() < OSCILLATION_THRESHOLD) {
            return false;  // Not enough changes
        }

        // Check for distinct conditions (ensure alternation, not repetition)
        Set<Monitors.Sign> uniqueConditions = new HashSet<>();
        history.forEach(change -> uniqueConditions.add(change.condition));

        return uniqueConditions.size() >= MIN_UNIQUE_CONDITIONS;
    }

    /**
     * Handle detected oscillation.
     * <p>
     * Emits WARNING via meta-reporter and logs diagnostic information.
     */
    private void handleOscillation(String monitorName, Deque<ConditionChange> history) {
        // Emit WARNING via meta-reporter
        metaReporter.warning();

        // Collect oscillation details
        Set<Monitors.Sign> uniqueConditions = new HashSet<>();
        history.forEach(change -> uniqueConditions.add(change.condition));

        long windowDuration = history.isEmpty() ? 0 :
            java.time.Duration.between(
                history.peekFirst().timestamp,
                history.peekLast().timestamp
            ).toMillis();

        // Log comprehensive warning
        logger.warn("╔══════════════════════════════════════════════════════════════╗");
        logger.warn("║ OSCILLATION DETECTED - Monitoring System Unstable            ║");
        logger.warn("╠══════════════════════════════════════════════════════════════╣");
        logger.warn("║ Monitor:     {:<48}║", monitorName);
        logger.warn("║ Changes:     {:<48}║", history.size() + " condition changes");
        logger.warn("║ Conditions:  {:<48}║", uniqueConditions);
        logger.warn("║ Window:      {:<48}║", windowDuration + "ms");
        logger.warn("╠══════════════════════════════════════════════════════════════╣");
        logger.warn("║ SUGGESTED ACTIONS:                                          ║");
        logger.warn("║ 1. Increase confidence thresholds (require more evidence)   ║");
        logger.warn("║ 2. Add hysteresis (different thresholds for up/down)        ║");
        logger.warn("║ 3. Increase cooldown periods in self-regulators             ║");
        logger.warn("║ 4. Tune feedback loop gains (less aggressive regulation)    ║");
        logger.warn("║ 5. Check for external load oscillations                     ║");
        logger.warn("╚══════════════════════════════════════════════════════════════╝");

        // Clear history to avoid repeated warnings for same oscillation
        history.clear();
    }

    /**
     * Get reporters conduit for subscribing to meta-monitoring warnings.
     *
     * @return reporters conduit emitting oscillation warnings
     */
    public Conduit<Reporter, Reporters.Sign> reporters() {
        return reporters;
    }

    /**
     * Get oscillation statistics for monitoring.
     *
     * @return map of monitor names to oscillation counts
     */
    public Map<String, Integer> getOscillationStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        changeHistory.forEach((monitor, history) -> {
            if (isOscillating(history)) {
                stats.put(monitor, history.size());
            }
        });
        return Collections.unmodifiableMap(stats);
    }

    @Override
    public void close() {
        logger.info("Shutting down oscillation detector");

        if (monitorSubscription != null) {
            monitorSubscription.close();
        }

        logger.info("Oscillation detector stopped");
    }

    /**
     * Represents a condition change with timestamp.
     */
    private static class ConditionChange {
        final Monitors.Sign condition;
        final Instant timestamp;

        ConditionChange(Monitors.Sign condition) {
            this.condition = condition;
            this.timestamp = Instant.now();
        }

        @Override
        public String toString() {
            return String.format("%s@%s", condition, timestamp);
        }
    }
}
