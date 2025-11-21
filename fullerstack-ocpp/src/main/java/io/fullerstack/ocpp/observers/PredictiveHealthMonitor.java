package io.fullerstack.ocpp.observers;

import io.humainary.substrates.Channel;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.Name;
import io.humainary.substrates.Registrar;
import io.humainary.substrates.Subject;
import io.humainary.substrates.Subscription;
import io.humainary.substrates.ext.serventis.ext.monitors.Monitors;
import io.humainary.substrates.ext.serventis.ext.reporters.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Predictive health monitoring with time-series trend analysis.
 * <p>
 * Layer 2.5 (ORIENT - Trend Analysis): Analyzes historical patterns to predict
 * failures BEFORE they happen, enabling preventive maintenance.
 * <p>
 * <b>Predictive Pattern - Failure Prevention:</b>
 * <pre>
 * Historical Signals (days/weeks):
 *   → PredictiveHealthMonitor tracks patterns
 *   → Detects degradation trends
 *   → Emits predictive WARNING before actual failure
 *   → Enables preventive maintenance
 *   → Prevents downtime and safety issues
 * </pre>
 *
 * <h3>Prediction Strategies:</h3>
 * <table border="1">
 * <tr><th>Failure Mode</th><th>Early Indicators</th><th>Predictive Signal</th></tr>
 * <tr><td>Connector failure</td><td>Increasing error frequency</td><td>WARNING: "connector degrading"</td></tr>
 * <tr><td>Communication loss</td><td>Slower response times</td><td>WARNING: "communication degrading"</td></tr>
 * <tr><td>Overheating</td><td>Temperature trending upward</td><td>WARNING: "thermal risk"</td></tr>
 * <tr><td>Power issues</td><td>Voltage fluctuations</td><td>WARNING: "power instability"</td></tr>
 * </table>
 *
 * <h3>Trend Analysis Examples:</h3>
 * <pre>
 * Example 1: Connector Degradation
 *   Day 1: 0 errors
 *   Day 2: 1 error (DEGRADED)
 *   Day 3: 2 errors (DEGRADED)
 *   Day 4: 4 errors (DEGRADED)
 *   → PREDICTION: Connector likely to fail within 2-3 days
 *   → Action: Schedule preventive maintenance
 *   → Result: Replace connector before catastrophic failure
 *
 * Example 2: Communication Degradation
 *   Week 1: Avg response time 100ms
 *   Week 2: Avg response time 200ms
 *   Week 3: Avg response time 400ms
 *   Week 4: Avg response time 800ms
 *   → PREDICTION: Communication failure imminent
 *   → Action: Inspect network, replace hardware
 *   → Result: Prevent total communication loss
 *
 * Example 3: Thermal Creep
 *   Month 1: Max temp 45°C
 *   Month 2: Max temp 50°C
 *   Month 3: Max temp 55°C
 *   Month 4: Max temp 60°C (approaching 65°C limit)
 *   → PREDICTION: Overheating risk within 1 month
 *   → Action: Clean cooling vents, upgrade cooling
 *   → Result: Prevent thermal shutdown or fire hazard
 * </pre>
 *
 * <h3>Benefits of Predictive Maintenance:</h3>
 * <ul>
 *   <li><b>Reduced Downtime</b>: Fix before failure (scheduled vs emergency)</li>
 *   <li><b>Cost Savings</b>: Preventive repair cheaper than emergency replacement</li>
 *   <li><b>Safety</b>: Prevent dangerous failures (fire, shock hazards)</li>
 *   <li><b>Customer Satisfaction</b>: No unexpected charger unavailability</li>
 *   <li><b>Optimized Maintenance</b>: Schedule work during low-demand periods</li>
 * </ul>
 *
 * <h3>Trend Detection Algorithms:</h3>
 * <pre>
 * 1. Frequency Analysis:
 *    - Count DEGRADED/DEFECTIVE signals per time window
 *    - If frequency increasing → predict failure
 *
 * 2. Severity Escalation:
 *    - Track transitions: STABLE → DEGRADED → DEFECTIVE
 *    - If pattern accelerating → predict failure
 *
 * 3. Time-Between-Failures (TBF):
 *    - Measure intervals between failures
 *    - If TBF decreasing → predict imminent failure
 *
 * 4. Threshold Approaching:
 *    - Track numeric values (temp, voltage, response time)
 *    - If trending toward critical threshold → predict failure
 * </pre>
 */
