package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import io.humainary.substrates.ext.serventis.Resources;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Emits Resources.Signal for thread pool metrics using Serventis RC1 vocabulary.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This monitor emits signals with Resources API vocabulary (GRANT/DENY), NOT interpretations.
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
 */
public class ThreadPoolResourceMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolResourceMonitor.class);

    // Simple fixed thresholds (no baseline - that's Layer 4)
    private static final double IDLE_HEALTHY_THRESHOLD = 0.30;    // 30% idle = healthy
    private static final double IDLE_EXHAUSTED_THRESHOLD = 0.10;  // 10% idle = exhausted

    private final Name circuitName;
    private final Channel<Resources.Signal> channel;
    private final Resources.Resource resource;

    /**
     * Creates a ThreadPoolResourceMonitor.
     *
     * @param circuitName Circuit name for logging
     * @param channel  Channel to emit Resources.Signal
     * @throws NullPointerException if any parameter is null
     */
    public ThreadPoolResourceMonitor(
        Name circuitName,
        Channel<Resources.Signal> channel
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.channel = Objects.requireNonNull(channel, "channel cannot be null");

        // Get Resource from RC1 API
        this.resource = Resources.composer(channel);
    }

    /**
     * Emits Resources.Signal for thread pool metrics.
     *
     * @param metrics Thread pool metrics from JMX collector
     * @throws NullPointerException if metrics is null
     */
    public void emit(ThreadPoolMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            double idlePercent = metrics.avgIdlePercent();

            // Simple threshold assessment (no baseline)
            if (idlePercent < IDLE_EXHAUSTED_THRESHOLD) {
                // EXHAUSTED: <10% idle → DENY (no capacity)
                resource.deny(0);
            } else {
                // ≥10% idle → GRANT (capacity available)
                resource.grant(metrics.idleThreads());
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted Resources.Signal for {}.{}: idlePercent={}%",
                    metrics.brokerId(),
                    metrics.poolType().name(),
                    (int)(idlePercent * 100));
            }
        } catch (Exception e) {
            logger.error("Failed to emit Resources.Signal for {}.{}: {}",
                metrics.brokerId(),
                metrics.poolType().name(),
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }
}
