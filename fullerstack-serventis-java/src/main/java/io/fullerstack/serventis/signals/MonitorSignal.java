package io.fullerstack.serventis.signals;

import io.humainary.modules.serventis.monitors.api.Monitors;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Monitor signal for broker health metrics (JVM heap, GC, disk, etc.).
 *
 * <p>Uses the authentic Humainary Monitors API for condition assessment.
 * Each status has a Condition (operational state) and Confidence (certainty level).
 *
 * <p>Emitted by BrokerSensorAgent every 10 seconds with broker health status.
 * Routed through "kafka.broker.health" circuit → "monitors" conduit.
 *
 * @param id unique signal identifier
 * @param circuit circuit name (e.g., "kafka.broker.health")
 * @param channel channel name (e.g., "broker-1.jvm.heap")
 * @param timestamp when signal was emitted
 * @param vectorClock causal ordering clock
 * @param status Humainary Monitors.Status (Condition + Confidence)
 * @param payload additional metadata
 */
public record MonitorSignal(
    UUID id,
    String circuit,
    String channel,
    Instant timestamp,
    VectorClock vectorClock,
    Monitors.Status status,
    Map<String, String> payload
) implements Signal {

    public MonitorSignal {
        payload = Map.copyOf(payload);
    }

    /**
     * Creates a monitor signal with the specified condition and confidence.
     *
     * @param circuit circuit name
     * @param channel channel name (subject)
     * @param condition operational condition
     * @param confidence certainty level
     * @param metadata additional context
     * @return new MonitorSignal
     */
    public static MonitorSignal create(
        String circuit,
        String channel,
        Monitors.Condition condition,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return new MonitorSignal(
            UUID.randomUUID(),
            circuit,
            channel,
            Instant.now(),
            VectorClock.empty(),
            new MonitorStatusImpl(condition, confidence),
            metadata
        );
    }

    /**
     * Creates a STABLE/CONFIRMED monitor signal.
     *
     * @param circuit circuit name
     * @param channel channel name
     * @param metadata additional context
     * @return STABLE/CONFIRMED monitor signal
     */
    public static MonitorSignal stable(String circuit, String channel, Map<String, String> metadata) {
        return create(circuit, channel, Monitors.Condition.STABLE, Monitors.Confidence.CONFIRMED, metadata);
    }

    /**
     * Creates a DEGRADED monitor signal.
     *
     * @param circuit circuit name
     * @param channel channel name
     * @param confidence certainty level
     * @param metadata additional context
     * @return DEGRADED monitor signal
     */
    public static MonitorSignal degraded(
        String circuit,
        String channel,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return create(circuit, channel, Monitors.Condition.DEGRADED, confidence, metadata);
    }

    /**
     * Creates a DEFECTIVE monitor signal.
     *
     * @param circuit circuit name
     * @param channel channel name
     * @param confidence certainty level
     * @param metadata additional context
     * @return DEFECTIVE monitor signal
     */
    public static MonitorSignal defective(
        String circuit,
        String channel,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return create(circuit, channel, Monitors.Condition.DEFECTIVE, confidence, metadata);
    }

    /**
     * Creates a DOWN monitor signal.
     *
     * @param circuit circuit name
     * @param channel channel name
     * @param confidence certainty level
     * @param metadata additional context
     * @return DOWN monitor signal
     */
    public static MonitorSignal down(
        String circuit,
        String channel,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return create(circuit, channel, Monitors.Condition.DOWN, confidence, metadata);
    }

    // Convenience methods

    /**
     * @return the operational condition
     */
    public Monitors.Condition condition() {
        return status.condition();
    }

    /**
     * @return the statistical certainty
     */
    public Monitors.Confidence confidence() {
        return status.confidence();
    }

    /**
     * Simple implementation of Monitors.Status
     */
    record MonitorStatusImpl(
        Monitors.Condition condition,
        Monitors.Confidence confidence
    ) implements Monitors.Status {
    }
}
