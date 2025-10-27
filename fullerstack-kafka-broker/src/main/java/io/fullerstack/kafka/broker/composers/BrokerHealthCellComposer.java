package io.fullerstack.kafka.broker.composers;

import io.fullerstack.serventis.config.HealthThresholds;
import io.fullerstack.serventis.signals.MonitorSignal;
import io.fullerstack.substrates.config.HierarchicalConfig;
import io.humainary.modules.serventis.monitors.api.Monitors;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.core.config.ClusterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * M18-compliant Cell Composer that transforms BrokerMetrics into MonitorSignal.
 * <p>
 * <b>CRITICAL M18 Pattern:</b> This composer implements the proper Substrates pattern
 * where Subjects come from Channel infrastructure, NOT manual construction.
 * <p>
 * <b>Architecture:</b>
 * <pre>
 * Cell<BrokerMetrics, MonitorSignal>
 *   → Composer creates Pipe<BrokerMetrics>
 *     → Pipe.emit(metrics) transforms to MonitorSignal
 *       → Uses channel.subject() for proper Subject hierarchy
 *         → Emits MonitorSignal to output Pipe<MonitorSignal>
 * </pre>
 * <p>
 * <b>Usage:</b>
 * <pre>{@code
 * Circuit circuit = Cortex.circuit(clusterName);
 * Cell<BrokerMetrics, MonitorSignal> healthCell = circuit.cell(
 *     new BrokerHealthCellComposer()
 * );
 *
 * // Create broker-specific cell
 * Cell<BrokerMetrics, MonitorSignal> broker1Cell = healthCell.get(
 *     Cortex.name("broker-1")
 * );
 *
 * // Emit metrics - Cell handles transformation with correct Subject
 * broker1Cell.emit(brokerMetrics);
 * }</pre>
 * <p>
 * <b>Subject Hierarchy:</b>
 * The Channel's Subject hierarchy ensures proper observability:
 * <pre>
 * Circuit → Cell → Channel → MonitorSignal.subject()
 *                    ↑
 *                    This Subject is used in the Signal
 * </pre>
 * <p>
 * <b>Health Assessment:</b>
 * - STABLE: heap < 75%, CPU < 70%, no offline partitions
 * - DEGRADED: heap 75-90%, CPU 70-85%, underReplicated > 0
 * - DOWN: heap > 90%, CPU > 85%, offlinePartitions > 0, activeControllers == 0
 * <p>
 * <b>Confidence Assessment:</b>
 * - CONFIRMED: metrics < 30s old
 * - MEASURED: metrics 30s-60s old
 * - TENTATIVE: metrics > 60s old
 *
 * @see MonitorSignal
 * @see BrokerMetrics
 */
public class BrokerHealthCellComposer implements Composer<Pipe<BrokerMetrics>, MonitorSignal> {
    private static final Logger logger = LoggerFactory.getLogger(BrokerHealthCellComposer.class);

    // Metric freshness threshold (not configurable - operational constant)
    private static final long METRIC_FRESHNESS_THRESHOLD_MS = 60_000; // 1 minute

    // Configurable health assessment thresholds
    private final HealthThresholds healthThresholds;

    /**
     * Creates a BrokerHealthCellComposer loading configuration from HierarchicalConfig.
     * <p>
     * Loads thresholds from config_broker-health.properties:
     * - health.thresholds.heap.stable (default: 0.75)
     * - health.thresholds.heap.degraded (default: 0.90)
     * - health.thresholds.cpu.stable (default: 0.70)
     * - health.thresholds.cpu.degraded (default: 0.85)
     * <p>
     * This is the recommended constructor for production use - it respects
     * externalized configuration via properties files.
     */
    public BrokerHealthCellComposer() {
        this(HealthThresholds.forCircuit("broker-health"));
    }

    /**
     * Creates a BrokerHealthCellComposer with specified HealthThresholds.
     * <p>
     * This constructor is useful when:
     * - You need programmatic threshold control
     * - You're testing with custom thresholds
     * - You want to use different thresholds than config files
     * <p>
     * Production code should typically use the no-arg constructor which
     * loads configuration from properties files.
     *
     * @param healthThresholds Health assessment thresholds configuration
     */
    public BrokerHealthCellComposer(HealthThresholds healthThresholds) {
        this.healthThresholds = healthThresholds;

        logger.info("BrokerHealthCellComposer initialized with thresholds - heap: {}/{}, cpu: {}/{}",
                healthThresholds.heapStable(), healthThresholds.heapDegraded(),
                healthThresholds.cpuStable(), healthThresholds.cpuDegraded());
    }

    /**
     * Creates a BrokerHealthCellComposer with custom thresholds.
     * <p>
     * <b>Note:</b> This constructor is primarily for testing. Production code should use
     * the no-arg constructor which loads configuration from properties files, or use
     * the {@link #BrokerHealthCellComposer(HealthThresholds)} constructor.
     * <p>
     * Allows tuning health assessment for different cluster profiles:
     * - High-throughput clusters may tolerate higher heap usage
     * - Mission-critical clusters may want lower thresholds
     * - Development clusters may relax CPU thresholds
     *
     * @param heapStableThreshold heap usage below this is STABLE (0.0-1.0)
     * @param heapDegradedThreshold heap usage above this is DOWN (0.0-1.0)
     * @param cpuStableThreshold CPU usage below this is STABLE (0.0-1.0)
     * @param cpuDegradedThreshold CPU usage above this is DOWN (0.0-1.0)
     * @throws IllegalArgumentException if thresholds are invalid
     * @deprecated Use {@link #BrokerHealthCellComposer(HealthThresholds)} instead
     */
    @Deprecated(since = "1.0.0", forRemoval = false)
    public BrokerHealthCellComposer(
            double heapStableThreshold,
            double heapDegradedThreshold,
            double cpuStableThreshold,
            double cpuDegradedThreshold
    ) {
        this(new HealthThresholds(heapStableThreshold, heapDegradedThreshold,
                cpuStableThreshold, cpuDegradedThreshold));

        logger.warn("BrokerHealthCellComposer created with explicit thresholds (deprecated) - prefer HealthThresholds constructor");
    }

