package io.fullerstack.kafka.consumer.sensors;

import io.humainary.serventis.queues.Queues.Queue;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors Kafka consumer lag and emits queue signals using RC1 Serventis API.
 * <p>
 * Uses AdminClient to calculate lag (end offset - current offset) and emits signals based on thresholds:
 * <ul>
 *   <li><b>UNDERFLOW (severely lagging)</b> (≥10,000 messages): {@code queue.underflow(lag)}</li>
 *   <li><b>UNDERFLOW (lagging)</b> (1,000-10,000 messages): {@code queue.underflow(lag)}</li>
 *   <li><b>TAKE (normal)</b> (<1,000 messages): {@code queue.take()}</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * QueueFlowCircuit circuit = new QueueFlowCircuit();
 * Queue lagQueue = circuit.queueFor("consumer-group-1.lag");
 *
 * Properties adminProps = new Properties();
 * adminProps.put("bootstrap.servers", "localhost:9092");
 *
 * ConsumerLagMonitor monitor = new ConsumerLagMonitor(
 *     "consumer-group-1",
 *     adminProps,
 *     lagQueue
 * );
 *
 * monitor.start();  // Begins monitoring every 15 seconds
 *
 * // Later...
 * monitor.stop();
 * }</pre>
 *
 * @author Fullerstack
 * @see Queue
 */
public class ConsumerLagMonitor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerLagMonitor.class);

    // Thresholds for lag assessment
    private static final long SEVERE_LAG_THRESHOLD = 10_000;   // ≥10k messages = severely lagging
    private static final long LAG_THRESHOLD = 1_000;           // 1k-10k messages = lagging

    private final String consumerGroupId;
    private final Properties adminClientProps;
    private final Queue lagQueue;

    private final ScheduledExecutorService scheduler;
    private AdminClient adminClient;
    private volatile boolean running = false;

    /**
     * Creates a new consumer lag monitor.
     *
     * @param consumerGroupId  Consumer group ID (e.g., "consumer-group-1")
     * @param adminClientProps Properties for AdminClient (must include bootstrap.servers)
     * @param lagQueue         Queue instrument for emitting lag signals
     */
    public ConsumerLagMonitor(
        String consumerGroupId,
        Properties adminClientProps,
        Queue lagQueue
    ) {
        this.consumerGroupId = consumerGroupId;
        this.adminClientProps = adminClientProps;
        this.lagQueue = lagQueue;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "consumer-lag-monitor-" + consumerGroupId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts lag monitoring.
     * <p>
     * Creates AdminClient and schedules lag checks every 15 seconds.
     */
    public void start() {
        if (running) {
            logger.warn("Consumer lag monitor for {} is already running", consumerGroupId);
            return;
        }

        try {
            this.adminClient = AdminClient.create(adminClientProps);
            running = true;

            // Schedule lag monitoring every 15 seconds
            scheduler.scheduleAtFixedRate(
                this::collectAndEmit,
                0,          // Initial delay
                15,         // Period
                TimeUnit.SECONDS
            );

            logger.info("Started consumer lag monitor for group {}", consumerGroupId);

        } catch (Exception e) {
            logger.error("Failed to start consumer lag monitor for group {}", consumerGroupId, e);
            running = false;
            throw new RuntimeException("Failed to start consumer lag monitor", e);
        }
    }

    /**
     * Stops lag monitoring and releases resources.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (adminClient != null) {
            adminClient.close();
        }

        logger.info("Stopped consumer lag monitor for group {}", consumerGroupId);
    }

    @Override
    public void close() {
        stop();
    }

    // ========================================
    // Lag Calculation & Signal Emission
    // ========================================

    private void collectAndEmit() {
        try {
            // Get consumer group offsets
            Map<TopicPartition, OffsetAndMetadata> groupOffsets = adminClient
                .listConsumerGroupOffsets(consumerGroupId)
                .partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS);

            if (groupOffsets.isEmpty()) {
                logger.debug("No committed offsets for consumer group {}", consumerGroupId);
                return;
            }

            // Get end offsets for partitions
            Map<TopicPartition, OffsetSpec> offsetSpecs = new HashMap<>();
            for (TopicPartition tp : groupOffsets.keySet()) {
                offsetSpecs.put(tp, OffsetSpec.latest());
            }

            ListOffsetsResult endOffsetsResult = adminClient.listOffsets(offsetSpecs);
            Map<TopicPartition, Long> endOffsets = new HashMap<>();
            for (Map.Entry<TopicPartition, OffsetSpec> entry : offsetSpecs.entrySet()) {
                TopicPartition tp = entry.getKey();
                long endOffset = endOffsetsResult.partitionResult(tp).get(10, TimeUnit.SECONDS).offset();
                endOffsets.put(tp, endOffset);
            }

            // Calculate total lag
            long totalLag = 0;
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : groupOffsets.entrySet()) {
                TopicPartition tp = entry.getKey();
                long currentOffset = entry.getValue().offset();
                long endOffset = endOffsets.getOrDefault(tp, currentOffset);
                long lag = endOffset - currentOffset;
                totalLag += Math.max(0, lag);  // Negative lag shouldn't happen, but guard against it
            }

            // Emit signal based on lag (RC1 instrument pattern)
            emitLagSignal(totalLag);

        } catch (Exception e) {
            logger.error("Error collecting lag metrics for consumer group {}", consumerGroupId, e);
            // Emit underflow on error (conservative approach)
            lagQueue.underflow();
        }
    }

    private void emitLagSignal(long totalLag) {
        if (totalLag >= SEVERE_LAG_THRESHOLD) {
            // Severely lagging - UNDERFLOW with high units
            lagQueue.underflow(totalLag);
            logger.warn("Consumer group {} SEVERE LAG: {} messages behind",
                consumerGroupId, totalLag);

        } else if (totalLag >= LAG_THRESHOLD) {
            // Lagging - UNDERFLOW with moderate units
            lagQueue.underflow(totalLag);
            logger.debug("Consumer group {} LAGGING: {} messages behind",
                consumerGroupId, totalLag);

        } else {
            // Normal operation - TAKE without units (consuming normally)
            lagQueue.take();
            logger.trace("Consumer group {} NORMAL: {} messages behind",
                consumerGroupId, totalLag);
        }
    }
}
