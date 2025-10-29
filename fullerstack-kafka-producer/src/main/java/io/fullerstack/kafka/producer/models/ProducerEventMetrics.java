package io.fullerstack.kafka.producer.models;

import io.humainary.modules.serventis.services.api.Services;
import org.apache.kafka.clients.producer.RecordMetadata;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Rich contextual metrics for Kafka producer events.
 * <p>
 * Unlike the bare Services.Signal enum, this record captures all contextual data
 * needed for observability: topic, partition, offset, latency, exceptions, etc.
 * <p>
 * This pattern matches BrokerMetrics - rich domain data that flows through Cells
 * and gets transformed into semiotic signals by Composers.
 * <p>
 * Design:
 * - Immutable record with validation
 * - Services.Signal embedded as metadata (not the primary data)
 * - Null-safe (partition/offset may be -1 if unknown)
 * - Exception details captured for failure analysis
 * - Timestamp for temporal ordering
 */
public record ProducerEventMetrics(
    String producerId,
    String topic,
    int partition,
    long offset,
    Services.Signal signal,
    Exception exception,
    long latencyMs,
    Instant timestamp,
    Map<String, Object> metadata
) {

    /**
     * Canonical constructor with validation.
     */
    public ProducerEventMetrics {
        Objects.requireNonNull(producerId, "producerId cannot be null");
        Objects.requireNonNull(topic, "topic cannot be null");
        Objects.requireNonNull(signal, "signal cannot be null");
        Objects.requireNonNull(timestamp, "timestamp cannot be null");

        // Defensive copy of metadata
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Factory method for producer send event (CALL signal).
     *
     * @param producerId The producer client ID
     * @param topic      The topic being written to
     * @param partition  The target partition (may be -1 if not yet determined)
     * @return ProducerEventMetrics with CALL signal
     */
    public static ProducerEventMetrics call(
        String producerId,
        String topic,
        int partition
    ) {
        return new ProducerEventMetrics(
            producerId,
            topic,
            partition,
            -1L,  // offset not yet assigned
            Services.Signal.CALL,
            null,  // no exception
            0L,    // latency not yet measured
            Instant.now(),
            Map.of()
        );
    }

    /**
     * Factory method for successful acknowledgement (SUCCEEDED signal).
     *
     * @param producerId The producer client ID
     * @param metadata   The record metadata from broker ack
     * @param latencyMs  The time taken for send (ms)
     * @return ProducerEventMetrics with SUCCEEDED signal
     */
    public static ProducerEventMetrics succeeded(
        String producerId,
        RecordMetadata metadata,
        long latencyMs
    ) {
        return new ProducerEventMetrics(
            producerId,
            metadata.topic(),
            metadata.partition(),
            metadata.offset(),
            Services.Signal.SUCCEEDED,
            null,
            latencyMs,
            Instant.now(),
            Map.of(
                "serializedKeySize", metadata.serializedKeySize(),
                "serializedValueSize", metadata.serializedValueSize(),
                "brokerTimestamp", metadata.timestamp()
            )
        );
    }

    /**
     * Factory method for failed send (FAILED signal).
     *
     * @param producerId The producer client ID
     * @param topic      The topic that failed
     * @param partition  The partition (may be -1 if unknown)
     * @param exception  The exception that caused failure
     * @param latencyMs  The time taken before failure (ms)
     * @return ProducerEventMetrics with FAILED signal
     */
    public static ProducerEventMetrics failed(
        String producerId,
        String topic,
        int partition,
        Exception exception,
        long latencyMs
    ) {
        return new ProducerEventMetrics(
            producerId,
            topic,
            partition,
            -1L,  // offset not assigned on failure
            Services.Signal.FAILED,
            exception,
            latencyMs,
            Instant.now(),
            Map.of(
                "errorType", exception.getClass().getSimpleName(),
                "errorMessage", exception.getMessage() != null ? exception.getMessage() : ""
            )
        );
    }

    /**
     * Check if this event represents a failure.
     */
    public boolean isFailed() {
        return signal.sign() == Services.Sign.FAIL;
    }

    /**
     * Check if this event represents a success.
     */
    public boolean isSucceeded() {
        return signal.sign() == Services.Sign.SUCCESS;
    }

    /**
     * Check if this event represents an in-flight call.
     */
    public boolean isCall() {
        return signal.sign() == Services.Sign.CALL;
    }

    /**
     * Check if latency is high (exceeds threshold).
     *
     * @param thresholdMs The latency threshold in milliseconds
     * @return true if latency exceeds threshold
     */
    public boolean isSlowRequest(long thresholdMs) {
        return latencyMs > thresholdMs;
    }

    /**
     * Get human-readable description of this event.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Producer ").append(producerId)
            .append(" ").append(signal.name())
            .append(" topic=").append(topic);

        if (partition >= 0) {
            sb.append(" partition=").append(partition);
        }

        if (offset >= 0) {
            sb.append(" offset=").append(offset);
        }

        if (latencyMs > 0) {
            sb.append(" latency=").append(latencyMs).append("ms");
        }

        if (exception != null) {
            sb.append(" error=").append(exception.getClass().getSimpleName())
                .append(":").append(exception.getMessage());
        }

        return sb.toString();
    }

    /**
     * Get unmodifiable view of metadata.
     */
    @Override
    public Map<String, Object> metadata() {
        return Collections.unmodifiableMap(metadata);
    }
}
