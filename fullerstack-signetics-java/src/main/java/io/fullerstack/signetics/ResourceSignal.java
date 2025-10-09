package io.fullerstack.signetics;

import io.humainary.modules.serventis.resources.api.Resources;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Resource signal for tracking resource acquisition/release lifecycle.
 *
 * <p>Uses the authentic Humainary Resources API for resource management.
 * Each signal captures Sign × units (quantity of resource).
 *
 * <p>Tracks critical Kafka resources:
 * <ul>
 *   <li>Connection pools - ATTEMPT/ACQUIRE/GRANT/DENY/TIMEOUT/RELEASE</li>
 *   <li>Memory buffers - Producer/consumer buffer allocation</li>
 *   <li>Thread pools - Network/IO thread allocation</li>
 *   <li>Network quotas - Bandwidth and request rate limits</li>
 * </ul>
 *
 * <p>Lifecycle flow: ATTEMPT/ACQUIRE → GRANT/DENY/TIMEOUT → RELEASE
 *
 * <p>Emitted by BrokerSensorAgent and ClientSensorAgent for resource tracking.
 * Routed through "kafka.broker.health" circuit → "resources" conduit.
 *
 * @param id unique signal identifier
 * @param circuit circuit name (e.g., "kafka.broker.health")
 * @param channel channel name (e.g., "broker-1.connections")
 * @param timestamp when resource operation occurred
 * @param vectorClock causal ordering clock
 * @param resourceSignal Humainary Resources.Signal (Sign + units)
 * @param payload additional metadata (resource type, pool size, utilization, etc.)
 */
public record ResourceSignal(
    UUID id,
    String circuit,
    String channel,
    Instant timestamp,
    VectorClock vectorClock,
    Resources.Signal resourceSignal,
    Map<String, String> payload
) implements Signal {

    public ResourceSignal {
        payload = Map.copyOf(payload);
    }

    /**
     * Creates a resource signal with the specified sign and units.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param sign resource operation type
     * @param units quantity of resource units involved
     * @param metadata additional context
     * @return new ResourceSignal
     */
    public static ResourceSignal create(
        String circuit,
        String channel,
        Resources.Sign sign,
        long units,
        Map<String, String> metadata
    ) {
        return new ResourceSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            new ResourceSignalImpl(sign, units),
            metadata
        );
    }

    /**
     * Creates an ATTEMPT signal indicating a non-blocking resource request.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of resource units requested
     * @param metadata request context (tryAcquire, timeout=0)
     * @return ATTEMPT resource signal
     */
    public static ResourceSignal attempt(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Resources.Sign.ATTEMPT, units, metadata);
    }

    /**
     * Creates an ACQUIRE signal indicating a blocking resource request.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of resource units requested
     * @param metadata request context (acquire with wait, timeout specified)
     * @return ACQUIRE resource signal
     */
    public static ResourceSignal acquire(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Resources.Sign.ACQUIRE, units, metadata);
    }

    /**
     * Creates a GRANT signal indicating resource request was successful.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of resource units granted
     * @param metadata grant context (pool size, utilization, wait time)
     * @return GRANT resource signal
     */
    public static ResourceSignal grant(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Resources.Sign.GRANT, units, metadata);
    }

    /**
     * Creates a DENY signal indicating resource request was denied (no capacity).
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of resource units that couldn't be granted
     * @param metadata denial context (pool exhausted, max capacity, current utilization)
     * @return DENY resource signal
     */
    public static ResourceSignal deny(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Resources.Sign.DENY, units, metadata);
    }

    /**
     * Creates a TIMEOUT signal indicating resource request timed out.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of resource units that couldn't be granted
     * @param metadata timeout context (wait time, timeout threshold, backpressure)
     * @return TIMEOUT resource signal
     */
    public static ResourceSignal timeout(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Resources.Sign.TIMEOUT, units, metadata);
    }

    /**
     * Creates a RELEASE signal indicating resource units were returned.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param units number of resource units released
     * @param metadata release context (hold duration, pool size after release)
     * @return RELEASE resource signal
     */
    public static ResourceSignal release(String circuit, String channel, long units, Map<String, String> metadata) {
        return create(circuit, channel, Resources.Sign.RELEASE, units, metadata);
    }

    // Convenience methods

    /**
     * @return the resource operation sign (ATTEMPT, ACQUIRE, GRANT, DENY, TIMEOUT, RELEASE)
     */
    public Resources.Sign sign() {
        return resourceSignal.sign();
    }

    /**
     * @return the number of resource units involved in this operation
     */
    public long units() {
        return resourceSignal.units();
    }

    /**
     * Simple implementation of Resources.Signal interface.
     */
    record ResourceSignalImpl(
        Resources.Sign sign,
        long units
    ) implements Resources.Signal {
    }
}
