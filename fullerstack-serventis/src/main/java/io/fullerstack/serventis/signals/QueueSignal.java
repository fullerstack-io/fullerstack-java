package io.fullerstack.serventis.signals;

import io.humainary.modules.serventis.queues.api.Queues;
import io.humainary.substrates.api.Substrates.Subject;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Queue signal for partition-level metrics.
 *
 * <p>Uses the authentic Humainary Queues API for queue operations.
 * Each signal has a Sign (operation type) and units (quantity involved).
 *
 * <p><b>Semiotic Interpretation:</b>
 * Queue signals represent the flow of data through queues - puts (writes),
 * takes (reads), and boundary conditions (overflow/underflow). The Sign enum
 * provides semantic meaning about queue operations and their outcomes.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Subject partitionQueue = cortex.subject(
 *     cortex.name("kafka.partition.behavior"),
 *     cortex.name("my-topic.0.producer")
 * );
 *
 * QueueSignal signal = QueueSignal.overflow(
 *     partitionQueue,
 *     1000,
 *     Map.of("topic", "my-topic", "partition", "0", "diskUsage", "95%")
 * );
 *
 * if (signal.requiresAttention()) {
 *     logger.error(signal.interpret());
 *     // "Queue OVERFLOW: Queue overflow detected - 1000 units"
 * }
 * }</pre>
 *
 * <p>Emitted by PartitionSensorAgent monitoring partition lag, disk usage, and activity.
 * Routed through "kafka.partition.behavior" circuit → "queues" conduit.
 *
 * @param id unique signal identifier
 * @param subject Substrates subject (circuit + entity identity)
 * @param timestamp when signal was emitted
 * @param vectorClock causal ordering clock
 * @param queueSignal Humainary Queues.Signal (Sign + units)
 * @param payload additional metadata (topic, partition, offset, lag, depth, diskUsage)
 */
public record QueueSignal(
    UUID id,
    Subject subject,
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
     * @param subject Substrates subject
     * @param sign queue operation type
     * @param units quantity involved
     * @param metadata additional context
     * @return new QueueSignal
     */
    public static QueueSignal create(
        Subject subject,
        Queues.Sign sign,
        long units,
        Map<String, String> metadata
    ) {
        return new QueueSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            new QueueSignalImpl(sign, units),
            metadata
        );
    }

    /**
     * Creates a PUT signal indicating producer wrote to partition.
     *
     * @param subject Substrates subject
     * @param units number of messages written
     * @param metadata additional context (topic, partition, offset)
     * @return new QueueSignal with PUT
     */
    public static QueueSignal put(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Queues.Sign.PUT, units, metadata);
    }

    /**
     * Creates a TAKE signal indicating consumer read from partition.
     *
     * @param subject Substrates subject
     * @param units number of messages read
     * @param metadata additional context (topic, partition, offset, lag)
     * @return new QueueSignal with TAKE
     */
    public static QueueSignal take(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Queues.Sign.TAKE, units, metadata);
    }

    /**
     * Creates an OVERFLOW signal indicating partition approaching disk capacity.
     *
     * @param subject Substrates subject
     * @param units number of units that couldn't be added
     * @param metadata additional context (topic, partition, diskUsage, threshold)
     * @return new QueueSignal with OVERFLOW
     */
    public static QueueSignal overflow(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Queues.Sign.OVERFLOW, units, metadata);
    }

    /**
     * Creates an UNDERFLOW signal indicating take failed due to empty queue.
     *
     * @param subject Substrates subject
     * @param units number of units that couldn't be taken
     * @param metadata additional context (topic, partition, consumerLag)
     * @return new QueueSignal with UNDERFLOW
     */
    public static QueueSignal underflow(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Queues.Sign.UNDERFLOW, units, metadata);
    }

    // Queues.Queue interface implementation

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

    // Signal interface implementation - Semantic Helpers

    /**
     * Returns the severity level based on the queue sign.
     *
     * <p><b>Severity Mapping:</b>
     * <ul>
     *   <li>OVERFLOW → CRITICAL</li>
     *   <li>UNDERFLOW → WARNING</li>
     *   <li>PUT, TAKE → INFO</li>
     * </ul>
     */
    @Override
    public Severity severity() {
        return switch (sign()) {
            case OVERFLOW -> Severity.CRITICAL;
            case UNDERFLOW -> Severity.WARNING;
            case PUT, TAKE -> Severity.INFO;
        };
    }

    /**
     * Checks if this queue signal requires attention.
     *
     * <p>Requires attention if sign is:
     * OVERFLOW or UNDERFLOW
     *
     * @return true if signal indicates a problem
     */
    @Override
    public boolean requiresAttention() {
        return switch (sign()) {
            case OVERFLOW, UNDERFLOW -> true;
            case PUT, TAKE -> false;
        };
    }

    /**
     * Returns a human-readable interpretation of this queue signal.
     *
     * <p>Example: "Queue OVERFLOW: Queue overflow detected - 1000 units"
     *
     * @return interpretable description
     */
    @Override
    public String interpret() {
        String signText = switch (sign()) {
            case PUT -> "Data written to queue";
            case TAKE -> "Data read from queue";
            case OVERFLOW -> "Queue overflow detected";
            case UNDERFLOW -> "Queue underflow detected";
        };

        return String.format(
            "Queue %s: %s - %d units",
            sign(),
            signText,
            units()
        );
    }

    /**
     * Checks if the queue operation is a normal data flow operation.
     *
     * @return true if PUT or TAKE
     */
    public boolean isNormalOperation() {
        return sign() == Queues.Sign.PUT || sign() == Queues.Sign.TAKE;
    }

    /**
     * Checks if the queue is experiencing capacity issues.
     *
     * @return true if OVERFLOW or UNDERFLOW
     */
    public boolean hasCapacityIssue() {
        return sign() == Queues.Sign.OVERFLOW || sign() == Queues.Sign.UNDERFLOW;
    }

    /**
     * Checks if this is an overflow condition (queue full).
     *
     * @return true if OVERFLOW
     */
    public boolean isOverflow() {
        return sign() == Queues.Sign.OVERFLOW;
    }

    /**
     * Checks if this is an underflow condition (queue empty).
     *
     * @return true if UNDERFLOW
     */
    public boolean isUnderflow() {
        return sign() == Queues.Sign.UNDERFLOW;
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
