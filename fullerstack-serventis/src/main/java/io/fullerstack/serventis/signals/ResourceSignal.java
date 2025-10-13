package io.fullerstack.serventis.signals;

import io.humainary.modules.serventis.resources.api.Resources;
import io.humainary.substrates.api.Substrates.Subject;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Resource signal for tracking resource acquisition/release lifecycle.
 *
 * <p>Uses the authentic Humainary Resources API for resource management.
 * Each signal captures Sign × units (quantity of resource).
 *
 * <p><b>Semiotic Interpretation:</b>
 * Resource signals represent the lifecycle of resource management - attempts
 * to acquire, grants/denials, timeouts, and releases. The Sign enum provides
 * semantic meaning about resource availability and contention.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Subject brokerConnections = cortex.subject(
 *     cortex.name("kafka.broker.health"),
 *     cortex.name("broker-1.connections")
 * );
 *
 * ResourceSignal signal = ResourceSignal.timeout(
 *     brokerConnections,
 *     5,
 *     Map.of("pool_size", "100", "wait_time_ms", "5000", "utilization", "98%")
 * );
 *
 * if (signal.requiresAttention()) {
 *     logger.error(signal.interpret());
 *     // "Resource TIMEOUT: Resource request timed out - 5 units"
 * }
 * }</pre>
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
 * @param subject Substrates subject (circuit + entity identity)
 * @param timestamp when resource operation occurred
 * @param vectorClock causal ordering clock
 * @param resourceSignal Humainary Resources.Signal (Sign + units)
 * @param payload additional metadata (resource type, pool size, utilization, etc.)
 */
public record ResourceSignal(
    UUID id,
    Subject subject,
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
     * @param subject Substrates subject
     * @param sign resource operation type
     * @param units quantity of resource units involved
     * @param metadata additional context
     * @return new ResourceSignal
     */
    public static ResourceSignal create(
        Subject subject,
        Resources.Sign sign,
        long units,
        Map<String, String> metadata
    ) {
        return new ResourceSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            new ResourceSignalImpl(sign, units),
            metadata
        );
    }

    /**
     * Creates an ATTEMPT signal indicating a non-blocking resource request.
     *
     * @param subject Substrates subject
     * @param units number of resource units requested
     * @param metadata request context (tryAcquire, timeout=0)
     * @return ATTEMPT resource signal
     */
    public static ResourceSignal attempt(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Resources.Sign.ATTEMPT, units, metadata);
    }

    /**
     * Creates an ACQUIRE signal indicating a blocking resource request.
     *
     * @param subject Substrates subject
     * @param units number of resource units requested
     * @param metadata request context (acquire with wait, timeout specified)
     * @return ACQUIRE resource signal
     */
    public static ResourceSignal acquire(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Resources.Sign.ACQUIRE, units, metadata);
    }

    /**
     * Creates a GRANT signal indicating resource request was successful.
     *
     * @param subject Substrates subject
     * @param units number of resource units granted
     * @param metadata grant context (pool size, utilization, wait time)
     * @return GRANT resource signal
     */
    public static ResourceSignal grant(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Resources.Sign.GRANT, units, metadata);
    }

    /**
     * Creates a DENY signal indicating resource request was denied (no capacity).
     *
     * @param subject Substrates subject
     * @param units number of resource units that couldn't be granted
     * @param metadata denial context (pool exhausted, max capacity, current utilization)
     * @return DENY resource signal
     */
    public static ResourceSignal deny(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Resources.Sign.DENY, units, metadata);
    }

    /**
     * Creates a TIMEOUT signal indicating resource request timed out.
     *
     * @param subject Substrates subject
     * @param units number of resource units that couldn't be granted
     * @param metadata timeout context (wait time, timeout threshold, backpressure)
     * @return TIMEOUT resource signal
     */
    public static ResourceSignal timeout(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Resources.Sign.TIMEOUT, units, metadata);
    }

    /**
     * Creates a RELEASE signal indicating resource units were returned.
     *
     * @param subject Substrates subject
     * @param units number of resource units released
     * @param metadata release context (hold duration, pool size after release)
     * @return RELEASE resource signal
     */
    public static ResourceSignal release(Subject subject, long units, Map<String, String> metadata) {
        return create(subject, Resources.Sign.RELEASE, units, metadata);
    }

    // Resources.Resource interface implementation

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

    // Signal interface implementation - Semantic Helpers

    /**
     * Returns the severity level based on the resource sign.
     *
     * <p><b>Severity Mapping:</b>
     * <ul>
     *   <li>TIMEOUT, DENY → ERROR</li>
     *   <li>ATTEMPT, ACQUIRE, GRANT, RELEASE → INFO</li>
     * </ul>
     */
    @Override
    public Severity severity() {
        return switch (sign()) {
            case TIMEOUT, DENY -> Severity.ERROR;
            case ATTEMPT, ACQUIRE, GRANT, RELEASE -> Severity.INFO;
        };
    }

    /**
     * Checks if this resource signal requires attention.
     *
     * <p>Requires attention if sign is:
     * TIMEOUT or DENY (indicating resource contention)
     *
     * @return true if signal indicates a problem
     */
    @Override
    public boolean requiresAttention() {
        return switch (sign()) {
            case TIMEOUT, DENY -> true;
            case ATTEMPT, ACQUIRE, GRANT, RELEASE -> false;
        };
    }

    /**
     * Returns a human-readable interpretation of this resource signal.
     *
     * <p>Example: "Resource TIMEOUT: Resource request timed out - 5 units"
     *
     * @return interpretable description
     */
    @Override
    public String interpret() {
        String signText = switch (sign()) {
            case ATTEMPT -> "Non-blocking resource request attempted";
            case ACQUIRE -> "Blocking resource request initiated";
            case GRANT -> "Resource request granted";
            case DENY -> "Resource request denied (no capacity)";
            case TIMEOUT -> "Resource request timed out";
            case RELEASE -> "Resource units released";
        };

        return String.format(
            "Resource %s: %s - %d units",
            sign(),
            signText,
            units()
        );
    }

    /**
     * Checks if the resource request was successful (granted).
     *
     * @return true if GRANT
     */
    public boolean isGranted() {
        return sign() == Resources.Sign.GRANT;
    }

    /**
     * Checks if the resource request failed (denied or timed out).
     *
     * @return true if DENY or TIMEOUT
     */
    public boolean isFailed() {
        return sign() == Resources.Sign.DENY || sign() == Resources.Sign.TIMEOUT;
    }

    /**
     * Checks if this indicates resource contention.
     *
     * @return true if DENY or TIMEOUT
     */
    public boolean indicatesContention() {
        return sign() == Resources.Sign.DENY || sign() == Resources.Sign.TIMEOUT;
    }

    /**
     * Checks if this is a request signal (before outcome).
     *
     * @return true if ATTEMPT or ACQUIRE
     */
    public boolean isRequest() {
        return sign() == Resources.Sign.ATTEMPT || sign() == Resources.Sign.ACQUIRE;
    }

    /**
     * Checks if this is a blocking request.
     *
     * @return true if ACQUIRE
     */
    public boolean isBlocking() {
        return sign() == Resources.Sign.ACQUIRE;
    }

    /**
     * Checks if this is a non-blocking request.
     *
     * @return true if ATTEMPT
     */
    public boolean isNonBlocking() {
        return sign() == Resources.Sign.ATTEMPT;
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
