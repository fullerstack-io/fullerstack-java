package io.fullerstack.kafka.consumer.sensors;

import io.humainary.serventis.services.Services;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Kafka ConsumerRebalanceListener that emits Services signals for rebalance events.
 * <p>
 * <b>Semiotic Observability Architecture (Substrates RC1):</b>
 * <pre>
 * Layer 2 (Services): Service lifecycle semantics
 *   → SUSPEND: Consumer partitions revoked (rebalance starting)
 *   → RESUME: Consumer partitions assigned (rebalance completed)
 * </pre>
 *
 * <h3>Signal Flow:</h3>
 * <pre>
 * 1. onPartitionsRevoked(partitions)
 *    → Service: suspend()     [Layer 2: "I am suspending"]
 *
 * 2. onPartitionsAssigned(partitions)
 *    → Service: resume()      [Layer 2: "I am resuming"]
 * </pre>
 *
 * <h3>Usage (RC1 Pattern):</h3>
 * <pre>{@code
 * // Runtime creates Services instrument
 * Services.Service service = circuit
 *     .conduit(Services::composer)
 *     .channel(Cortex.name("consumer.operations"));
 *
 * // Create rebalance listener with service
 * ConsumerRebalanceListenerAdapter listener = new ConsumerRebalanceListenerAdapter(
 *     "my-consumer",
 *     "my-group",
 *     service
 * );
 *
 * // Subscribe with rebalance listener
 * consumer.subscribe(
 *     List.of("my-topic"),
 *     listener
 * );
 * }</pre>
 *
 * <h3>Rebalance Lifecycle:</h3>
 * <ul>
 *   <li><b>Partition Revocation:</b> Consumer stops processing, emits SUSPEND signal</li>
 *   <li><b>Rebalance Coordination:</b> Kafka coordinator reassigns partitions</li>
 *   <li><b>Partition Assignment:</b> Consumer resumes processing, emits RESUME signal</li>
 * </ul>
 *
 * <h3>Error Handling:</h3>
 * All listener logic is wrapped in try-catch to prevent failures from breaking the consumer.
 * Errors are logged but do not propagate.
 *
 * <h3>Layer Separation:</h3>
 * <ul>
 *   <li><b>This listener (Layer 2):</b> Emits raw rebalance events</li>
 *   <li><b>Monitor aggregators (Layer 3):</b> Analyze patterns (rebalance frequency, duration)</li>
 *   <li><b>Reporter assessors (Layer 4):</b> Determine situations (rebalance storm detection)</li>
 * </ul>
 *
 * @author Fullerstack
 * @see Services
 * @see ConsumerEventInterceptor
 */
public class ConsumerRebalanceListenerAdapter implements ConsumerRebalanceListener {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerRebalanceListenerAdapter.class);

    private final String consumerId;
    private final String consumerGroup;
    private final Services.Service service;

    /**
     * Create a rebalance listener adapter with service signal emission.
     *
     * @param consumerId Consumer client identifier
     * @param consumerGroup Consumer group identifier
     * @param service Service instrument for signal emission (may be null)
     */
    public ConsumerRebalanceListenerAdapter(
        String consumerId,
        String consumerGroup,
        Services.Service service
    ) {
        this.consumerId = consumerId != null ? consumerId : "unknown-consumer";
        this.consumerGroup = consumerGroup != null ? consumerGroup : "unknown-group";
        this.service = service;

        if (service == null) {
            logger.warn(
                "ConsumerRebalanceListenerAdapter created without Service for consumer: {} group: {}. " +
                "Rebalance signals will not be emitted.",
                this.consumerId,
                this.consumerGroup
            );
        } else {
            logger.info(
                "ConsumerRebalanceListenerAdapter created for consumer: {} group: {}",
                this.consumerId,
                this.consumerGroup
            );
        }
    }

    /**
     * Called when partitions are revoked from this consumer (rebalance starting).
     * <p>
     * Emits Layer 2 Service SUSPEND signal indicating consumer is stopping partition processing.
     *
     * <h3>Timing:</h3>
     * <ul>
     *   <li>Called BEFORE rebalance coordination begins</li>
     *   <li>Consumer must stop processing and commit offsets</li>
     *   <li>Signal indicates "consumer is suspending"</li>
     * </ul>
     *
     * @param partitions Collection of partitions being revoked
     */
    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        try {
            // Layer 2 (Services): Consumer suspending partition processing
            if (service != null) {
                service.suspend();  // RELEASE orientation: "I am suspending"
            }

            logger.info("Consumer {} rebalance SUSPEND: group={} partitions={}",
                consumerId, consumerGroup, partitions.size());

        } catch (Exception e) {
            // CRITICAL: Don't let listener errors break the consumer
            logger.error("Error in ConsumerRebalanceListenerAdapter.onPartitionsRevoked for consumer {}: {}",
                consumerId, e.getMessage(), e);
        }
    }

    /**
     * Called when partitions are assigned to this consumer (rebalance completed).
     * <p>
     * Emits Layer 2 Service RESUME signal indicating consumer is starting partition processing.
     *
     * <h3>Timing:</h3>
     * <ul>
     *   <li>Called AFTER rebalance coordination completes</li>
     *   <li>Consumer can now start processing assigned partitions</li>
     *   <li>Signal indicates "consumer is resuming"</li>
     * </ul>
     *
     * @param partitions Collection of partitions being assigned
     */
    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        try {
            // Layer 2 (Services): Consumer resuming partition processing
            if (service != null) {
                service.resume();  // RELEASE orientation: "I am resuming"
            }

            logger.info("Consumer {} rebalance RESUME: group={} partitions={}",
                consumerId, consumerGroup, partitions.size());

        } catch (Exception e) {
            // CRITICAL: Don't let listener errors break the consumer
            logger.error("Error in ConsumerRebalanceListenerAdapter.onPartitionsAssigned for consumer {}: {}",
                consumerId, e.getMessage(), e);
        }
    }
}
