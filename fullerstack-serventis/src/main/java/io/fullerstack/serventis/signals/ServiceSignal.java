package io.fullerstack.serventis.signals;

import io.humainary.modules.serventis.services.api.Services;
import io.humainary.substrates.api.Substrates.Subject;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service signal for producer/consumer request outcomes.
 *
 * <p>Uses the authentic Humainary Services API for signal semantics.
 * Each signal has a Sign (semantic meaning) and Orientation (perspective).
 *
 * <p><b>Semiotic Interpretation:</b>
 * Service signals represent the lifecycle of service interactions - calls, starts,
 * stops, successes, and failures. The Sign enum provides semantic meaning
 * (CALL, SUCCESS, FAIL) combined with Orientation (RELEASE=self, RECEIPT=observed).
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Subject producerSend = cortex.subject(
 *     cortex.name("kafka.client.interactions"),
 *     cortex.name("producer-app1.send")
 * );
 *
 * ServiceSignal signal = ServiceSignal.fail(
 *     producerSend,
 *     Map.of("error", "TimeoutException", "broker", "broker-1")
 * );
 *
 * if (signal.requiresAttention()) {
 *     logger.error(signal.interpret());
 *     // "Service FAIL (RELEASE): Service operation failed"
 * }
 * }</pre>
 *
 * <p>Emitted by ClientSensorInterceptor on send/poll completion.
 * Routed through "kafka.client.interactions" circuit → "services" conduit.
 *
 * @param id unique signal identifier
 * @param subject Substrates subject (circuit + entity identity)
 * @param timestamp when signal was emitted
 * @param vectorClock causal ordering clock
 * @param signal Humainary Services.Signal (combines Sign + Orientation)
 * @param payload additional metadata
 */
