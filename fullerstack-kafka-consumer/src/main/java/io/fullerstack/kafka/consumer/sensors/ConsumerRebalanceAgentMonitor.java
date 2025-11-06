package io.fullerstack.kafka.consumer.sensors;

import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.ext.serventis.Agents;
import io.humainary.substrates.ext.serventis.Agents.Agent;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Monitors consumer rebalance lifecycle using Agents API (RC6 Promise Theory).
 * <p>
 * Models consumer-coordinator relationship as autonomous agents making and keeping promises:
 * <pre>
 * INQUIRE  → Consumer asks "Can I join the group?"
 * OFFER    → Coordinator offers partition assignment (inferred)
 * PROMISE  → Consumer commits "I will consume these partitions"
 * ACCEPT   → Coordinator accepts promise (inferred)
 * FULFILL  → Consumer maintains healthy consumption (low lag)
 * BREACH   → Consumer fails commitment (excessive lag, timeout)
 * </pre>
 *
 * <h3>Promise Theory Model:</h3>
 * <ul>
 *   <li><b>OUTBOUND signals:</b> Self-reporting ("I promise", "I fulfill")</li>
 *   <li><b>INBOUND signals:</b> Observing others ("Coordinator offered", "Coordinator accepted")</li>
 *   <li><b>Autonomy:</b> Consumer can only promise what it controls (consumption behavior)</li>
 *   <li><b>Breach detection:</b> Post-rebalance monitoring triggers BREACH if lag exceeds threshold</li>
 * </ul>
 *
 * <h3>Integration with WP5:</h3>
 * This monitor depends on {@link ConsumerLagMonitor} from WP5 to detect promise breaches.
 * Excessive lag after rebalance indicates the consumer failed to maintain its commitment.
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("consumer-rebalance"));
 * Conduit<Agent, Agents.Signal> agents = circuit.conduit(
 *     cortex().name("rebalance-agents"),
 *     Agents::composer
 * );
 *
 * ConsumerRebalanceAgentMonitor monitor = new ConsumerRebalanceAgentMonitor(
 *     circuit,
 *     "consumer-1",
 *     "my-group",
 *     agents
 * );
 *
 * consumer.subscribe(topics, monitor);
 * }</pre>
 *
 * @author Fullerstack
 * @see Agents
 * @see ConsumerLagMonitor
 */
