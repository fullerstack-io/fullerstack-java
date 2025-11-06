package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.PartitionMetrics;
import io.humainary.substrates.ext.serventis.Gauges;
import io.humainary.substrates.ext.serventis.Counters;
import io.humainary.substrates.ext.serventis.Monitors;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Emits Gauges/Counters/Monitors signals for partition state metrics using Serventis RC1 vocabulary.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This observer emits signals with multiple Serventis API vocabularies, NOT interpretations.
 *
 * <h3>Partition State Metrics (4 metrics):</h3>
 * <ul>
 *   <li><b>Partition Size</b> - Total bytes → Gauges (INCREMENT/DECREMENT for growth/shrinkage)</li>
 *   <li><b>Log End Offset</b> - Highest offset → Counters (INCREMENT as offset advances)</li>
 *   <li><b>ISR Count</b> - In-sync replicas → Gauges (UNDERFLOW for under-replication)</li>
 *   <li><b>Leader Epoch</b> - Leadership changes → Monitors (DIVERGING when leader changes)</li>
 * </ul>
 *
 * <h3>Signal Emission Logic:</h3>
 * <pre>
 * Partition Size (Gauges):
 *   INCREMENT:  Size increased since last poll
 *   DECREMENT:  Size decreased (log compaction/deletion)
 *
 * Log End Offset (Counters):
 *   INCREMENT:  Offset advanced (new records appended)
 *
 * ISR Count (Gauges):
 *   INCREMENT:  All replicas in-sync (healthy)
 *   UNDERFLOW:  ISR count < replication factor (under-replicated)
 *
 * Leader Epoch (Monitors):
 *   STABLE:     Leader unchanged
 *   DIVERGING:  Leader epoch increased (leadership change)
 * </pre>
 *
 * <p><b>Note:</b> This observer uses simple state tracking for signal emission. Contextual
 * assessment using trends and patterns will be added in Epic 2
 * via Observers (Layer 4 - Semiosphere).
 */
public class PartitionStateObserver {

    private static final Logger logger = LoggerFactory.getLogger(PartitionStateObserver.class);

    private final Name circuitName;
    private final Channel<Gauges.Sign> sizeGaugeChannel;
    private final Channel<Counters.Sign> offsetCounterChannel;
    private final Channel<Gauges.Sign> isrGaugeChannel;
    private final Channel<Monitors.Signal> epochMonitorChannel;

    private final Gauges.Gauge sizeGauge;
    private final Counters.Counter offsetCounter;
    private final Gauges.Gauge isrGauge;
    private final Monitors.Monitor epochMonitor;

    // Track previous state for delta calculations
    private final Map<String, PartitionMetrics> previousMetrics = new HashMap<>();

    /**
     * Creates a PartitionStateObserver.
     *
     * @param circuitName         Circuit name for logging
     * @param sizeGaugeChannel    Channel for partition size gauge signals
     * @param offsetCounterChannel Channel for log end offset counter signals
     * @param isrGaugeChannel     Channel for ISR count gauge signals
     * @param epochMonitorChannel Channel for leader epoch monitor signals
     * @throws NullPointerException if any parameter is null
     */
    public PartitionStateObserver(
        Name circuitName,
        Channel<Gauges.Sign> sizeGaugeChannel,
        Channel<Counters.Sign> offsetCounterChannel,
        Channel<Gauges.Sign> isrGaugeChannel,
        Channel<Monitors.Signal> epochMonitorChannel
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.sizeGaugeChannel = Objects.requireNonNull(sizeGaugeChannel, "sizeGaugeChannel cannot be null");
        this.offsetCounterChannel = Objects.requireNonNull(offsetCounterChannel, "offsetCounterChannel cannot be null");
        this.isrGaugeChannel = Objects.requireNonNull(isrGaugeChannel, "isrGaugeChannel cannot be null");
        this.epochMonitorChannel = Objects.requireNonNull(epochMonitorChannel, "epochMonitorChannel cannot be null");

        // Get instruments from RC1 API
        this.sizeGauge = Gauges.composer(sizeGaugeChannel);
        this.offsetCounter = Counters.composer(offsetCounterChannel);
        this.isrGauge = Gauges.composer(isrGaugeChannel);
        this.epochMonitor = Monitors.composer(epochMonitorChannel);
    }

    /**
     * Emits signals for partition state metrics.
     *
     * @param metrics Partition metrics from JMX/AdminClient collector
     * @throws NullPointerException if metrics is null
     */
    public void emit(PartitionMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            String partitionKey = metrics.partitionIdentifier();
            PartitionMetrics previous = previousMetrics.get(partitionKey);

            // 1. Partition Size - emit gauge increment/decrement based on size change
            if (previous != null) {
                long sizeDelta = metrics.sizeBytes() - previous.sizeBytes();
                if (sizeDelta > 0) {
                    sizeGauge.increment(); // Size increased
                } else if (sizeDelta < 0) {
                    sizeGauge.decrement(); // Size decreased (compaction/deletion)
                }
            }

            // 2. Log End Offset - emit counter increment if offset advanced
            if (previous != null && metrics.logEndOffset() > previous.logEndOffset()) {
                long offsetDelta = metrics.logEndOffset() - previous.logEndOffset();
                for (int i = 0; i < offsetDelta && i < 1000; i++) { // Cap at 1000 to avoid excessive signals
                    offsetCounter.increment();
                }
            }

            // 3. ISR Count - emit gauge based on replication health
            if (metrics.isUnderReplicated()) {
                // Under-replicated - emit UNDERFLOW
                isrGauge.underflow();
            } else {
                // Healthy replication - emit INCREMENT
                isrGauge.increment();
            }

            // 4. Leader Epoch - emit monitor signal for leadership changes
            if (previous != null && metrics.hasLeaderChanged(previous.leaderEpoch())) {
                // Leader changed - emit DIVERGING
                epochMonitor.diverging(Monitors.Confidence.CONFIRMED);
            } else {
                // Leader stable
                epochMonitor.stable(Monitors.Confidence.CONFIRMED);
            }

            // Update previous metrics for next poll
            previousMetrics.put(partitionKey, metrics);

            if (logger.isDebugEnabled()) {
                logger.debug("Emitted partition state signals for {}: " +
                    "size={} bytes, offset={}, isrCount={}/{}, epoch={}, underReplicated={}",
                    partitionKey,
                    metrics.sizeBytes(),
                    metrics.logEndOffset(),
                    metrics.isrCount(),
                    metrics.replicationFactor(),
                    metrics.leaderEpoch(),
                    metrics.isUnderReplicated());
            }
        } catch (Exception e) {
            logger.error("Failed to emit partition state signals for {}-{}: {}",
                metrics.topicName(),
                metrics.partitionId(),
                e.getMessage(),
                e);
            // Don't propagate - monitoring failures shouldn't break the system
        }
    }

    /**
     * Clears previous metrics state.
     * <p>
     * Useful for testing or when resetting monitoring state.
     */
    public void clearState() {
        previousMetrics.clear();
    }
}
