package io.fullerstack.serventis.signals;

import io.humainary.modules.serventis.probes.api.Probes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Probe signal for client-server communication observations.
 *
 * <p>Uses the authentic Humainary Probes API for operation tracking.
 * Each observation captures Origin × Operation × Outcome (3D model).
 *
 * <p>Emitted by ClientSensorAgent and BrokerSensorAgent for network operations:
 * <ul>
 *   <li>CONNECT - Establishing connections (TCP, auth)</li>
 *   <li>SEND - Sending data (producer.send, broker replication)</li>
 *   <li>RECEIVE - Receiving data (consumer.poll, broker receive request)</li>
 *   <li>PROCESS - Processing data (consumer record processing, broker request handling)</li>
 *   <li>CLOSE - Closing connections (graceful shutdown, disconnect)</li>
 * </ul>
 *
 * <p>Routed through "kafka.client.interactions" circuit → "probes" conduit.
 *
 * @param id unique signal identifier
 * @param circuit circuit name (e.g., "kafka.client.interactions")
 * @param channel channel name (e.g., "producer-123.send")
 * @param timestamp when observation was made
 * @param vectorClock causal ordering clock
 * @param observation Humainary Probes.Observation (Origin × Operation × Outcome)
 * @param payload additional metadata (topic, partition, latency, etc.)
 */
public record ProbeSignal(
    UUID id,
    String circuit,
    String channel,
    Instant timestamp,
    VectorClock vectorClock,
    Probes.Observation observation,
    Map<String, String> payload
) implements Signal {

    public ProbeSignal {
        payload = Map.copyOf(payload);
    }

    /**
     * Creates a probe signal with the specified origin, operation, and outcome.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param origin observation perspective (CLIENT or SERVER)
     * @param operation type of operation (CONNECT, SEND, RECEIVE, PROCESS, CLOSE)
     * @param outcome result (SUCCESS or FAILURE)
     * @param metadata additional context
     * @return new ProbeSignal
     */
    public static ProbeSignal create(
        String circuit,
        String channel,
        Probes.Origin origin,
        Probes.Operation operation,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return new ProbeSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            new ProbeObservationImpl(origin, operation, outcome),
            metadata
        );
    }

    /**
     * Creates a CONNECT probe signal.
     *
     * @param circuit circuit name
     * @param channel channel name
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata connection details (broker, port, latency)
     * @return CONNECT probe signal
     */
    public static ProbeSignal connect(
        String circuit,
        String channel,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(circuit, channel, origin, Probes.Operation.CONNECT, outcome, metadata);
    }

    /**
     * Creates a SEND probe signal.
     *
     * @param circuit circuit name
     * @param channel channel name
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata send details (topic, partition, offset, bytes)
     * @return SEND probe signal
     */
    public static ProbeSignal send(
        String circuit,
        String channel,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(circuit, channel, origin, Probes.Operation.SEND, outcome, metadata);
    }

    /**
     * Creates a RECEIVE probe signal.
     *
     * @param circuit circuit name
     * @param channel channel name
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata receive details (topic, partition, offset, count, bytes)
     * @return RECEIVE probe signal
     */
    public static ProbeSignal receive(
        String circuit,
        String channel,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(circuit, channel, origin, Probes.Operation.RECEIVE, outcome, metadata);
    }

    /**
     * Creates a PROCESS probe signal.
     *
     * @param circuit circuit name
     * @param channel channel name
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata processing details (duration, records processed)
     * @return PROCESS probe signal
     */
    public static ProbeSignal process(
        String circuit,
        String channel,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(circuit, channel, origin, Probes.Operation.PROCESS, outcome, metadata);
    }

    /**
     * Creates a CLOSE probe signal.
     *
     * @param circuit circuit name
     * @param channel channel name
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata close details (reason, duration)
     * @return CLOSE probe signal
     */
    public static ProbeSignal close(
        String circuit,
        String channel,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(circuit, channel, origin, Probes.Operation.CLOSE, outcome, metadata);
    }

    // Convenience methods

    /**
     * @return the observation perspective (CLIENT or SERVER)
     */
    public Probes.Origin origin() {
        return observation.origin();
    }

    /**
     * @return the operation type (CONNECT, SEND, RECEIVE, PROCESS, CLOSE)
     */
    public Probes.Operation operation() {
        return observation.operation();
    }

    /**
     * @return the operation outcome (SUCCESS or FAILURE)
     */
    public Probes.Outcome outcome() {
        return observation.outcome();
    }

    /**
     * Simple implementation of Probes.Observation interface.
     */
    record ProbeObservationImpl(
        Probes.Origin origin,
        Probes.Operation operation,
        Probes.Outcome outcome
    ) implements Probes.Observation {
    }
}
