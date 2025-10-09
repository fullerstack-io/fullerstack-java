package io.fullerstack.signetics;

import io.humainary.modules.serventis.reporters.api.Reporters;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Reporter signal for situation detection and alerting.
 *
 * <p>Uses the authentic Humainary Reporters API for situational assessment.
 * Each signal has a Situation (operational significance level).
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
 * @param circuit circuit name (e.g., "kafka.situations")
 * @param channel channel name (e.g., "client.producer-123.situation")
 * @param timestamp when situation was detected
 * @param vectorClock causal ordering clock
 * @param situation Humainary Reporters.Situation (NORMAL, WARNING, CRITICAL)
 * @param reason human-readable explanation
 * @param metadata context information (failureCount, windowSize, pattern, etc.)
 */
public record ReporterSignal(
    UUID id,
    String circuit,
    String channel,
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
     * Creates a NORMAL reporter signal.
     *
     * @param subject channel subject (e.g., "client.producer-123.situation")
     * @param reason human-readable explanation
     * @param metadata context metadata
     * @return NORMAL reporter signal
     */
    public static ReporterSignal normal(String subject, String reason, Map<String, String> metadata) {
        return new ReporterSignal(
            UUID.randomUUID(),
            "kafka.situations",
            subject,
            Instant.now(),
            VectorClock.empty(),
            Reporters.Situation.NORMAL,
            reason,
            metadata
        );
    }

    /**
     * Creates a WARNING reporter signal.
     *
     * @param subject channel subject (e.g., "client.producer-123.situation")
     * @param reason human-readable explanation
     * @param metadata context metadata
     * @return WARNING reporter signal
     */
    public static ReporterSignal warning(String subject, String reason, Map<String, String> metadata) {
        return new ReporterSignal(
            UUID.randomUUID(),
            "kafka.situations",
            subject,
            Instant.now(),
            VectorClock.empty(),
            Reporters.Situation.WARNING,
            reason,
            metadata
        );
    }

    /**
     * Creates a CRITICAL reporter signal.
     *
     * @param subject channel subject (e.g., "client.producer-123.situation")
     * @param reason human-readable explanation
     * @param metadata context metadata
     * @return CRITICAL reporter signal
     */
    public static ReporterSignal critical(String subject, String reason, Map<String, String> metadata) {
        return new ReporterSignal(
            UUID.randomUUID(),
            "kafka.situations",
            subject,
            Instant.now(),
            VectorClock.empty(),
            Reporters.Situation.CRITICAL,
            reason,
            metadata
        );
    }
}