    /**
     * Composes a Pipe<BrokerMetrics> that transforms metrics to MonitorSignal.
     * <p>
     * This is the M18 Composer pattern:
     * - Receives Channel<MonitorSignal> (OUTPUT channel)
     * - Returns Pipe<BrokerMetrics> (INPUT pipe)
     * - The returned Pipe transforms BrokerMetrics → MonitorSignal
     * - Uses channel.subject() for proper Subject hierarchy
     *
     * @param channel the output Channel for MonitorSignal emissions
     * @return Pipe that accepts BrokerMetrics and emits MonitorSignal
     */
    @Override
    public Pipe<BrokerMetrics> compose(Channel<MonitorSignal> channel) {
        // Get the output pipe for emitting MonitorSignal
        Pipe<MonitorSignal> outputPipe = channel.pipe();

        // Get the Channel's Subject - this is the key to M18 pattern!
        Subject<Channel<MonitorSignal>> channelSubject = channel.subject();

        logger.debug("Created BrokerHealthCellComposer for channel: {}", channelSubject.name());

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

                    // Build context with key metrics
                    Map<String, String> context = buildContext(metrics);

                    // Create MonitorSignal using Channel's Subject
                    // ✅ CORRECT M18 PATTERN - Subject from Channel infrastructure!
                    MonitorSignal signal = switch (condition) {
                        case STABLE -> MonitorSignal.stable(channelSubject, context);
                        case DEGRADED -> MonitorSignal.degraded(channelSubject, confidence, context);
                        case DOWN -> MonitorSignal.down(channelSubject, confidence, context);
                        default -> MonitorSignal.create(channelSubject, condition, confidence, context);
                    };

                    logger.debug("Emitting {} signal for broker {} (confidence: {})",
                            condition, metrics.brokerId(), confidence);

                    // Emit to output pipe
                    outputPipe.emit(signal);

                } catch (Exception e) {
                    logger.error("Error composing MonitorSignal from metrics for broker {}",
                            metrics.brokerId(), e);

                    // Emit error signal
                    MonitorSignal errorSignal = MonitorSignal.down(
                            channelSubject,
                            Monitors.Confidence.TENTATIVE,
                            Map.of("error", "Exception during composition: " + e.getMessage())
                    );
                    outputPipe.emit(errorSignal);
                }
            }
        };
    }

    /**
     * Assess broker condition based on metrics and thresholds.
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
        if (heapUsageRatio > healthThresholds.heapDegraded()) {
            logger.warn("Broker {} DOWN: heap usage {}%",
                    metrics.brokerId(), String.format("%.1f", heapUsageRatio * 100));
            return Monitors.Condition.DOWN;
        }

        if (metrics.cpuUsage() > healthThresholds.cpuDegraded()) {
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
        if (heapUsageRatio > healthThresholds.heapStable()) {
            logger.info("Broker {} DEGRADED: heap usage {}%",
                    metrics.brokerId(), String.format("%.1f", heapUsageRatio * 100));
            return Monitors.Condition.DEGRADED;
        }

        if (metrics.cpuUsage() > healthThresholds.cpuStable()) {
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

    /**
     * Build context map with key metrics for observability.
     * <p>
     * Includes:
     * - Memory metrics (heap used/max, usage %)
     * - CPU usage
     * - Request metrics (rate, bytes in/out)
     * - Controller and replication status
     * - Thread pool utilization
     * - Latency metrics
     *
     * @param metrics Kafka broker JMX metrics
     * @return context map with metric key-value pairs
     */
    private Map<String, String> buildContext(BrokerMetrics metrics) {
        Map<String, String> context = new HashMap<>();

        // Memory metrics
        double heapUsageRatio = (double) metrics.heapUsed() / metrics.heapMax();
        context.put("heap.used", String.valueOf(metrics.heapUsed()));
        context.put("heap.max", String.valueOf(metrics.heapMax()));
        context.put("heap.usage.percent", String.format("%.1f", heapUsageRatio * 100));

        // CPU metrics
        context.put("cpu.usage.percent", String.format("%.1f", metrics.cpuUsage() * 100));

        // Request metrics
        context.put("request.rate", String.valueOf(metrics.requestRate()));
        context.put("byte.in.rate", String.valueOf(metrics.byteInRate()));
        context.put("byte.out.rate", String.valueOf(metrics.byteOutRate()));

        // Controller and replication metrics
        context.put("active.controllers", String.valueOf(metrics.activeControllers()));
        context.put("under.replicated.partitions", String.valueOf(metrics.underReplicatedPartitions()));
        context.put("offline.partitions", String.valueOf(metrics.offlinePartitionsCount()));

        // Thread pool metrics
        context.put("network.processor.idle.percent", String.valueOf(metrics.networkProcessorAvgIdlePercent()));
        context.put("request.handler.idle.percent", String.valueOf(metrics.requestHandlerAvgIdlePercent()));

        // Latency metrics
        context.put("fetch.consumer.latency.ms", String.valueOf(metrics.fetchConsumerTotalTimeMs()));
        context.put("produce.latency.ms", String.valueOf(metrics.produceTotalTimeMs()));

        // Metadata
        context.put("broker.id", ClusterConfig.extractBrokerId(metrics.brokerId()));
        context.put("timestamp", String.valueOf(metrics.timestamp()));

        return context;
    }
}
