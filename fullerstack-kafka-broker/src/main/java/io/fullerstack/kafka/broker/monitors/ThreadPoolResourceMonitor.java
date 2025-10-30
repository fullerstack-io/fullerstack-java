package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import io.fullerstack.serventis.signals.ResourceSignal;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.api.Substrates.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Emits ResourceSignal for thread pool metrics using Serventis vocabulary.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This monitor emits signals with Resources API vocabulary (GRANT/DENY), NOT interpretations.
 * Interpretation of signals (contextual assessment, recommendations) belongs in Layer 4
 * (Semiosphere Observers) - see Epic 2.
 *
 * <h3>Assessment (Simple Fixed Thresholds):</h3>
 * <pre>
 * GRANT (healthy): ≥30% idle - threads available
 * GRANT (degraded): 10-30% idle - threads available but under pressure
 * DENY (exhausted): <10% idle - no capacity available
 * </pre>
 *
 * <p><b>Note:</b> These are simple fixed thresholds for signal emission. Contextual
 * assessment using baselines, trends, and recommendations will be added in Epic 2
 * via Observers (Layer 4 - Semiosphere).
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * // Runtime creates Cell with Composer.pipe() (no transformation)
 * Circuit circuit = Cortex.circuit(Cortex.name("kafka.broker.resources"));
 * Cell<ResourceSignal, ResourceSignal> cell = circuit.cell(
 *     Cortex.name("thread-pools"),
 *     Composer.pipe()
 * );
 *
 * // Monitor emits signals with Serventis vocabulary
 * ThreadPoolResourceMonitor monitor = new ThreadPoolResourceMonitor(
 *     Cortex.name("kafka.broker.resources"),
 *     cell  // Cell IS-A Pipe<ResourceSignal>
 * );
 *
 * // Collect metrics and emit signal
 * ThreadPoolMetrics metrics = collector.collect("broker-1");
 * monitor.emit(metrics);  // ← Emits ResourceSignal (GRANT or DENY)
 * }</pre>
 *
 * <h3>Signal Payload (Raw Metrics Only):</h3>
 * <ul>
 *   <li><b>brokerId</b>: Broker identifier</li>
 *   <li><b>poolType</b>: Thread pool type (Network, I/O, Log Cleaner)</li>
 *   <li><b>totalThreads</b>: Pool capacity</li>
 *   <li><b>activeThreads</b>: Currently executing</li>
 *   <li><b>idleThreads</b>: Available for work</li>
 *   <li><b>avgIdlePercent</b>: Idle percentage (0.0-1.0)</li>
 *   <li><b>utilizationPercent</b>: Active percentage (0.0-1.0)</li>
 * </ul>
 *
 * <p>NO interpretation, NO recommendations in signal payload - that's Layer 4 (Epic 2).
 *
 * @author Fullerstack
 * @see ThreadPoolMetrics
 * @see ResourceSignal
 * @see <a href="../../../../../../../docs/architecture/ADR-002-LAYER-SEPARATION-SENSORS-VS-INTERPRETATION.md">ADR-002</a>
 */
