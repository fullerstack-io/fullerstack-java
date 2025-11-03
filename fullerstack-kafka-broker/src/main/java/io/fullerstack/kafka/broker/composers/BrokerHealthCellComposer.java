package io.fullerstack.kafka.broker.composers;

import io.humainary.substrates.ext.serventis.Monitors;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.core.config.ClusterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RC1 Cell Composer that transforms BrokerMetrics into Monitors.Status signals.
 * <p>
 * Uses Humainary Serventis RC1 API directly - no wrapper classes.
 * <p>
 * <b>Health Assessment (Hardcoded Thresholds):</b>
 * - STABLE: heap < 75%, CPU < 70%, no offline partitions
 * - DEGRADED: heap 75-90%, CPU 70-85%, underReplicated > 0
 * - DOWN: heap > 90%, CPU > 85%, offlinePartitions > 0
 * <p>
 * <b>Confidence Assessment:</b>
 * - CONFIRMED: metrics < 30s old
 * - MEASURED: metrics 30s-60s old
 * - TENTATIVE: metrics > 60s old
 *
 * @see Monitors
 * @see BrokerMetrics
 */
public class BrokerHealthCellComposer implements Composer<Monitors.Status, Pipe<BrokerMetrics>> {
    private static final Logger logger = LoggerFactory.getLogger(BrokerHealthCellComposer.class);

    // Metric freshness threshold (not configurable - operational constant)
    private static final long METRIC_FRESHNESS_THRESHOLD_MS = 60_000; // 1 minute

    // Hardcoded health assessment thresholds (simplified per RC1 philosophy)
    private static final double HEAP_STABLE_THRESHOLD = 0.75;
    private static final double HEAP_DEGRADED_THRESHOLD = 0.90;
    private static final double CPU_STABLE_THRESHOLD = 0.70;
    private static final double CPU_DEGRADED_THRESHOLD = 0.85;

    public BrokerHealthCellComposer() {
        logger.info("BrokerHealthCellComposer initialized with hardcoded thresholds - heap: {}/{}, cpu: {}/{}",
                HEAP_STABLE_THRESHOLD, HEAP_DEGRADED_THRESHOLD,
                CPU_STABLE_THRESHOLD, CPU_DEGRADED_THRESHOLD);
    }

    /**
     * Creates a Pipe<BrokerMetrics> that transforms metrics to Monitors.Status signals.
     * <p>
     * Uses RC1 pattern: Get Monitor from channel, emit semantic signals only.
     *
     * @param channel the output Channel for Monitors.Status emissions
     * @return Pipe that accepts BrokerMetrics and emits Monitors.Status
     */
    @Override
    public Pipe<BrokerMetrics> compose(Channel<Monitors.Status> channel) {
        // Get Monitor from RC1 API
        Monitors.Monitor monitor = Monitors.composer(channel);

        logger.debug("Created BrokerHealthCellComposer for channel: {}", channel.subject().name());

        // Return a Pipe<BrokerMetrics> that transforms metrics to signals
        return new Pipe<BrokerMetrics>() {
            @Override
            public void emit(BrokerMetrics metrics) {
                if (metrics == null) {
                    logger.warn("Received null metrics, skipping emission");
                    return;
                }

                try {
                    // Assess broker health condition
                    Monitors.Condition condition = assessCondition(metrics);

                    // Assess confidence based on metric freshness
                    Monitors.Confidence confidence = assessConfidence(metrics);

                    logger.debug("Emitting {} signal for broker {} (confidence: {})",
                            condition, metrics.brokerId(), confidence);

                    // Emit via Monitor (RC1 API - semantic signal only, no metadata!)
                    monitor.status(condition, confidence);

                } catch (Exception e) {
                    logger.error("Error composing Monitors.Status from metrics for broker {}",
                            metrics.brokerId(), e);

                    // Emit error signal
                    monitor.status(Monitors.Condition.DOWN, Monitors.Confidence.TENTATIVE);
                }
            }

            @Override
            public void flush() {
                // No-op: Monitor instrument handles its own flushing
            }
        };
    }