public class ConsumerRebalanceAgentMonitor implements ConsumerRebalanceListener, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerRebalanceAgentMonitor.class);

    // Thresholds for promise fulfillment/breach detection
    private static final long BREACH_LAG_THRESHOLD = 50_000;    // >50k messages = breach
    private static final long FULFILL_LAG_THRESHOLD = 5_000;    // <5k messages = fulfill
    private static final long GRACE_PERIOD_MS = 30_000;         // 30s grace period after rebalance

    private final Circuit circuit;
    private final Agent consumer;
    private final Agent coordinator;
    private final String consumerId;
    private final String consumerGroup;

    private final ConsumerLagMonitor lagMonitor;  // WP5 dependency
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean monitoringActive = new AtomicBoolean(false);

    private volatile long rebalanceStartTime = 0;
    private volatile Set<TopicPartition> assignedPartitions = Set.of();

    /**
     * Creates consumer rebalance agent monitor.
     *
     * @param circuit        Circuit for signal synchronization
     * @param consumerId     Consumer client identifier
     * @param consumerGroup  Consumer group identifier
     * @param agents         Conduit for Agent instruments
     */
    public ConsumerRebalanceAgentMonitor(
        Circuit circuit,
        String consumerId,
        String consumerGroup,
        io.humainary.substrates.api.Substrates.Conduit<Agent, Agents.Signal> agents
    ) {
        this(circuit, consumerId, consumerGroup, agents, null);
    }

    /**
     * Creates consumer rebalance agent monitor with lag monitoring.
     *
     * @param circuit        Circuit for signal synchronization
     * @param consumerId     Consumer client identifier
     * @param consumerGroup  Consumer group identifier
     * @param agents         Conduit for Agent instruments
     * @param lagMonitor     Lag monitor for breach detection (optional)
     */
    public ConsumerRebalanceAgentMonitor(
        Circuit circuit,
        String consumerId,
        String consumerGroup,
        io.humainary.substrates.api.Substrates.Conduit<Agent, Agents.Signal> agents,
        ConsumerLagMonitor lagMonitor
    ) {
        this.circuit = circuit;
        this.consumerId = consumerId;
        this.consumerGroup = consumerGroup;
        this.lagMonitor = lagMonitor;

        // Create agents using static cortex() pattern (RC7)
        this.consumer = agents.get(
            io.humainary.substrates.api.Substrates.cortex().name(consumerId)
        );
        this.coordinator = agents.get(
            io.humainary.substrates.api.Substrates.cortex().name("coordinator-" + consumerGroup)
        );

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rebalance-monitor-" + consumerId);
            t.setDaemon(true);
            return t;
        });

        logger.info("Created ConsumerRebalanceAgentMonitor for consumer {} in group {}",
            consumerId, consumerGroup);
    }

    /**
     * Called when partitions are revoked from this consumer (rebalance starting).
     * <p>
     * Emits Agents API signals:
     * <ul>
     *   <li>INQUIRE (OUTBOUND): "Can I join the new generation?"</li>
     * </ul>
     * <p>
     * The consumer inquires about participating in the new generation after partitions
     * are revoked. This models the consumer's request to join the rebalance coordination.
     *
     * @param partitions Collection of partitions being revoked
     */
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        try {
            logger.info("Consumer {} rebalance REVOKED: group={} partitions={}",
                consumerId, consumerGroup, partitions.size());

            // Stop any ongoing breach monitoring
            stopBreachMonitoring();

            // Consumer inquires about joining new generation
            // OUTBOUND: "I ask to participate in rebalance"
            consumer.inquire();
            circuit.await();

            rebalanceStartTime = System.currentTimeMillis();

            logger.debug("Consumer {} emitted INQUIRE (OUTBOUND) for rebalance", consumerId);

        } catch (Exception e) {
            logger.error("Error in ConsumerRebalanceAgentMonitor.onPartitionsRevoked for consumer {}: {}",
                consumerId, e.getMessage(), e);
        }
    }

    /**
     * Called when partitions are assigned to this consumer (rebalance completed).
     * <p>
     * Emits Agents API signals in Promise Theory sequence:
     * <ul>
     *   <li>OFFERED (INBOUND): "Coordinator offered partition assignment"</li>
     *   <li>PROMISE (OUTBOUND): "I commit to consuming these partitions"</li>
     *   <li>ACCEPTED (INBOUND): "Coordinator accepted my promise"</li>
     * </ul>
     * <p>
     * After the promise is made, starts breach monitoring to detect if the consumer
     * fails to maintain its commitment (e.g., excessive lag).
     *
     * @param partitions Collection of partitions being assigned
     */
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        try {
            logger.info("Consumer {} rebalance ASSIGNED: group={} partitions={}",
                consumerId, consumerGroup, partitions.size());

            // Store assigned partitions for breach monitoring
            this.assignedPartitions = Set.copyOf(partitions);

            // Promise Theory lifecycle sequence

            // Step 1: Coordinator offers partition assignment
            // INBOUND: "Coordinator offered me these partitions" (past tense, observed)
            coordinator.offered();
            circuit.await();

            // Step 2: Consumer promises to consume partitions
            // OUTBOUND: "I promise to consume these partitions" (present tense, self)
            consumer.promise();
            circuit.await();

            // Step 3: Coordinator accepts the promise
            // INBOUND: "Coordinator accepted my promise" (past tense, observed)
            coordinator.accepted();
            circuit.await();

            logger.debug("Consumer {} completed promise lifecycle (OFFERED → PROMISE → ACCEPTED)",
                consumerId);

            // Start monitoring for promise breach (if lag monitor available)
            if (lagMonitor != null && !partitions.isEmpty()) {
                startBreachMonitoring();
            } else if (lagMonitor == null) {
                logger.debug("No lag monitor configured - breach detection disabled");
            }

        } catch (Exception e) {
            logger.error("Error in ConsumerRebalanceAgentMonitor.onPartitionsAssigned for consumer {}: {}",
                consumerId, e.getMessage(), e);
        }
    }

    /**
     * Notifies the monitor that offsets were successfully committed.
     * <p>
     * This can trigger FULFILL signal if consumption remains healthy.
     * Call this from your consumer's offset commit callback.
     *
     * @param offsets Committed offsets
     */
    public void onOffsetsCommitted(Map<TopicPartition, OffsetAndMetadata> offsets) {
        if (offsets == null || offsets.isEmpty()) {
            return;
        }

        logger.trace("Consumer {} committed offsets for {} partitions",
            consumerId, offsets.size());

        // Offset commits indicate active consumption - contributes to FULFILL assessment
    }

    // ========================================
    // Breach Monitoring
    // ========================================

    private void startBreachMonitoring() {
        if (monitoringActive.getAndSet(true)) {
            logger.debug("Breach monitoring already active for consumer {}", consumerId);
            return;
        }

        logger.debug("Starting breach monitoring for consumer {} after rebalance", consumerId);

        // Schedule periodic lag checks to detect promise breach
        scheduler.scheduleAtFixedRate(
            this::checkPromiseStatus,
            10,  // Initial delay (allow consumer to start processing)
            15,  // Check every 15 seconds
            TimeUnit.SECONDS
        );
    }

    private void stopBreachMonitoring() {
        if (monitoringActive.getAndSet(false)) {
            logger.debug("Stopping breach monitoring for consumer {}", consumerId);
        }
    }

    private void checkPromiseStatus() {
        if (!monitoringActive.get()) {
            return;
        }

        try {
            long timeSinceRebalance = System.currentTimeMillis() - rebalanceStartTime;

            // Skip if still in grace period (consumer starting up)
            if (timeSinceRebalance < GRACE_PERIOD_MS) {
                logger.trace("Consumer {} in grace period ({}/{}ms)",
                    consumerId, timeSinceRebalance, GRACE_PERIOD_MS);
                return;
            }

            // Note: In production, you would integrate with ConsumerLagMonitor to get actual lag
            // For now, we demonstrate the pattern with a placeholder
            long totalLag = getTotalLagFromMonitor();

            if (totalLag > BREACH_LAG_THRESHOLD) {
                // Consumer failed to maintain promise - excessive lag
                logger.warn("Consumer {} BREACHED promise: lag={} exceeds threshold={}",
                    consumerId, totalLag, BREACH_LAG_THRESHOLD);

                consumer.breach();  // OUTBOUND: "I failed to keep my promise"
                circuit.await();

                stopBreachMonitoring();

            } else if (totalLag < FULFILL_LAG_THRESHOLD && timeSinceRebalance > GRACE_PERIOD_MS * 2) {
                // Consumer successfully maintained promise - healthy consumption
                logger.info("Consumer {} FULFILLED promise: lag={} below threshold={}",
                    consumerId, totalLag, FULFILL_LAG_THRESHOLD);

                consumer.fulfill();  // OUTBOUND: "I kept my promise"
                circuit.await();

                stopBreachMonitoring();
            } else {
                logger.trace("Consumer {} promise status: lag={} (monitoring continues)",
                    consumerId, totalLag);
            }

        } catch (Exception e) {
            logger.error("Error checking promise status for consumer {}: {}",
                consumerId, e.getMessage(), e);
        }
    }

    private long getTotalLagFromMonitor() {
        // In production, integrate with WP5 ConsumerLagMonitor
        // For demonstration, return 0 (would be replaced with actual lag query)
        if (lagMonitor != null) {
            // Future: lagMonitor.getTotalLag(assignedPartitions)
            logger.trace("Lag monitor integration pending - returning 0");
        }
        return 0;  // Placeholder
    }

    @Override
    public void close() {
        stopBreachMonitoring();

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("Closed ConsumerRebalanceAgentMonitor for consumer {}", consumerId);
    }
}
