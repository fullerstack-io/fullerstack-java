package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing request queue metrics for a Kafka broker.
 *
 * <p>Tracks the size of the request queue to detect queue buildup and processing delays.
 *
 * @param brokerId  Broker identifier
 * @param queueSize Current number of requests in queue
 * @param timestamp Collection time (epoch milliseconds)
 */
public record RequestQueueMetrics(
    String brokerId,
    long queueSize,
    long timestamp
) {
    /**
     * Compact constructor with validation.
     */
    public RequestQueueMetrics {
        Objects.requireNonNull(brokerId, "brokerId required");
        if (queueSize < 0) throw new IllegalArgumentException("queueSize must be >= 0");
    }
}
