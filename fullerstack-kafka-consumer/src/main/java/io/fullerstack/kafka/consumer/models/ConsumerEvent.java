package io.fullerstack.kafka.consumer.models;

import org.apache.kafka.common.TopicPartition;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a consumer lifecycle event captured by ConsumerInterceptor.
 * <p>
 * Events include poll operations, commits, and rebalances.
 * <p>
 * <b>Note</b>: ConsumerInterceptor implementation is optional (deferred to Story 1.8).
 * This model is included for completeness.
 *
 * @param consumerId    Unique consumer identifier
 * @param consumerGroup Consumer group ID
 * @param eventType     Type of event (POLL, COMMIT, REBALANCE)
 * @param partitions    Affected partitions
 * @param timestamp     Event time (epoch milliseconds)
 * @param metadata      Additional event metadata (rebalance type, error details, etc.)
 */
public record ConsumerEvent(
        String consumerId,
        String consumerGroup,
        EventType eventType,
        List<TopicPartition> partitions,
        long timestamp,
        Map<String, String> metadata
) {
    /**
     * Type of consumer event.
     */
    public enum EventType {
        /** Records polled from broker */
        POLL,
        /** Offsets committed */
        COMMIT,
        /** Rebalance occurred */
        REBALANCE
    }

    /**
     * Compact constructor with validation.
     */
    public ConsumerEvent {
        Objects.requireNonNull(consumerId, "consumerId cannot be null");
        Objects.requireNonNull(consumerGroup, "consumerGroup cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");
        Objects.requireNonNull(partitions, "partitions cannot be null");
        Objects.requireNonNull(metadata, "metadata cannot be null");

        if (consumerId.isBlank()) {
            throw new IllegalArgumentException("consumerId cannot be blank");
        }
        if (consumerGroup.isBlank()) {
            throw new IllegalArgumentException("consumerGroup cannot be blank");
        }
    }
}
