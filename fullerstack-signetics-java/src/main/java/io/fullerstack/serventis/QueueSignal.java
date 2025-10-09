package io.fullerstack.serventis;

import io.humainary.modules.serventis.queues.api.Queues;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Queue signal for partition-level metrics.
 *
 * <p>Uses the authentic Humainary Queues API for queue operations.
 * Each signal has a Sign (operation type) and units (quantity involved).
 *
 * <p>Emitted by PartitionSensorAgent monitoring partition lag, disk usage, and activity.
 * Routed through "kafka.partition.behavior" circuit → "queues" conduit.
 *
 * @param id unique signal identifier
 * @param circuit circuit name (e.g., "kafka.partition.behavior")
 * @param channel channel name (e.g., "my-topic.0.producer")
 * @param timestamp when signal was emitted
 * @param vectorClock causal ordering clock
 * @param queueSignal Humainary Queues.Signal (Sign + units)
 * @param payload additional metadata (topic, partition, offset, lag, depth, diskUsage)
 */
public record QueueSignal(
    UUID id,
    String circuit,
    String channel,
    Instant timestamp,
    VectorClock vectorClock,
    Queues.Signal queueSignal,
    Map<String, String> payload
) implements Signal {

    public QueueSignal {
        payload = Map.copyOf(payload);
    }

    /**
     * Creates a queue signal with the specified sign and units.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param sign queue operation type
     * @param units quantity involved
     * @param metadata additional context
     * @return new QueueSignal
     */
    public static QueueSignal create(
        String circuit,
        String channel,
        Queues.Sign sign,
        long units,
        Map<String, String> metadata
    ) {
        return new QueueSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            new QueueSignalImpl(sign, units),
            metadata
        );
    }

    /**
     * Creates a PUT signal indicating producer wrote to partition.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of messages written
     * @param metadata additional context (topic, partition, offset)
     * @return new QueueSignal with PUT
     */
    public static QueueSignal put(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Queues.Sign.PUT, units, metadata);
    }

    /**
     * Creates a TAKE signal indicating consumer read from partition.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of messages read
     * @param metadata additional context (topic, partition, offset, lag)
     * @return new QueueSignal with TAKE
     */
    public static QueueSignal take(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Queues.Sign.TAKE, units, metadata);
    }

    /**
     * Creates an OVERFLOW signal indicating partition approaching disk capacity.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of units that couldn't be added
     * @param metadata additional context (topic, partition, diskUsage, threshold)
     * @return new QueueSignal with OVERFLOW
     */
    public static QueueSignal overflow(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Queues.Sign.OVERFLOW, units, metadata);
    }

    /**
     * Creates an UNDERFLOW signal indicating take failed due to empty queue.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of units that couldn't be taken
     * @param metadata additional context (topic, partition, consumerLag)
     * @return new QueueSignal with UNDERFLOW
     */
    public static QueueSignal underflow(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Queues.Sign.UNDERFLOW, units, metadata);
    }

    // Convenience methods

    /**
     * @return the queue operation sign (PUT, TAKE, OVERFLOW, UNDERFLOW)
     */
    public Queues.Sign sign() {
        return queueSignal.sign();
    }

    /**
     * @return the number of units involved in this queue operation
     */
    public long units() {
        return queueSignal.units();
    }

    /**
     * Simple implementation of Queues.Signal
     */
    record QueueSignalImpl(
        Queues.Sign sign,
        long units
    ) implements Queues.Signal {
    }
}
