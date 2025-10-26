package io.fullerstack.serventis.signals;

import io.humainary.modules.serventis.probes.api.Probes;
import io.humainary.substrates.api.Substrates.Subject;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Probe signal for client-server communication observations.
 *
 * <p>Uses the authentic Humainary Probes API for operation tracking.
 * Each observation captures Origin × Operation × Outcome (3D model).
 *
 * <p><b>Semiotic Interpretation:</b>
 * Probe signals represent observations of communication operations from different
 * perspectives (CLIENT or SERVER). The three-dimensional model (Origin, Operation, Outcome)
 * provides rich semantic context about what happened, from whose perspective, and
 * whether it succeeded or failed.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Subject producerSend = cortex.subject(
 *     cortex.name("kafka.client.interactions"),
 *     cortex.name("producer-123.send")
 * );
 *
 * ProbeSignal signal = ProbeSignal.send(
 *     producerSend,
 *     Probes.Origin.CLIENT,
 *     Probes.Outcome.FAILURE,
 *     Map.of("error", "TimeoutException", "latency_ms", "5000")
 * );
 *
 * if (signal.requiresAttention()) {
 *     logger.error(signal.interpret());
 *     // "Probe CLIENT/SEND/FAILURE: Client send operation failed"
 * }
 * }</pre>
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
 * @param subject Substrates subject (circuit + entity identity)
 * @param timestamp when observation was made
 * @param vectorClock causal ordering clock
 * @param observation Humainary Probes.Observation (Origin × Operation × Outcome)
 * @param payload additional metadata (topic, partition, latency, etc.)
 */