public class PredictiveHealthMonitor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PredictiveHealthMonitor.class);

    // Trend analysis configuration
    private static final int HISTORY_SIZE = 100;  // Keep last 100 events per aspect
    private static final Duration TREND_WINDOW = Duration.ofHours(24);  // Analyze last 24 hours
    private static final Duration ANALYSIS_INTERVAL = Duration.ofMinutes(5);  // Analyze every 5 minutes

    // Thresholds for prediction
    private static final int DEGRADED_COUNT_WARNING = 5;   // 5+ DEGRADED in window → WARNING
    private static final int DEFECTIVE_COUNT_CRITICAL = 3; // 3+ DEFECTIVE in window → CRITICAL
    private static final double FREQUENCY_INCREASE_THRESHOLD = 2.0;  // 2x increase → WARNING

    private final Conduit<Situations.Situation, Situations.Signal> reporters;
    private final Subscription monitorSubscription;
    private final ScheduledExecutorService analysisScheduler;

    // Historical event tracking: "{chargerId}.{aspect}" → EventHistory
    private final Map<String, EventHistory> healthHistory;

    /**
     * Represents a historical monitor event.
     */
    private static class HealthEvent {
        final Instant timestamp;
        final Monitors.Sign sign;

        HealthEvent(Instant timestamp, Monitors.Sign sign) {
            this.timestamp = timestamp;
            this.sign = sign;
        }
    }

    /**
     * Maintains historical events for trend analysis.
     */
    private static class EventHistory {
        final String key;  // "{chargerId}.{aspect}"
        final LinkedList<HealthEvent> events;

        EventHistory(String key) {
            this.key = key;
            this.events = new LinkedList<>();
        }

        void addEvent(Monitors.Sign sign) {
            events.addLast(new HealthEvent(Instant.now(), sign));

            // Keep only recent history
            if (events.size() > HISTORY_SIZE) {
                events.removeFirst();
            }
        }

        /**
         * Get events within time window.
         */
        LinkedList<HealthEvent> getEventsInWindow(Duration window) {
            Instant cutoff = Instant.now().minus(window);
            LinkedList<HealthEvent> recent = new LinkedList<>();

            for (HealthEvent event : events) {
                if (event.timestamp.isAfter(cutoff)) {
                    recent.add(event);
                }
            }

            return recent;
        }

        /**
         * Count events of specific sign within window.
         */
        int countInWindow(Monitors.Sign sign, Duration window) {
            return (int) getEventsInWindow(window).stream()
                .filter(e -> e.sign == sign)
                .count();
        }

        /**
         * Detect if frequency is increasing.
         */
        boolean isFrequencyIncreasing(Monitors.Sign sign) {
            // Compare first half vs second half of window
            LinkedList<HealthEvent> windowEvents = getEventsInWindow(TREND_WINDOW);
            if (windowEvents.size() < 10) {
                return false;  // Not enough data
            }

            int midpoint = windowEvents.size() / 2;
            LinkedList<HealthEvent> firstHalf = new LinkedList<>(windowEvents.subList(0, midpoint));
            LinkedList<HealthEvent> secondHalf = new LinkedList<>(windowEvents.subList(midpoint, windowEvents.size()));

            long firstCount = firstHalf.stream().filter(e -> e.sign == sign).count();
            long secondCount = secondHalf.stream().filter(e -> e.sign == sign).count();

            // Frequency increased by threshold factor
            return secondCount >= firstCount * FREQUENCY_INCREASE_THRESHOLD && secondCount > 0;
        }

        /**
         * Detect rapid degradation pattern.
         */
        boolean isRapidlyDegrading() {
            LinkedList<HealthEvent> recent = getEventsInWindow(Duration.ofHours(2));
            if (recent.size() < 3) {
                return false;
            }

            // Check if last 3 events show escalation: STABLE → DEGRADED → DEFECTIVE
            int size = recent.size();
            if (size >= 3) {
                HealthEvent third = recent.get(size - 3);
                HealthEvent second = recent.get(size - 2);
                HealthEvent first = recent.get(size - 1);

                return third.sign == Monitors.Sign.STABLE &&
                       second.sign == Monitors.Sign.DEGRADED &&
                       first.sign == Monitors.Sign.CRITICAL;
            }

            return false;
        }
    }

    public PredictiveHealthMonitor(
        Conduit<Monitors.Monitor, Monitors.Sign> monitors,
        Conduit<Situations.Situation, Situations.Signal> reporters
    ) {
        this.reporters = reporters;
        this.healthHistory = new ConcurrentHashMap<>();

        // Subscribe to all monitor signals
        this.monitorSubscription = monitors.subscribe(
            cortex().subscriber(
                cortex().name("predictive-health-subscriber"),
                this::handleMonitorSignal
            )
        );

        // Start periodic trend analysis
        this.analysisScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "predictive-health-analyzer");
            t.setDaemon(true);
            return t;
        });

        startAnalysis();

        logger.info("PredictiveHealthMonitor initialized (analyzing every {} minutes, {} hour window)",
            ANALYSIS_INTERVAL.toMinutes(), TREND_WINDOW.toHours());
    }

    /**
     * Handle incoming monitor signals and record for trend analysis.
     */
    private void handleMonitorSignal(
        Subject<Channel<Monitors.Sign>> subject,
        Registrar<Monitors.Sign> registrar
    ) {
        Name monitorName = subject.name();
        String key = formatKey(monitorName);

        registrar.register(sign -> {
            // Record event in history
            EventHistory history = healthHistory.computeIfAbsent(key, EventHistory::new);
            history.addEvent(sign);

            logger.trace("Recorded health event: {} → {}", key, sign);
        });
    }

    /**
     * Start periodic trend analysis.
     */
    private void startAnalysis() {
        analysisScheduler.scheduleAtFixedRate(() -> {
            try {
                analyzeTrends();
            } catch (Exception e) {
                logger.error("Error analyzing health trends", e);
            }
        }, ANALYSIS_INTERVAL.toMinutes(), ANALYSIS_INTERVAL.toMinutes(), TimeUnit.MINUTES);
    }

    /**
     * Analyze all tracked health histories for predictive patterns.
     */
    private void analyzeTrends() {
        logger.debug("Analyzing health trends across {} aspects", healthHistory.size());

        for (EventHistory history : healthHistory.values()) {
            try {
                analyzeSingleAspect(history);
            } catch (Exception e) {
                logger.error("Error analyzing trend for {}: {}", history.key, e.getMessage(), e);
            }
        }
    }

    /**
     * Analyze trends for a single charger aspect.
     */
    private void analyzeSingleAspect(EventHistory history) {
        String key = history.key;

        // Count recent problematic events
        int degradedCount = history.countInWindow(Monitors.Sign.DEGRADED, TREND_WINDOW);
        int defectiveCount = history.countInWindow(Monitors.Sign.CRITICAL, TREND_WINDOW);

        // Detect patterns
        boolean frequencyIncreasing = history.isFrequencyIncreasing(Monitors.Sign.DEGRADED) ||
                                     history.isFrequencyIncreasing(Monitors.Sign.CRITICAL);
        boolean rapidlyDegrading = history.isRapidlyDegrading();

        // Predict failures
        if (defectiveCount >= DEFECTIVE_COUNT_CRITICAL || rapidlyDegrading) {
            emitPredictiveCritical(key, defectiveCount, rapidlyDegrading);
        } else if (degradedCount >= DEGRADED_COUNT_WARNING || frequencyIncreasing) {
            emitPredictiveWarning(key, degradedCount, frequencyIncreasing);
        }
    }

    /**
     * Emit predictive CRITICAL signal - failure likely imminent.
     */
    private void emitPredictiveCritical(String key, int defectiveCount, boolean rapidlyDegrading) {
        String reason = rapidlyDegrading ?
            "rapid degradation pattern detected" :
            defectiveCount + " defective events in " + TREND_WINDOW.toHours() + "h";

        logger.error("[PREDICTIVE] CRITICAL: {} - {}", key, reason);

        Situations.Situation reporter = reporters.percept(
            cortex().name("predictive").name(key)
        );
        reporter.critical(Situations.Dimension.PREDICTED);

        logger.error("🔴 PREDICTION: {} likely to fail soon - {}", key, reason);
    }

    /**
     * Emit predictive WARNING signal - degradation trend detected.
     */
    private void emitPredictiveWarning(String key, int degradedCount, boolean frequencyIncreasing) {
        String reason = frequencyIncreasing ?
            "error frequency increasing" :
            degradedCount + " degraded events in " + TREND_WINDOW.toHours() + "h";

        logger.warn("[PREDICTIVE] WARNING: {} - {}", key, reason);

        Situations.Situation reporter = reporters.percept(
            cortex().name("predictive").name(key)
        );
        reporter.warning(Situations.Dimension.PREDICTED);

        logger.warn("⚠️  PREDICTION: {} showing degradation trend - {}", key, reason);
    }

    /**
     * Format monitor name to key for tracking.
     */
    private String formatKey(Name name) {
        // Convert hierarchical name to dot-separated key
        // e.g., cortex().name("CP001").name("connector") → "CP001.connector"
        StringBuilder sb = new StringBuilder();
        Name current = name;

        while (current != null) {
            if (sb.length() > 0) {
                sb.insert(0, ".");
            }
            sb.insert(0, current.value());
            current = current.enclosure();
        }

        return sb.toString();
    }

    /**
     * Get event count for testing/monitoring.
     */
    public int getEventCount(String key) {
        EventHistory history = healthHistory.get(key);
        return history != null ? history.events.size() : 0;
    }

    @Override
    public void close() {
        logger.info("Closing PredictiveHealthMonitor");
        if (monitorSubscription != null) {
            monitorSubscription.cancel();
        }
        analysisScheduler.shutdown();
        try {
            if (!analysisScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                analysisScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            analysisScheduler.shutdownNow();
        }
        healthHistory.clear();
    }
}
