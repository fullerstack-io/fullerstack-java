package io.fullerstack.kafka.broker.composers;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.serventis.signals.ResourceSignal;
import io.humainary.modules.serventis.resources.api.Resources;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Composer;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.api.Substrates.Subject;

import java.util.HashMap;
import java.util.Map;

/**
 * Composer that transforms {@link ThreadPoolMetrics} into {@link ResourceSignal}.
 *
 * <p>Maps thread pool states to ResourceSignal semantics:
 * <ul>
 *   <li><b>Healthy (≥30% idle)</b> → GRANT signal (resources available)</li>
 *   <li><b>Degraded (10-30% idle)</b> → GRANT signal with degraded context</li>
 *   <li><b>Exhausted (<10% idle)</b> → DENY signal (resource saturation)</li>
 * </ul>
 *
 * <p><b>Semantic Interpretation:</b>
 * <ul>
 *   <li>{@code GRANT} - Thread pool has capacity (INFO severity)</li>
 *   <li>{@code DENY} - Thread pool at or near capacity, new requests may be rejected (ERROR severity)</li>
 * </ul>
 *
 * <p>The payload metadata includes:
 * <ul>
 *   <li>{@code state} - "AVAILABLE", "DEGRADED", or "EXHAUSTED"</li>
 *   <li>{@code avgIdlePercent} - Average idle percentage (0.0-1.0)</li>
 *   <li>{@code utilizationPercent} - Active thread utilization (0.0-1.0)</li>
 *   <li>{@code totalThreads}, {@code activeThreads}, {@code idleThreads}</li>
 * </ul>
 *
 * <h3>Example Usage in Cell Hierarchy</h3>
 * <pre>{@code
 * ThreadPoolResourceComposer composer = new ThreadPoolResourceComposer();
 * Cell<ThreadPoolMetrics, ResourceSignal> threadPoolCell = circuit.cell(composer);
 *
 * // Emit metrics - transforms to ResourceSignal
 * ThreadPoolMetrics metrics = collector.collect("broker-1");
 * threadPoolCell.emit(metrics);
 *
 * // Subscribe to signals
 * threadPoolCell.source().subscribe(signal -> {
 *     if (signal.requiresAttention()) {
 *         logger.error("Thread pool exhaustion: {}", signal.interpret());
 *         // "Resource DENY: Resource request denied (no capacity) - 0 units"
 *     }
 * });
 * }</pre>
 *
 * <p><b>Implementation Note:</b> Units in ResourceSignal represent "available capacity":
 * <ul>
 *   <li>GRANT signals: units = idle threads (positive capacity)</li>
 *   <li>DENY signals: units = 0 (no capacity available)</li>
 * </ul>
 *
 * @see ThreadPoolMetrics
 * @see ResourceSignal
 */
public class ThreadPoolResourceComposer
    implements Composer<Pipe<ThreadPoolMetrics>, ResourceSignal> {

    /**
     * Composes a {@link Pipe} that transforms {@link ThreadPoolMetrics} to {@link ResourceSignal}.
     *
     * @param channel output channel for ResourceSignal emissions
     * @return input pipe for ThreadPoolMetrics
     */
    @Override
    public Pipe<ThreadPoolMetrics> compose(Channel<ResourceSignal> channel) {
        Subject channelSubject = channel.subject();
        Pipe<ResourceSignal> outputPipe = channel.pipe();

        return metrics -> {
            ResourceSignal signal = createSignal(channelSubject, metrics);
            outputPipe.emit(signal);
        };
    }

    /**
     * Creates a ResourceSignal from thread pool metrics.
     * <p>
     * Mapping logic:
     * <ul>
     *   <li>Exhausted (<10% idle) → DENY (no capacity)</li>
     *   <li>Degraded or Healthy (≥10% idle) → GRANT (capacity available)</li>
     * </ul>
     *
     * @param subject channel subject (from Substrates infrastructure)
     * @param metrics thread pool metrics snapshot
     * @return ResourceSignal with appropriate sign and context
     */
    private ResourceSignal createSignal(Subject subject, ThreadPoolMetrics metrics) {
        String state = determineState(metrics);
        Resources.Sign sign = determineSign(metrics);
        long units = determineUnits(metrics, sign);
        Map<String, String> context = buildContext(metrics, state);

        return ResourceSignal.create(subject, sign, units, context);
    }

    /**
     * Determines thread pool state based on idle percentage thresholds.
     *
     * @param metrics thread pool metrics
     * @return "AVAILABLE" (≥30%), "DEGRADED" (10-30%), or "EXHAUSTED" (<10%)
     */
    private String determineState(ThreadPoolMetrics metrics) {
        if (metrics.isHealthy()) {
            return "AVAILABLE";
        } else if (metrics.isDegraded() && !metrics.isExhausted()) {
            return "DEGRADED";
        } else {
            return "EXHAUSTED";
        }
    }

    /**
     * Determines ResourceSignal sign based on thread pool state.
     * <p>
     * Exhausted pools emit DENY (resource saturation).
     * Degraded or healthy pools emit GRANT (capacity available).
     *
     * @param metrics thread pool metrics
     * @return DENY if exhausted, GRANT otherwise
     */
    private Resources.Sign determineSign(ThreadPoolMetrics metrics) {
        return metrics.isExhausted() ? Resources.Sign.DENY : Resources.Sign.GRANT;
    }

    /**
     * Determines resource units for the signal.
     * <p>
     * Units represent available capacity:
     * <ul>
     *   <li>GRANT: idle threads (positive capacity)</li>
     *   <li>DENY: 0 (no capacity)</li>
     * </ul>
     *
     * @param metrics thread pool metrics
     * @param sign    resource sign
     * @return idle thread count for GRANT, 0 for DENY
     */
    private long determineUnits(ThreadPoolMetrics metrics, Resources.Sign sign) {
        return sign == Resources.Sign.GRANT ? metrics.idleThreads() : 0;
    }

    /**
     * Builds context metadata for the ResourceSignal.
     * <p>
     * Includes complete thread pool state for downstream interpretation:
     * state, brokerId, poolType, thread counts, utilization metrics.
     *
     * @param metrics thread pool metrics
     * @param state   thread pool state (AVAILABLE/DEGRADED/EXHAUSTED)
     * @return immutable context map
     */
    private Map<String, String> buildContext(ThreadPoolMetrics metrics, String state) {
        Map<String, String> context = new HashMap<>();

        // State classification
        context.put("state", state);

        // Identity
        context.put("brokerId", metrics.brokerId());
        context.put("poolType", metrics.poolType().displayName());

        // Thread counts
        context.put("totalThreads", String.valueOf(metrics.totalThreads()));
        context.put("activeThreads", String.valueOf(metrics.activeThreads()));
        context.put("idleThreads", String.valueOf(metrics.idleThreads()));

        // Utilization metrics
        context.put("avgIdlePercent", String.format("%.2f", metrics.avgIdlePercent()));
        context.put("utilizationPercent", String.format("%.2f", metrics.utilizationPercent()));

        // Optional fields
        if (metrics.queueSize() > 0) {
            context.put("queueSize", String.valueOf(metrics.queueSize()));
        }
        if (metrics.rejectionCount() > 0) {
            context.put("rejectionCount", String.valueOf(metrics.rejectionCount()));
        }

        return Map.copyOf(context);
    }
}
