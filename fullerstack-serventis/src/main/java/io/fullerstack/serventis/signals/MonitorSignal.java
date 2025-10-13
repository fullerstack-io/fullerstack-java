package io.fullerstack.serventis.signals;

import io.humainary.modules.serventis.monitors.api.Monitors;
import io.humainary.substrates.api.Substrates.Subject;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Monitor signal representing operational health condition.
 *
 * <p>Uses the authentic Humainary Monitors API for condition assessment.
 * Each monitor has a Condition (operational state) and Confidence (certainty level).
 *
 * <p><b>Semiotic Interpretation:</b>
 * Monitor signals are interpreted as health signs that indicate the operational
 * status of system components. The condition enum provides semantic meaning
 * (STABLE, DEGRADED, DEFECTIVE, DOWN) rather than just numeric thresholds.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Subject brokerHeap = cortex.subject(
 *     cortex.name("kafka.broker.health"),
 *     cortex.name("broker-1.jvm.heap")
 * );
 *
 * MonitorSignal signal = MonitorSignal.degraded(
 *     brokerHeap,
 *     Monitors.Confidence.SUSPECTED,
 *     Map.of("heap_usage_percent", "87", "gc_time_ms", "450")
 * );
 *
 * if (signal.requiresAttention()) {
 *     logger.warn(signal.interpret());
 *     // "Monitor DEGRADED (SUSPECTED): Operational degradation detected"
 * }
 * }</pre>
 *
 * @param id unique signal identifier
 * @param subject Substrates subject (circuit + entity identity)
 * @param timestamp when signal was emitted
 * @param vectorClock causal ordering clock
 * @param status Humainary Monitors.Status (Condition + Confidence)
 * @param payload additional metadata
 */
public record MonitorSignal(
    UUID id,
    Subject subject,
    Instant timestamp,
    VectorClock vectorClock,
    Monitors.Status status,
    Map<String, String> payload
) implements Signal {

    public MonitorSignal {
        payload = Map.copyOf(payload);
    }

    // Factory Methods

    /**
     * Creates a monitor signal with the specified condition and confidence.
     *
     * @param subject Substrates subject
     * @param condition operational condition
     * @param confidence certainty level
     * @param metadata additional context
     * @return new MonitorSignal
     */
    public static MonitorSignal create(
        Subject subject,
        Monitors.Condition condition,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return new MonitorSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            new MonitorStatusImpl(condition, confidence),
            metadata
        );
    }

    /**
     * Creates a STABLE/CONFIRMED monitor signal.
     *
     * @param subject Substrates subject
     * @param metadata additional context
     * @return STABLE/CONFIRMED monitor signal
     */
    public static MonitorSignal stable(Subject subject, Map<String, String> metadata) {
        return create(subject, Monitors.Condition.STABLE, Monitors.Confidence.CONFIRMED, metadata);
    }

    /**
     * Creates a CONVERGING monitor signal (trending toward stability).
     *
     * @param subject Substrates subject
     * @param confidence certainty level
     * @param metadata additional context
     * @return CONVERGING monitor signal
     */
    public static MonitorSignal converging(
        Subject subject,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return create(subject, Monitors.Condition.CONVERGING, confidence, metadata);
    }

    /**
     * Creates a DIVERGING monitor signal (trending away from stability).
     *
     * @param subject Substrates subject
     * @param confidence certainty level
     * @param metadata additional context
     * @return DIVERGING monitor signal
     */
    public static MonitorSignal diverging(
        Subject subject,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return create(subject, Monitors.Condition.DIVERGING, confidence, metadata);
    }

    /**
     * Creates an ERRATIC monitor signal (unstable behavior).
     *
     * @param subject Substrates subject
     * @param confidence certainty level
     * @param metadata additional context
     * @return ERRATIC monitor signal
     */
    public static MonitorSignal erratic(
        Subject subject,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return create(subject, Monitors.Condition.ERRATIC, confidence, metadata);
    }

    /**
     * Creates a DEGRADED monitor signal.
     *
     * @param subject Substrates subject
     * @param confidence certainty level
     * @param metadata additional context
     * @return DEGRADED monitor signal
     */
    public static MonitorSignal degraded(
        Subject subject,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return create(subject, Monitors.Condition.DEGRADED, confidence, metadata);
    }

    /**
     * Creates a DEFECTIVE monitor signal.
     *
     * @param subject Substrates subject
     * @param confidence certainty level
     * @param metadata additional context
     * @return DEFECTIVE monitor signal
     */
    public static MonitorSignal defective(
        Subject subject,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return create(subject, Monitors.Condition.DEFECTIVE, confidence, metadata);
    }

    /**
     * Creates a DOWN monitor signal.
     *
     * @param subject Substrates subject
     * @param confidence certainty level
     * @param metadata additional context
     * @return DOWN monitor signal
     */
    public static MonitorSignal down(
        Subject subject,
        Monitors.Confidence confidence,
        Map<String, String> metadata
    ) {
        return create(subject, Monitors.Condition.DOWN, confidence, metadata);
    }

    // Monitors.Monitor interface implementation

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

    // Signal interface implementation - Semantic Helpers

    /**
     * Returns the severity level based on the monitor condition.
     *
     * <p><b>Severity Mapping:</b>
     * <ul>
     *   <li>DOWN, DEFECTIVE → CRITICAL</li>
     *   <li>DEGRADED → ERROR</li>
     *   <li>ERRATIC, DIVERGING → WARNING</li>
     *   <li>CONVERGING, STABLE → INFO</li>
     * </ul>
     */
    @Override
    public Severity severity() {
        return switch (condition()) {
            case DOWN, DEFECTIVE -> Severity.CRITICAL;
            case DEGRADED -> Severity.ERROR;
            case ERRATIC, DIVERGING -> Severity.WARNING;
            case CONVERGING, STABLE -> Severity.INFO;
        };
    }

    /**
     * Checks if this monitor signal requires attention.
     *
     * <p>Requires attention if condition is:
     * DEGRADED, DEFECTIVE, DOWN, ERRATIC, or DIVERGING
     *
     * @return true if condition warrants attention
     */
    @Override
    public boolean requiresAttention() {
        return switch (condition()) {
            case DEGRADED, DEFECTIVE, DOWN, ERRATIC, DIVERGING -> true;
            case CONVERGING, STABLE -> false;
        };
    }

    /**
     * Returns a human-readable interpretation of this monitor signal.
     *
     * <p>Example: "Monitor DEGRADED (SUSPECTED): Operational degradation detected"
     *
     * @return interpretable description
     */
    @Override
    public String interpret() {
        String conditionText = switch (condition()) {
            case STABLE -> "Operational stability confirmed";
            case CONVERGING -> "System converging toward stability";
            case DIVERGING -> "System diverging from stability";
            case ERRATIC -> "Erratic behavior detected";
            case DEGRADED -> "Operational degradation detected";
            case DEFECTIVE -> "Defective operation detected";
            case DOWN -> "Component is down";
        };

        return String.format(
            "Monitor %s (%s): %s",
            condition(),
            confidence(),
            conditionText
        );
    }

    /**
     * Checks if the monitor is healthy (STABLE or CONVERGING).
     *
     * @return true if healthy
     */
    public boolean isHealthy() {
        return condition() == Monitors.Condition.STABLE ||
               condition() == Monitors.Condition.CONVERGING;
    }

    /**
     * Checks if the monitor indicates a critical failure (DOWN or DEFECTIVE).
     *
     * @return true if critical
     */
    public boolean isCritical() {
        return condition() == Monitors.Condition.DOWN ||
               condition() == Monitors.Condition.DEFECTIVE;
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
