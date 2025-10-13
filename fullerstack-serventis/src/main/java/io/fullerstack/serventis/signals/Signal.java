package io.fullerstack.serventis.signals;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Base signal interface for all observability signals in the Kafka Observability Framework.
 *
 * <p>Signals are immutable event records emitted by sensor agents and routed through Cortex circuits.
 * All signal implementations should be Java records for immutability and thread-safety.
 *
 * @see MonitorSignal
 * @see ServiceSignal
 * @see QueueSignal
 */
public interface Signal {

    /**
     * Unique identifier for this signal.
     *
     * @return signal ID
     */
    UUID id();

    /**
     * Circuit name for routing (e.g., "kafka.broker.health").
     *
     * @return circuit name
     */
    String circuit();

    /**
     * Subject name within circuit (e.g., "broker-1.jvm.heap").
     *
     * <p>In Substrates terminology, the subject is the semantic identity that a Channel routes to.
     * This aligns with {@code Conduit.get(Name subject)} which retrieves a percept by subject name.
     *
     * @return subject name
     */
    String subject();

    /**
     * Timestamp when signal was emitted.
     *
     * @return signal timestamp
     */
    Instant timestamp();

    /**
     * Vector clock for causal ordering across distributed agents.
     *
     * @return vector clock
     */
    VectorClock vectorClock();

    /**
     * Additional metadata payload.
     *
     * @return metadata map
     */
    Map<String, String> payload();
}
