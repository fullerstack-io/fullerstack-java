package io.fullerstack.signetics;

import io.humainary.modules.serventis.services.api.Services;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service signal for producer/consumer request outcomes.
 *
 * <p>Uses the authentic Humainary Services API for signal semantics.
 * Each signal has a Sign (semantic meaning) and Orientation (perspective).
 *
 * <p>Emitted by ClientSensorInterceptor on send/poll completion.
 * Routed through "kafka.client.interactions" circuit → "services" conduit.
 *
 * @param id unique signal identifier
 * @param circuit circuit name (e.g., "kafka.client.interactions")
 * @param channel channel name (e.g., "producer-app1.send")
 * @param timestamp when signal was emitted
 * @param vectorClock causal ordering clock
 * @param signal Humainary Services.Signal (combines Sign + Orientation)
 * @param payload additional metadata
 */
public record ServiceSignal(
    UUID id,
    String circuit,
    String channel,
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
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context
     * @return new ServiceSignal with CALL/RELEASE
     */
    public static ServiceSignal call(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.CALL,
            metadata
        );
    }

    /**
     * Creates a CALLED signal (RECEIPT orientation) indicating observed incoming call.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context
     * @return new ServiceSignal with CALL/RECEIPT
     */
    public static ServiceSignal called(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.CALLED,
            metadata
        );
    }

    /**
     * Creates a SUCCESS signal (RELEASE orientation) indicating self-reported successful completion.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context
     * @return new ServiceSignal with SUCCESS/RELEASE
     */
    public static ServiceSignal success(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.SUCCESS,
            metadata
        );
    }

    /**
     * Creates a SUCCEEDED signal (RECEIPT orientation) indicating observed successful response.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context
     * @return new ServiceSignal with SUCCESS/RECEIPT
     */
    public static ServiceSignal succeeded(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.SUCCEEDED,
            metadata
        );
    }

    /**
     * Creates a FAIL signal (RELEASE orientation) indicating self-reported failure.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context (should include error details)
     * @return new ServiceSignal with FAIL/RELEASE
     */
    public static ServiceSignal fail(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.FAIL,
            metadata
        );
    }

    /**
     * Creates a FAILED signal (RECEIPT orientation) indicating observed failure in response.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context (should include error details)
     * @return new ServiceSignal with FAIL/RECEIPT
     */
    public static ServiceSignal failed(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.FAILED,
            metadata
        );
    }

    /**
     * Creates a START signal (RELEASE orientation) indicating work execution started.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context
     * @return new ServiceSignal with START/RELEASE
     */
    public static ServiceSignal start(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.START,
            metadata
        );
    }

    /**
     * Creates a STARTED signal (RECEIPT orientation) indicating observed work start.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context
     * @return new ServiceSignal with START/RECEIPT
     */
    public static ServiceSignal started(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.STARTED,
            metadata
        );
    }

    /**
     * Creates a STOP signal (RELEASE orientation) indicating work completion.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context
     * @return new ServiceSignal with STOP/RELEASE
     */
    public static ServiceSignal stop(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.STOP,
            metadata
        );
    }

    /**
     * Creates a STOPPED signal (RECEIPT orientation) indicating observed work completion.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param metadata additional context
     * @return new ServiceSignal with STOP/RECEIPT
     */
    public static ServiceSignal stopped(String circuit, String channel, Map<String, String> metadata) {
        return new ServiceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            Services.Signal.STOPPED,
            metadata
        );
    }

    // Convenience methods to access Sign and Orientation

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
}