public record ProbeSignal(
    UUID id,
    Subject subject,
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
     * @param subject Substrates subject
     * @param origin observation perspective (CLIENT or SERVER)
     * @param operation type of operation (CONNECT, SEND, RECEIVE, PROCESS, CLOSE)
     * @param outcome result (SUCCESS or FAILURE)
     * @param metadata additional context
     * @return new ProbeSignal
     */
    public static ProbeSignal create(
        Subject subject,
        Probes.Origin origin,
        Probes.Operation operation,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return new ProbeSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            new ProbeObservationImpl(origin, operation, outcome),
            metadata
        );
    }

    /**
     * Creates a CONNECT probe signal.
     *
     * @param subject Substrates subject
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata connection details (broker, port, latency)
     * @return CONNECT probe signal
     */
    public static ProbeSignal connect(
        Subject subject,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(subject, origin, Probes.Operation.CONNECT, outcome, metadata);
    }

    /**
     * Creates a SEND probe signal.
     *
     * @param subject Substrates subject
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata send details (topic, partition, offset, bytes)
     * @return SEND probe signal
     */
    public static ProbeSignal send(
        Subject subject,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(subject, origin, Probes.Operation.SEND, outcome, metadata);
    }

    /**
     * Creates a RECEIVE probe signal.
     *
     * @param subject Substrates subject
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata receive details (topic, partition, offset, count, bytes)
     * @return RECEIVE probe signal
     */
    public static ProbeSignal receive(
        Subject subject,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(subject, origin, Probes.Operation.RECEIVE, outcome, metadata);
    }

    /**
     * Creates a PROCESS probe signal.
     *
     * @param subject Substrates subject
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata processing details (duration, records processed)
     * @return PROCESS probe signal
     */
    public static ProbeSignal process(
        Subject subject,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(subject, origin, Probes.Operation.PROCESS, outcome, metadata);
    }

    /**
     * Creates a CLOSE probe signal.
     *
     * @param subject Substrates subject
     * @param origin CLIENT or SERVER
     * @param outcome SUCCESS or FAILURE
     * @param metadata close details (reason, duration)
     * @return CLOSE probe signal
     */
    public static ProbeSignal close(
        Subject subject,
        Probes.Origin origin,
        Probes.Outcome outcome,
        Map<String, String> metadata
    ) {
        return create(subject, origin, Probes.Operation.CLOSE, outcome, metadata);
    }

    // Probes.Probe interface implementation

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

    // Signal interface implementation - Semantic Helpers

    /**
     * Returns the severity level based on the probe outcome.
     *
     * <p><b>Severity Mapping:</b>
     * <ul>
     *   <li>FAILURE → ERROR</li>
     *   <li>SUCCESS → INFO</li>
     * </ul>
     */
    @Override
    public Severity severity() {
        return switch (outcome()) {
            case FAILURE -> Severity.ERROR;
            case SUCCESS -> Severity.INFO;
        };
    }

    /**
     * Checks if this probe signal requires attention.
     *
     * <p>Requires attention if outcome is:
     * FAILURE
     *
     * @return true if signal indicates a problem
     */
    @Override
    public boolean requiresAttention() {
        return outcome() == Probes.Outcome.FAILURE;
    }

    /**
     * Returns a human-readable interpretation of this probe signal.
     *
     * <p>Example: "Probe CLIENT/SEND/FAILURE: Client send operation failed"
     *
     * @return interpretable description
     */
    @Override
    public String interpret() {
        String originText = switch (origin()) {
            case CLIENT -> "Client";
            case SERVER -> "Server";
        };

        String operationText = switch (operation()) {
            case CONNECT -> "connection";
            case SEND -> "send";
            case RECEIVE -> "receive";
            case PROCESS -> "processing";
            case CLOSE -> "close";
        };

        String outcomeText = switch (outcome()) {
            case SUCCESS -> "succeeded";
            case FAILURE -> "failed";
        };

        return String.format(
            "Probe %s/%s/%s: %s %s operation %s",
            origin(),
            operation(),
            outcome(),
            originText,
            operationText,
            outcomeText
        );
    }

    /**
     * Checks if the probe operation was successful.
     *
     * @return true if SUCCESS
     */
    public boolean isSuccessful() {
        return outcome() == Probes.Outcome.SUCCESS;
    }

    /**
     * Checks if the probe operation failed.
     *
     * @return true if FAILURE
     */
    public boolean isFailed() {
        return outcome() == Probes.Outcome.FAILURE;
    }


    /**
     * Checks if this is a client-side observation.
     *
     * @return true if CLIENT origin
     */
    public boolean isClientOrigin() {
        return origin() == Probes.Origin.CLIENT;
    }

    /**
     * Checks if this is a server-side observation.
     *
     * @return true if SERVER origin
     */
    public boolean isServerOrigin() {
        return origin() == Probes.Origin.SERVER;
    }

    /**
     * Creates a new ProbeSignal with an updated vector clock.
     *
     * @param newClock the new vector clock
     * @return new ProbeSignal with updated clock
     */
    public ProbeSignal withClock(VectorClock newClock) {
        return new ProbeSignal(id, subject, timestamp, newClock, observation, payload);
    }

    /**
     * Creates a new ProbeSignal with additional payload entries.
     *
     * @param additionalPayload additional metadata to merge
     * @return new ProbeSignal with merged payload
     */
    public ProbeSignal withPayload(Map<String, String> additionalPayload) {
        Map<String, String> merged = new java.util.HashMap<>(payload);
        merged.putAll(additionalPayload);
        return new ProbeSignal(id, subject, timestamp, vectorClock, observation, merged);
    }

    /**
     * Creates a builder for constructing ProbeSignals.
     *
     * @return new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ProbeSignal with fluent API.
     */
    public static class Builder {
        private UUID id = UUID.randomUUID();
        private Subject subject;
        private Instant timestamp = Instant.now();
        private VectorClock vectorClock = VectorClock.empty();
        private Probes.Origin origin;
        private Probes.Operation operation;
        private Probes.Outcome outcome;
        private final Map<String, String> payload = new java.util.HashMap<>();

        private Builder() {}

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder subject(Subject subject) {
            this.subject = subject;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder vectorClock(VectorClock vectorClock) {
            this.vectorClock = vectorClock;
            return this;
        }

        public Builder origin(Probes.Origin origin) {
            this.origin = origin;
            return this;
        }

        public Builder operation(Probes.Operation operation) {
            this.operation = operation;
            return this;
        }

        public Builder outcome(Probes.Outcome outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder payload(Map<String, String> payload) {
            this.payload.clear();
            this.payload.putAll(payload);
            return this;
        }

        public Builder addPayload(String key, String value) {
            this.payload.put(key, value);
            return this;
        }

        public Builder connect() {
            this.operation = Probes.Operation.CONNECT;
            return this;
        }

        public Builder send() {
            this.operation = Probes.Operation.SEND;
            return this;
        }

        public Builder receive() {
            this.operation = Probes.Operation.RECEIVE;
            return this;
        }

        public Builder process() {
            this.operation = Probes.Operation.PROCESS;
            return this;
        }

        public Builder close() {
            this.operation = Probes.Operation.CLOSE;
            return this;
        }

        public Builder client() {
            this.origin = Probes.Origin.CLIENT;
            return this;
        }

        public Builder server() {
            this.origin = Probes.Origin.SERVER;
            return this;
        }

        public Builder success() {
            this.outcome = Probes.Outcome.SUCCESS;
            return this;
        }

        public Builder failure() {
            this.outcome = Probes.Outcome.FAILURE;
            return this;
        }

        public ProbeSignal build() {
            if (subject == null) {
                throw new IllegalStateException("Subject is required");
            }
            if (origin == null) {
                throw new IllegalStateException("Origin is required");
            }
            if (operation == null) {
                throw new IllegalStateException("Operation is required");
            }
            if (outcome == null) {
                throw new IllegalStateException("Outcome is required");
            }
            return new ProbeSignal(
                id,
                subject,
                timestamp,
                vectorClock,
                new ProbeObservationImpl(origin, operation, outcome),
                payload
            );
        }
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
