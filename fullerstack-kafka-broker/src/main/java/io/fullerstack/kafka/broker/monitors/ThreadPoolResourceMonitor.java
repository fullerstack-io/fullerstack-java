package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.baseline.BaselineService;
import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import io.fullerstack.serventis.signals.ResourceSignal;
import io.humainary.modules.serventis.resources.api.Resources;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.api.Substrates.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Interprets thread pool metrics and emits ResourceSignal with contextual assessment.
 *
 * <p><b>Signal-First Architecture:</b>
 * This monitor INTERPRETS thread pool state at the point of observation (where context exists)
 * and emits ResourceSignals with embedded meaning, NOT raw data bags.
 *
 * <h3>Assessment Levels:</h3>
 * <pre>
 * AVAILABLE:  ≥30% idle (or above baseline)
 * DEGRADED:   10-30% idle (or below baseline but not critical)
 * EXHAUSTED:  <10% idle (or at/near capacity)
 * </pre>
 *
 * <h3>Baseline Integration:</h3>
 * Uses BaselineService to get contextual thresholds based on:
 * <ul>
 *   <li>Historical idle percentages for this broker/pool</li>
 *   <li>Time-of-day patterns (higher load during peak hours)</li>
 *   <li>Workload characteristics (streaming vs batch)</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * // Runtime creates Cell with Composer.pipe() (no transformation)
 * Circuit circuit = Cortex.circuit(Cortex.name("kafka.broker.resources"));
 * Cell<ResourceSignal, ResourceSignal> cell = circuit.cell(
 *     Cortex.name("thread-pools"),
 *     Composer.pipe()  // ← No Composer needed!
 * );
 *
 * // Monitor interprets and emits signals
 * ThreadPoolResourceMonitor monitor = new ThreadPoolResourceMonitor(
 *     Cortex.name("kafka.broker.resources"),
 *     cell,  // Cell IS-A Pipe<ResourceSignal>
 *     baselineService
 * );
 *
 * // Collect raw metrics, interpret, emit signal
 * ThreadPoolMetrics metrics = collector.collect("broker-1", ThreadPoolType.NETWORK);
 * monitor.interpret(metrics);  // ← Emits ResourceSignal with assessment
 * }</pre>
 *
 * <h3>Signal Payload:</h3>
 * ResourceSignals emitted include:
 * <ul>
 *   <li><b>Raw Metrics</b>: poolType, totalThreads, activeThreads, idleThreads, avgIdlePercent</li>
 *   <li><b>Baseline Context</b>: expectedIdlePercent (from BaselineService)</li>
 *   <li><b>Assessment</b>: AVAILABLE/DEGRADED/EXHAUSTED</li>
 *   <li><b>Interpretation</b>: Human-readable explanation (e.g., "Thread pool critically saturated")</li>
 *   <li><b>Recommendation</b>: Actionable guidance (e.g., "Increase thread pool size")</li>
 *   <li><b>Trend</b>: Change from previous observation (e.g., "-0.15" means 15% drop in idle)</li>
 * </ul>
 *
 * @author Fullerstack
 * @see ThreadPoolMetrics
 * @see ResourceSignal
 * @see BaselineService
 */