public class ThreadPoolResourceMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolResourceMonitor.class);

    // Simple fixed thresholds (no baseline - that's Layer 4)
    private static final double IDLE_HEALTHY_THRESHOLD = 0.30;    // 30% idle = healthy
    private static final double IDLE_EXHAUSTED_THRESHOLD = 0.10;  // 10% idle = exhausted

    private final Name circuitName;
    private final Pipe<ResourceSignal> signalPipe;

    /**
     * Creates a ThreadPoolResourceMonitor.
     *
     * <p><b>Note:</b> BaselineService removed in ADR-002. Contextual assessment
     * (baselines, trends, interpretation) will be added in Epic 2 via Observers.
     *
     * @param circuitName Circuit name for Subject creation
     * @param signalPipe  Pipe to emit ResourceSignals (typically a Cell)
     * @throws NullPointerException if any parameter is null
     */
    public ThreadPoolResourceMonitor(
        Name circuitName,
        Pipe<ResourceSignal> signalPipe
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.signalPipe = Objects.requireNonNull(signalPipe, "signalPipe cannot be null");
    }

    /**
     * Emits ResourceSignal for thread pool metrics.
     *
     * <p><b>What this does</b> (Layer 2 - Serventis):
     * <ol>
     *   <li>Assess resource availability using simple thresholds</li>
     *   <li>Build payload with raw metrics only</li>
     *   <li>Emit ResourceSignal (GRANT or DENY)</li>
     * </ol>
     *
     * <p><b>What this does NOT do</b> (deferred to Layer 4 - Semiosphere):
     * <ul>
     *   <li>❌ Contextual baseline comparison (Epic 2 Observers)</li>
     *   <li>❌ Trend analysis ("degrading rapidly") (Epic 2 Observers)</li>
     *   <li>❌ Interpretation text ("Thread pool under pressure") (Epic 2 Observers)</li>
     *   <li>❌ Recommendations ("Increase num.io.threads") (Epic 3 SituationAssessors)</li>
     * </ul>
     *
     * @param metrics Thread pool metrics from JMX collector
     * @throws NullPointerException if metrics is null
     */
    public void emit(ThreadPoolMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            Subject subject = createSubject(metrics.brokerId(), metrics.poolType());

            // Build payload with raw metrics ONLY
            Map<String, String> payload = buildPayload(metrics);

            // Simple threshold assessment (no baseline)
            ResourceSignal signal = assessAvailability(subject, metrics, payload);

            // Emit signal
            signalPipe.emit(signal);

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted ResourceSignal for {}.{}: sign={}, idlePercent={}%",
                    metrics.brokerId(),
                    metrics.poolType().name(),
                    signal.sign(),
                    (int)(metrics.avgIdlePercent() * 100));
            }
        } catch (Exception e) {
            logger.error("Failed to emit ResourceSignal for {}.{}: {}",
                metrics.brokerId(),
                metrics.poolType().name(),
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }

    /**
     * Assesses resource availability using simple fixed thresholds.
     *
     * <p><b>Layer 2 Assessment</b> (simple, no context):
     * <ul>
     *   <li>≥30% idle → GRANT (healthy)</li>
     *   <li>10-30% idle → GRANT (degraded)</li>
     *   <li><10% idle → DENY (exhausted)</li>
     * </ul>
     *
     * <p><b>Note:</b> Contextual assessment (comparing to baseline, detecting trends)
     * will be added in Epic 2 via Observers.
     */
    private ResourceSignal assessAvailability(
        Subject subject,
        ThreadPoolMetrics metrics,
        Map<String, String> payload
    ) {
        double idlePercent = metrics.avgIdlePercent();

        // EXHAUSTED: <10% idle → DENY (no capacity)
        if (idlePercent < IDLE_EXHAUSTED_THRESHOLD) {
            return ResourceSignal.deny(subject, 0, payload);
        }

        // DEGRADED or HEALTHY: ≥10% idle → GRANT (capacity available)
        // Observer in Epic 2 will distinguish DEGRADED vs HEALTHY using baselines
        return ResourceSignal.grant(subject, metrics.idleThreads(), payload);
    }

    /**
     * Builds payload with raw metrics only.
     *
     * <p><b>What's included</b>:
     * <ul>
     *   <li>✅ Raw metrics (threads, percentages)</li>
     *   <li>✅ Entity identifiers (brokerId, poolType)</li>
     * </ul>
     *
     * <p><b>What's NOT included</b> (removed per ADR-002):
     * <ul>
     *   <li>❌ expectedIdlePercent (baseline - Layer 4)</li>
     *   <li>❌ assessment ("DEGRADED") (interpretation - Layer 4)</li>
     *   <li>❌ interpretation text (Layer 4)</li>
     *   <li>❌ recommendation text (Layer 4)</li>
     *   <li>❌ trend ("degrading rapidly") (Layer 4)</li>
     * </ul>
     */
    private Map<String, String> buildPayload(ThreadPoolMetrics metrics) {
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