public record ServiceSignal(
    UUID id,
    Subject subject,
    Instant timestamp,
    VectorClock vectorClock,
    Services.Signal signal,
    Map<String, String> payload
) implements Signal {

    public ServiceSignal {
        payload = Map.copyOf(payload);
    }

    /**
     * Creates a CALL signal (RELEASE orientation) indicating self-initiated service call.
     *
     * @param subject Substrates subject
     * @param metadata additional context
     * @return new ServiceSignal with CALL/RELEASE
     */
    public static ServiceSignal call(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.CALL,
            metadata
        );
    }

    /**
     * Creates a CALLED signal (RECEIPT orientation) indicating observed incoming call.
     *
     * @param subject Substrates subject
     * @param metadata additional context
     * @return new ServiceSignal with CALL/RECEIPT
     */
    public static ServiceSignal called(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.CALLED,
            metadata
        );
    }

    /**
     * Creates a SUCCESS signal (RELEASE orientation) indicating self-reported successful completion.
     *
     * @param subject Substrates subject
     * @param metadata additional context
     * @return new ServiceSignal with SUCCESS/RELEASE
     */
    public static ServiceSignal success(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.SUCCESS,
            metadata
        );
    }

    /**
     * Creates a SUCCEEDED signal (RECEIPT orientation) indicating observed successful response.
     *
     * @param subject Substrates subject
     * @param metadata additional context
     * @return new ServiceSignal with SUCCESS/RECEIPT
     */
    public static ServiceSignal succeeded(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.SUCCEEDED,
            metadata
        );
    }

    /**
     * Creates a FAIL signal (RELEASE orientation) indicating self-reported failure.
     *
     * @param subject Substrates subject
     * @param metadata additional context (should include error details)
     * @return new ServiceSignal with FAIL/RELEASE
     */
    public static ServiceSignal fail(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.FAIL,
            metadata
        );
    }

    /**
     * Creates a FAILED signal (RECEIPT orientation) indicating observed failure in response.
     *
     * @param subject Substrates subject
     * @param metadata additional context (should include error details)
     * @return new ServiceSignal with FAIL/RECEIPT
     */
    public static ServiceSignal failed(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.FAILED,
            metadata
        );
    }

    /**
     * Creates a START signal (RELEASE orientation) indicating work execution started.
     *
     * @param subject Substrates subject
     * @param metadata additional context
     * @return new ServiceSignal with START/RELEASE
     */
    public static ServiceSignal start(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.START,
            metadata
        );
    }

    /**
     * Creates a STARTED signal (RECEIPT orientation) indicating observed work start.
     *
     * @param subject Substrates subject
     * @param metadata additional context
     * @return new ServiceSignal with START/RECEIPT
     */
    public static ServiceSignal started(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.STARTED,
            metadata
        );
    }

    /**
     * Creates a STOP signal (RELEASE orientation) indicating work completion.
     *
     * @param subject Substrates subject
     * @param metadata additional context
     * @return new ServiceSignal with STOP/RELEASE
     */
    public static ServiceSignal stop(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.STOP,
            metadata
        );
    }

    /**
     * Creates a STOPPED signal (RECEIPT orientation) indicating observed work completion.
     *
     * @param subject Substrates subject
     * @param metadata additional context
     * @return new ServiceSignal with STOP/RECEIPT
     */
    public static ServiceSignal stopped(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.STOPPED,
            metadata
        );
    }

    /**
     * Creates a RETRY signal indicating a retry attempt.
     *
     * @param subject Substrates subject
     * @param metadata additional context (should include retry count, reason)
     * @return new ServiceSignal with RETRY
     */
    public static ServiceSignal retry(Subject subject, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.RETRY,
            metadata
        );
    }

    // Services.Service interface implementation

    /**
     * @return the semantic Sign of this signal (START, STOP, CALL, SUCCESS, FAIL, etc.)
     */
    public Services.Sign sign() {
        return signal.sign();
    }

    /**
     * @return the Orientation of this signal (RELEASE=self, RECEIPT=other)
     */
    public Services.Orientation orientation() {
        return signal.orientation();
    }

    // Signal interface implementation - Semantic Helpers

    /**
     * Returns the severity level based on the service sign.
     *
     * <p><b>Severity Mapping:</b>
     * <ul>
     *   <li>FAIL, FAILED → ERROR</li>
     *   <li>RETRY → WARNING</li>
     *   <li>SUCCESS, SUCCEEDED, START, STARTED, STOP, STOPPED, CALL, CALLED → INFO</li>
     * </ul>
     */
    @Override
    public Severity severity() {
        return switch (sign()) {
            case FAIL -> Severity.ERROR;
            case RETRY -> Severity.WARNING;
            case SUCCESS, START, STOP, CALL -> Severity.INFO;
            default -> Severity.INFO;
        };
    }

    /**
     * Checks if this service signal requires attention.
     *
     * <p>Requires attention if sign is:
     * FAIL, FAILED, or RETRY
     *
     * @return true if signal indicates a problem
     */
    @Override
    public boolean requiresAttention() {
        return switch (sign()) {
            case FAIL, RETRY -> true;
            default -> false;
        };
    }

    /**
     * Returns a human-readable interpretation of this service signal.
     *
     * <p>Example: "Service FAIL (RELEASE): Service operation failed"
     *
     * @return interpretable description
     */
    @Override
    public String interpret() {
        String signText = switch (sign()) {
            case CALL -> "Service call initiated";
            case START -> "Service execution started";
            case STOP -> "Service execution stopped";
            case SUCCESS -> "Service operation succeeded";
            case FAIL -> "Service operation failed";
            case RETRY -> "Service operation being retried";
            default -> "Service operation: " + sign();
        };

        return String.format(
            "Service %s (%s): %s",
            sign(),
            orientation(),
            signText
        );
    }

    /**
     * Checks if the service operation was successful.
     *
     * @return true if SUCCESS sign
     */
    public boolean isSuccessful() {
        return sign() == Services.Sign.SUCCESS;
    }

    /**
     * Checks if the service operation failed.
     *
     * @return true if FAIL sign
     */
    public boolean isFailed() {
        return sign() == Services.Sign.FAIL;
    }

    /**
     * Checks if this is a self-reported signal (RELEASE orientation).
     *
     * @return true if RELEASE orientation
     */
    public boolean isSelfReported() {
        return orientation() == Services.Orientation.RELEASE;
    }

    /**
     * Checks if this is an observed signal (RECEIPT orientation).
     *
     * @return true if RECEIPT orientation
     */
    public boolean isObserved() {
        return orientation() == Services.Orientation.RECEIPT;
    }

    /**
     * Creates a new ServiceSignal with an updated vector clock.
     *
     * @param newClock the new vector clock
     * @return new ServiceSignal with updated clock
     */
    public ServiceSignal withClock(VectorClock newClock) {
        return new ServiceSignal(id, subject, timestamp, newClock, signal, payload);
    }

    /**
     * Creates a new ServiceSignal with additional payload entries.
     *
     * @param additionalPayload additional metadata to merge
     * @return new ServiceSignal with merged payload
     */
    public ServiceSignal withPayload(Map<String, String> additionalPayload) {
        Map<String, String> merged = new java.util.HashMap<>(payload);
        merged.putAll(additionalPayload);
        return new ServiceSignal(id, subject, timestamp, vectorClock, signal, merged);
    }

    /**
     * Creates a builder for constructing ServiceSignals.
     *
     * @return new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ServiceSignal with fluent API.
     */
    public static class Builder {
        private UUID id = UUID.randomUUID();
        private Subject subject;
        private Instant timestamp = Instant.now();
        private VectorClock vectorClock = VectorClock.empty();
        private Services.Signal signal;
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

        public Builder signal(Services.Signal signal) {
            this.signal = signal;
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

        public Builder call() {
            this.signal = Services.Signal.CALL;
            return this;
        }

        public Builder called() {
            this.signal = Services.Signal.CALLED;
            return this;
        }

        public Builder success() {
            this.signal = Services.Signal.SUCCESS;
            return this;
        }

        public Builder succeeded() {
            this.signal = Services.Signal.SUCCEEDED;
            return this;
        }

        public Builder fail() {
            this.signal = Services.Signal.FAIL;
            return this;
        }

        public Builder failed() {
            this.signal = Services.Signal.FAILED;
            return this;
        }

        public Builder start() {
            this.signal = Services.Signal.START;
            return this;
        }

        public Builder started() {
            this.signal = Services.Signal.STARTED;
            return this;
        }

        public Builder stop() {
            this.signal = Services.Signal.STOP;
            return this;
        }

        public Builder stopped() {
            this.signal = Services.Signal.STOPPED;
            return this;
        }

        public Builder retry() {
            this.signal = Services.Signal.RETRY;
            return this;
        }

        public ServiceSignal build() {
            if (subject == null) {
                throw new IllegalStateException("Subject is required");
            }
            if (signal == null) {
                throw new IllegalStateException("Signal is required");
            }
            return new ServiceSignal(
                id,
                subject,
                timestamp,
                vectorClock,
                signal,
                payload
            );
        }
    }
}
