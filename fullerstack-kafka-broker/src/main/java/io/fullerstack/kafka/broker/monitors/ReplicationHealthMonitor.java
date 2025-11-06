package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.ReplicationHealthMetrics;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;
import static io.humainary.substrates.ext.serventis.ext.Monitors.Dimension.CONFIRMED;

/**
 * Emits Monitors.Signal for replication health metrics using Serventis RC6 vocabulary.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This monitor emits signals with Monitors API vocabulary (STABLE/DEGRADED/DEFECTIVE/DOWN), NOT interpretations.
 *
 * <h3>RC6 Monitors API - Replication Health Assessment</h3>
 * Assesses cluster-wide replication health based on partition state and controller status:
 * <ul>
 *   <li><b>STABLE</b> - All partitions fully replicated, controller active (healthy)</li>
 *   <li><b>DEGRADED</b> - Some under-replicated partitions (redundancy reduced but data available)</li>
 *   <li><b>DEFECTIVE</b> - Partitions offline (data unavailable)</li>
 *   <li><b>DOWN</b> - No active controller (cluster cannot perform leader elections)</li>
 * </ul>
 *
 * <h3>Signal Emission Logic</h3>
 * <pre>
 * Partition Health:
 *   - monitor.status(STABLE, CONFIRMED) - All partitions fully replicated
 *   - monitor.status(DEGRADED, CONFIRMED) - Under-replicated > 0 but no offline
 *   - monitor.status(DEFECTIVE, CONFIRMED) - Offline partitions > 0
 *
 * Controller Health:
 *   - monitor.status(DOWN, CONFIRMED) - Active controller count = 0
 *   - monitor.status(STABLE, CONFIRMED) - Active controller count = 1
 * </pre>
 *
 * <p><b>Monitor Naming Convention</b>:
 * <ul>
 *   <li>Partition health: {@code cluster-{id}.partition-health}</li>
 *   <li>Controller health: {@code cluster-{id}.controller-health}</li>
 * </ul>
 *
 * <p><b>Confidence Level</b>: All signals use CONFIRMED confidence since they are based on
 * direct JMX metrics (not derived or inferred).
 *
 * <p><b>Note:</b> Simple threshold-based signal emission. Contextual assessment using baselines,
 * trends, and recommendations will be added in Epic 2 via Observers (Layer 4 - Semiosphere).
 *
 * @see ReplicationHealthMetrics
 * @see Monitors
 */
public class ReplicationHealthMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationHealthMonitor.class);

    private final Name circuitName;
    private final Channel<Monitors.Signal> channel;
    private final Monitor partitionHealthMonitor;
    private final Monitor controllerHealthMonitor;

    /**
     * Creates a ReplicationHealthMonitor.
     *
     * @param circuitName Circuit name for logging
     * @param conduit     Conduit for Monitors
     * @param clusterId   Cluster identifier (e.g., "cluster-1", "prod-kafka")
     * @throws NullPointerException if any parameter is null
     */
    public ReplicationHealthMonitor(
        Name circuitName,
        Conduit<Monitor, Monitors.Signal> conduit,
        String clusterId
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        Objects.requireNonNull(conduit, "conduit cannot be null");
        Objects.requireNonNull(clusterId, "clusterId cannot be null");

        // Get monitors from Conduit (creates via composer)
        this.partitionHealthMonitor = conduit.get(cortex().name(clusterId + ".partition-health"));
        this.controllerHealthMonitor = conduit.get(cortex().name(clusterId + ".controller-health"));
        this.channel = null; // Not needed
    }

    /**
     * Emits Monitors.Signal for replication health metrics.
     *
     * @param metrics Replication health metrics from JMX collector
     * @throws NullPointerException if metrics is null
     */
    public void emit(ReplicationHealthMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            // Emit partition health assessment
            emitPartitionHealth(metrics);

            // Emit controller health assessment
            emitControllerHealth(metrics);

        } catch (Exception e) {
            logger.error("Failed to emit Monitors.Signal for {}: {}",
                metrics.clusterId(),
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }

    /**
     * Emits partition health assessment.
     * <p>
     * Assessment levels:
     * <ul>
     *   <li><b>STABLE</b> - All partitions fully replicated (no under-replicated, no offline)</li>
     *   <li><b>DEGRADED</b> - Under-replicated partitions exist but no offline partitions</li>
     *   <li><b>DEFECTIVE</b> - Offline partitions exist (critical)</li>
     * </ul>
     *
     * @param metrics Replication health metrics
     */
    private void emitPartitionHealth(ReplicationHealthMetrics metrics) {
        if (metrics.hasOfflinePartitions()) {
            // DEFECTIVE: Partitions offline = data unavailable (critical)
            partitionHealthMonitor.defective(CONFIRMED);

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted DEFECTIVE signal for partition health on {} ({} offline partitions)",
                    metrics.clusterId(), metrics.offlinePartitions());
            }

        } else if (metrics.hasUnderReplicatedPartitions()) {
            // DEGRADED: Under-replicated but no offline = reduced redundancy (warning)
            partitionHealthMonitor.degraded(CONFIRMED);

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted DEGRADED signal for partition health on {} ({} under-replicated)",
                    metrics.clusterId(), metrics.underReplicatedPartitions());
            }

        } else {
            // STABLE: All partitions fully replicated (healthy)
            partitionHealthMonitor.stable(CONFIRMED);

            if (logger.isTraceEnabled()) {
                logger.trace("Emitted STABLE signal for partition health on {}",
                    metrics.clusterId());
            }
        }
    }

    /**
     * Emits controller health assessment.
     * <p>
     * Assessment levels:
     * <ul>
     *   <li><b>STABLE</b> - Active controller count = 1 (healthy)</li>
     *   <li><b>DOWN</b> - Active controller count = 0 (cluster cannot perform leader elections)</li>
     * </ul>
     * <p>
     * <b>Note</b>: Active controller count > 1 is theoretically impossible (split-brain protection),
     * but if it occurs, it will be treated as STABLE since a controller is available.
     *
     * @param metrics Replication health metrics
     */
    private void emitControllerHealth(ReplicationHealthMetrics metrics) {
        if (!metrics.hasActiveController()) {
            // DOWN: No active controller = cluster cannot perform leader elections (critical)
            controllerHealthMonitor.down(CONFIRMED);

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted DOWN signal for controller health on {} (no active controller)",
                    metrics.clusterId());
            }

        } else {
            // STABLE: Active controller present (healthy)
            controllerHealthMonitor.stable(CONFIRMED);

            if (logger.isTraceEnabled()) {
                logger.trace("Emitted STABLE signal for controller health on {}",
                    metrics.clusterId());
            }
        }
    }
}
