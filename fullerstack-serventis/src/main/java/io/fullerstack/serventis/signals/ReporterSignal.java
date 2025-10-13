package io.fullerstack.serventis.signals;

import io.humainary.modules.serventis.reporters.api.Reporters;
import io.humainary.substrates.api.Substrates.Subject;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Reporter signal for situation detection and alerting.
 *
 * <p>Uses the authentic Humainary Reporters API for situational assessment.
 * Each signal has a Situation (operational significance level).
 *
 * <p><b>Semiotic Interpretation:</b>
 * Reporter signals represent high-level situational assessments - the culmination
 * of analyzing multiple lower-level signals to detect patterns, anomalies, and
 * operational issues. The Situation enum provides semantic meaning about the
 * urgency and severity of detected conditions.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Subject clientSituation = cortex.subject(
 *     cortex.name("kafka.situations"),
 *     cortex.name("client.producer-123.situation")
 * );
 *
 * ReporterSignal signal = ReporterSignal.critical(
 *     clientSituation,
 *     "Cascading failure detected: 8/10 operations failed",
 *     Map.of("failureCount", "8", "windowSize", "10", "pattern", "cascading")
 * );
 *
 * if (signal.requiresAttention()) {
 *     logger.error(signal.interpret());
 *     // "Reporter CRITICAL: Cascading failure detected: 8/10 operations failed"
 * }
 * }</pre>
 *
 * <p>Emitted by pattern detectors (FailurePatternDetector, CapacityPlanner) when anomalies,
 * failures, or resource constraints are detected. Routed through "kafka.situations" circuit.
 *
 * <p>Example situations:
 * <ul>
 *   <li>Cascading client failures (>7/10 operations failed)</li>
 *   <li>Correlated broker and client failures</li>
 *   <li>Slow consumer patterns (>75% poll operations empty)</li>
 *   <li>Cluster capacity warnings</li>
 * </ul>
 *
 * @param id unique signal identifier
 * @param subject Substrates subject (circuit + entity identity)
 * @param timestamp when situation was detected
 * @param vectorClock causal ordering clock
 * @param situation Humainary Reporters.Situation (NORMAL, WARNING, CRITICAL)
 * @param reason human-readable explanation
 * @param metadata context information (failureCount, windowSize, pattern, etc.)
 */
public record ReporterSignal(
    UUID id,
    Subject subject,
    Instant timestamp,
    VectorClock vectorClock,
    Reporters.Situation situation,
    String reason,
    Map<String, String> metadata
) implements Signal {

    public ReporterSignal {
        metadata = Map.copyOf(metadata);
    }

    @Override
    public Map<String, String> payload() {
        return metadata;
    }

    /**
     * Creates a reporter signal with the specified situation.
     *
     * @param subject Substrates subject
     * @param situation operational situation level
     * @param reason human-readable explanation
     * @param metadata context information
     * @return new ReporterSignal
     */
    public static ReporterSignal create(
        Subject subject,
        Reporters.Situation situation,
        String reason,
        Map<String, String> metadata
    ) {
        return new ReporterSignal(
            UUID.randomUUID(),
            subject,
            Instant.now(),
            VectorClock.empty(),
            situation,
            reason,
            metadata
        );
    }

    /**
     * Creates a NORMAL reporter signal.
     *
     * @param subject Substrates subject
     * @param reason human-readable explanation
     * @param metadata context metadata
     * @return NORMAL reporter signal
     */
    public static ReporterSignal normal(Subject subject, String reason, Map<String, String> metadata) {
        return create(subject, Reporters.Situation.NORMAL, reason, metadata);
    }

    /**
     * Creates a WARNING reporter signal.
     *
     * @param subject Substrates subject
     * @param reason human-readable explanation
     * @param metadata context metadata
     * @return WARNING reporter signal
     */
    public static ReporterSignal warning(Subject subject, String reason, Map<String, String> metadata) {
        return create(subject, Reporters.Situation.WARNING, reason, metadata);
    }

    /**
     * Creates a CRITICAL reporter signal.
     *
     * @param subject Substrates subject
     * @param reason human-readable explanation
     * @param metadata context metadata
     * @return CRITICAL reporter signal
     */
    public static ReporterSignal critical(Subject subject, String reason, Map<String, String> metadata) {
        return create(subject, Reporters.Situation.CRITICAL, reason, metadata);
    }

    // Reporters.Reporter interface implementation

    /**
     * @return the situation level (NORMAL, WARNING, CRITICAL)
     */
    public Reporters.Situation situation() {
        return situation;
    }

    // Signal interface implementation - Semantic Helpers

    /**
     * Returns the severity level based on the reporter situation.
     *
     * <p><b>Severity Mapping:</b>
     * <ul>
     *   <li>CRITICAL → CRITICAL</li>
     *   <li>WARNING → WARNING</li>
     *   <li>NORMAL → INFO</li>
     * </ul>
     */
    @Override
    public Severity severity() {
        return switch (situation) {
            case CRITICAL -> Severity.CRITICAL;
            case WARNING -> Severity.WARNING;
            case NORMAL -> Severity.INFO;
        };
    }

    /**
     * Checks if this reporter signal requires attention.
     *
     * <p>Requires attention if situation is:
     * WARNING or CRITICAL
     *
     * @return true if signal indicates a problem
     */
    @Override
    public boolean requiresAttention() {
        return switch (situation) {
            case WARNING, CRITICAL -> true;
            case NORMAL -> false;
        };
    }

    /**
     * Returns a human-readable interpretation of this reporter signal.
     *
     * <p>Example: "Reporter CRITICAL: Cascading failure detected: 8/10 operations failed"
     *
     * @return interpretable description
     */
    @Override
    public String interpret() {
        return String.format(
            "Reporter %s: %s",
            situation,
            reason
        );
    }

    /**
     * Checks if the situation is normal (no issues).
     *
     * @return true if NORMAL
     */
    public boolean isNormal() {
        return situation == Reporters.Situation.NORMAL;
    }

    /**
     * Checks if the situation is a warning.
     *
     * @return true if WARNING
     */
    public boolean isWarning() {
        return situation == Reporters.Situation.WARNING;
    }

    /**
     * Checks if the situation is critical.
     *
     * @return true if CRITICAL
     */
    public boolean isCritical() {
        return situation == Reporters.Situation.CRITICAL;
    }

    /**
     * Returns the human-readable reason for this situation.
     *
     * @return reason text
     */
    public String getReason() {
        return reason;
    }
}