    /**
     * Assess broker condition based on metrics and hardcoded thresholds.
     * <p>
     * Priority order (most critical first):
     * 1. DOWN - Critical failures (heap > 90%, CPU > 85%, offline partitions, no controller)
     * 2. DEGRADED - Performance issues (heap 75-90%, CPU 70-85%, under-replicated)
     * 3. STABLE - Normal operation (all checks passed)
     *
     * @param metrics Kafka broker JMX metrics
     * @return assessed Monitors.Condition
     */
    private Monitors.Condition assessCondition(BrokerMetrics metrics) {
        double heapUsageRatio = (double) metrics.heapUsed() / metrics.heapMax();

        // DOWN conditions (most critical)
        if (heapUsageRatio > HEAP_DEGRADED_THRESHOLD) {
            logger.warn("Broker {} DOWN: heap usage {}%",
                    metrics.brokerId(), String.format("%.1f", heapUsageRatio * 100));
            return Monitors.Condition.DOWN;
        }

        if (metrics.cpuUsage() > CPU_DEGRADED_THRESHOLD) {
            logger.warn("Broker {} DOWN: CPU usage {}%",
                    metrics.brokerId(), String.format("%.1f", metrics.cpuUsage() * 100));
            return Monitors.Condition.DOWN;
        }

        if (metrics.offlinePartitionsCount() > 0) {
            logger.warn("Broker {} DOWN: {} offline partitions",
                    metrics.brokerId(), metrics.offlinePartitionsCount());
            return Monitors.Condition.DOWN;
        }

        if (metrics.activeControllers() == 0) {
            logger.warn("Broker {} DOWN: no active controller", metrics.brokerId());
            return Monitors.Condition.DOWN;
        }

        // DEGRADED conditions
        if (heapUsageRatio > HEAP_STABLE_THRESHOLD) {
            logger.info("Broker {} DEGRADED: heap usage {}%",
                    metrics.brokerId(), String.format("%.1f", heapUsageRatio * 100));
            return Monitors.Condition.DEGRADED;
        }

        if (metrics.cpuUsage() > CPU_STABLE_THRESHOLD) {
            logger.info("Broker {} DEGRADED: CPU usage {}%",
                    metrics.brokerId(), String.format("%.1f", metrics.cpuUsage() * 100));
            return Monitors.Condition.DEGRADED;
        }

        if (metrics.underReplicatedPartitions() > 0) {
            logger.info("Broker {} DEGRADED: {} under-replicated partitions",
                    metrics.brokerId(), metrics.underReplicatedPartitions());
            return Monitors.Condition.DEGRADED;
        }

        // STABLE (all checks passed)
        logger.debug("Broker {} STABLE", metrics.brokerId());
        return Monitors.Condition.STABLE;
    }

    /**
     * Assess confidence based on metric freshness.
     * <p>
     * Uses Monitors.Confidence values:
     * - CONFIRMED: High confidence (metrics < 30s old)
     * - MEASURED: Medium confidence (metrics 30s-60s old)
     * - TENTATIVE: Low confidence (metrics > 60s old)
     *
     * @param metrics Kafka broker JMX metrics
     * @return assessed Monitors.Confidence
     */
    private Monitors.Confidence assessConfidence(BrokerMetrics metrics) {
        long age = System.currentTimeMillis() - metrics.timestamp();

        // TENTATIVE if metrics are stale (> 60s)
        if (age > METRIC_FRESHNESS_THRESHOLD_MS) {
            logger.debug("Broker {} metrics are {}ms old, confidence TENTATIVE",
                    metrics.brokerId(), age);
            return Monitors.Confidence.TENTATIVE;
        }

        // MEASURED if metrics are somewhat fresh (30-60s)
        if (age > METRIC_FRESHNESS_THRESHOLD_MS / 2) {
            return Monitors.Confidence.MEASURED;
        }

        // CONFIRMED for fresh metrics (< 30s)
        return Monitors.Confidence.CONFIRMED;
    }
}