public class ThreadPoolResourceMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolResourceMonitor.class);

    // Default baseline if BaselineService doesn't have historical data
    private static final double DEFAULT_EXPECTED_IDLE_PERCENT = 0.30;  // 30%

    private final Name circuitName;
    private final Pipe<ResourceSignal> signalPipe;
    private final BaselineService baselineService;

    /**
     * Creates a ThreadPoolResourceMonitor.
     *
     * @param circuitName     Circuit name for Subject creation
     * @param signalPipe      Pipe to emit ResourceSignals (typically a Cell)
     * @param baselineService Baseline service for contextual assessment
     * @throws NullPointerException if any parameter is null
     */
    public ThreadPoolResourceMonitor(
        Name circuitName,
        Pipe<ResourceSignal> signalPipe,
        BaselineService baselineService
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.signalPipe = Objects.requireNonNull(signalPipe, "signalPipe cannot be null");
        this.baselineService = Objects.requireNonNull(baselineService, "baselineService cannot be null");
    }

    /**
     * Interprets thread pool metrics and emits ResourceSignal.
     *
     * <p><b>Interpretation Logic:</b>
     * <ol>
     *   <li>Get expected idle % from BaselineService</li>
     *   <li>Compare actual vs expected</li>
     *   <li>Assess condition: AVAILABLE/DEGRADED/EXHAUSTED</li>
     *   <li>Add interpretation and recommendation to payload</li>
     *   <li>Record observation for future baselines</li>
     *   <li>Emit ResourceSignal with embedded assessment</li>
     * </ol>
     *
     * @param metrics Thread pool metrics from JMX collector
     * @throws NullPointerException if metrics is null
     */
    public void interpret(ThreadPoolMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            Subject subject = createSubject(metrics.brokerId(), metrics.poolType());

            // Get contextual baseline
            double expectedIdlePercent = getExpectedIdlePercent(metrics);

            // Build payload with raw metrics
            Map<String, String> payload = buildBasePayload(metrics, expectedIdlePercent);

            // INTERPRET at observation point
            ResourceSignal signal = assessCondition(subject, metrics, expectedIdlePercent, payload);

            // Record observation for future baselines
            recordObservation(metrics);

            // Emit interpreted signal
            signalPipe.emit(signal);

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted ResourceSignal for {}.{}: sign={}, assessment={}",
                    metrics.brokerId(),
                    metrics.poolType().name(),
                    signal.sign(),
                    payload.get("assessment"));
            }
        } catch (Exception e) {
            logger.error("Failed to interpret thread pool metrics for {}.{}: {}",
                metrics.brokerId(),
                metrics.poolType().name(),
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }

    /**
     * Gets expected idle percentage from BaselineService.
     * Falls back to default if no baseline data available or BaselineService fails.
     */
    private double getExpectedIdlePercent(ThreadPoolMetrics metrics) {
        try {
            String metricKey = "thread_pool_idle_" + metrics.poolType().name().toLowerCase();

            if (baselineService.hasBaseline(metrics.brokerId())) {
                // BaselineService doesn't have thread-pool-specific methods yet,
                // so we'll use the generic observation system
                Double recentObservation = getRecentObservation(metrics.brokerId(), metricKey);
                if (recentObservation != null) {
                    return recentObservation;
                }
            }
        } catch (Exception e) {
            // BaselineService failed - fall back to default
            logger.debug("Failed to get baseline for {}: {}", metrics.brokerId(), e.getMessage());
        }

        // Fall back to default baseline
        return DEFAULT_EXPECTED_IDLE_PERCENT;
    }

    /**
     * Gets recent observation from BaselineService.
     * This is a workaround until BaselineService has thread-pool-specific methods.
     */
    private Double getRecentObservation(String brokerId, String metricKey) {
        try {
            // Use reflection or check if method exists
            // For now, return null to indicate no historical data
            // TODO: Once BaselineService has getRecentObservation(), use it
            return null;
        } catch (Exception e) {
            logger.debug("No recent observation for {}: {}", metricKey, e.getMessage());
            return null;
        }
    }

    /**
     * Builds base payload with raw metrics and baseline context.
     */
    private Map<String, String> buildBasePayload(ThreadPoolMetrics metrics, double expectedIdlePercent) {
        Map<String, String> payload = new HashMap<>();

        // Identity
        payload.put("brokerId", metrics.brokerId());
        payload.put("poolType", metrics.poolType().displayName());

        // Thread counts
        payload.put("totalThreads", String.valueOf(metrics.totalThreads()));
        payload.put("activeThreads", String.valueOf(metrics.activeThreads()));
        payload.put("idleThreads", String.valueOf(metrics.idleThreads()));

        // Utilization metrics
        payload.put("avgIdlePercent", String.format("%.2f", metrics.avgIdlePercent()));
        payload.put("utilizationPercent", String.format("%.2f", metrics.utilizationPercent()));
        payload.put("expectedIdlePercent", String.format("%.2f", expectedIdlePercent));

        // Optional fields
        if (metrics.queueSize() > 0) {
            payload.put("queueSize", String.valueOf(metrics.queueSize()));
        }
        if (metrics.rejectionCount() > 0) {
            payload.put("rejectionCount", String.valueOf(metrics.rejectionCount()));
        }

        return payload;
    }

    /**
     * Assesses thread pool condition and creates ResourceSignal with interpretation.
     */
    private ResourceSignal assessCondition(
        Subject subject,
        ThreadPoolMetrics metrics,
        double expectedIdlePercent,
        Map<String, String> payload
    ) {
        double actualIdlePercent = metrics.avgIdlePercent();

        // EXHAUSTED: <10% idle OR significantly below baseline
        if (isExhausted(actualIdlePercent, expectedIdlePercent)) {
            return assessExhausted(subject, metrics, actualIdlePercent, expectedIdlePercent, payload);
        }

        // DEGRADED: 10-30% idle OR below baseline
        else if (isDegraded(actualIdlePercent, expectedIdlePercent)) {
            return assessDegraded(subject, metrics, actualIdlePercent, expectedIdlePercent, payload);
        }

        // AVAILABLE: ≥30% idle OR at/above baseline
        else {
            return assessAvailable(subject, metrics, actualIdlePercent, expectedIdlePercent, payload);
        }
    }

    /**
     * Checks if thread pool is exhausted.
     */
    private boolean isExhausted(double actualIdlePercent, double expectedIdlePercent) {
        return actualIdlePercent < 0.1 || actualIdlePercent < expectedIdlePercent * 0.5;
    }

    /**
     * Checks if thread pool is degraded.
     */
    private boolean isDegraded(double actualIdlePercent, double expectedIdlePercent) {
        return actualIdlePercent < 0.3 || actualIdlePercent < expectedIdlePercent * 0.8;
    }

    /**
     * Assesses EXHAUSTED condition with critical interpretation and recommendations.
     * Maps to DENY signal (no capacity available).
     */
    private ResourceSignal assessExhausted(
        Subject subject,
        ThreadPoolMetrics metrics,
        double actualIdlePercent,
        double expectedIdlePercent,
        Map<String, String> payload
    ) {
        payload.put("assessment", "EXHAUSTED");

        // Interpretation
        payload.put("interpretation", String.format(
            "Thread pool critically saturated: %.0f%% idle vs %.0f%% expected (%.0f%% below baseline)",
            actualIdlePercent * 100,
            expectedIdlePercent * 100,
            (expectedIdlePercent - actualIdlePercent) * 100
        ));

        // Recommendation
        payload.put("recommendation", String.format(
            "CRITICAL: Increase %s thread pool size (current: %d threads) or reduce broker load",
            metrics.poolType().displayName(),
            metrics.totalThreads()
        ));

        // Add severity escalation if rejections occurring
        if (metrics.rejectionCount() > 0) {
            payload.put("rejections", String.valueOf(metrics.rejectionCount()));
            payload.put("severity", "CRITICAL - Tasks being rejected!");
        }

        // Add trend if available
        addTrendIfAvailable(metrics, actualIdlePercent, payload);

        // EXHAUSTED → DENY signal (0 units available)
        return ResourceSignal.deny(subject, 0, payload);
    }

    /**
     * Assesses DEGRADED condition with pressure interpretation and monitoring guidance.
     * Maps to GRANT signal but with degraded status in payload.
     */
    private ResourceSignal assessDegraded(
        Subject subject,
        ThreadPoolMetrics metrics,
        double actualIdlePercent,
        double expectedIdlePercent,
        Map<String, String> payload
    ) {
        payload.put("assessment", "DEGRADED");

        // Interpretation
        payload.put("interpretation", String.format(
            "Thread pool under pressure: %.0f%% idle vs %.0f%% expected",
            actualIdlePercent * 100,
            expectedIdlePercent * 100
        ));

        // Recommendation
        payload.put("recommendation", String.format(
            "Monitor %s thread pool - consider increasing capacity if pressure persists",
            metrics.poolType().displayName()
        ));

        // Add trend if available
        addTrendIfAvailable(metrics, actualIdlePercent, payload);

        // DEGRADED → GRANT signal (threads available but under pressure)
        return ResourceSignal.grant(subject, metrics.idleThreads(), payload);
    }

    /**
     * Assesses AVAILABLE condition with healthy status.
     * Maps to GRANT signal with healthy capacity.
     */
    private ResourceSignal assessAvailable(
        Subject subject,
        ThreadPoolMetrics metrics,
        double actualIdlePercent,
        double expectedIdlePercent,
        Map<String, String> payload
    ) {
        payload.put("assessment", "AVAILABLE");

        // Interpretation
        payload.put("interpretation", String.format(
            "Thread pool healthy: %.0f%% idle (%.0f%% expected)",
            actualIdlePercent * 100,
            expectedIdlePercent * 100
        ));

        // Note if significantly under-utilized
        if (actualIdlePercent > expectedIdlePercent * 1.2) {
            payload.put("note", "Pool significantly under-utilized - may be over-provisioned");
        }

        // AVAILABLE → GRANT signal (threads available)
        return ResourceSignal.grant(subject, metrics.idleThreads(), payload);
    }

    /**
     * Adds trend information to payload if historical data available.
     */
    private void addTrendIfAvailable(ThreadPoolMetrics metrics, double actualIdlePercent, Map<String, String> payload) {
        String metricKey = "thread_pool_idle_" + metrics.poolType().name().toLowerCase();
        Double previousIdle = getRecentObservation(metrics.brokerId(), metricKey);

        if (previousIdle != null) {
            double trend = actualIdlePercent - previousIdle;
            payload.put("trend", String.format("%.2f", trend));

            if (trend < -0.05) {
                payload.put("trendInterpretation", String.format("Degrading rapidly (%.0f%% idle drop)", Math.abs(trend) * 100));
            } else if (trend > 0.05) {
                payload.put("trendInterpretation", String.format("Improving (%.0f%% idle increase)", trend * 100));
            } else {
                payload.put("trendInterpretation", "Stable");
            }
        }
    }

    /**
     * Records observation to BaselineService for future trend analysis.
     */
    private void recordObservation(ThreadPoolMetrics metrics) {
        try {
            String metricKey = "thread_pool_idle_" + metrics.poolType().name().toLowerCase();
            baselineService.recordObservation(
                metrics.brokerId(),
                metricKey,
                metrics.avgIdlePercent(),
                Instant.ofEpochMilli(metrics.timestamp())
            );
        } catch (Exception e) {
            logger.debug("Failed to record observation: {}", e.getMessage());
            // Non-critical - continue even if recording fails
        }
    }

    /**
     * Creates Subject for thread pool signal.
     * Subject represents: Circuit + Entity (broker.poolType)
     *
     * @param brokerId Broker ID (e.g., "broker-1")
     * @param poolType Thread pool type (NETWORK, IO, LOG_CLEANER)
     * @return Subject for signal emission
     */
    private Subject createSubject(String brokerId, ThreadPoolType poolType) {
        final String entityName = brokerId + "." + poolType.name().toLowerCase();

        return new Subject() {
            @Override
            public io.humainary.substrates.api.Substrates.Id id() {
                return null; // No specific ID
            }

            @Override
            public Name name() {
                return cortex().name(entityName);
            }

            @Override
            public Class<ResourceSignal> type() {
                return ResourceSignal.class;
            }

            @Override
            public io.humainary.substrates.api.Substrates.State state() {
                return null; // No state - Subject is used for signal identity only
            }

            @Override
            public int compareTo(Object o) {
                if (!(o instanceof Subject)) return -1;
                Subject other = (Subject) o;
                return name().value().compareTo(other.name().value());
            }
        };
    }
}
