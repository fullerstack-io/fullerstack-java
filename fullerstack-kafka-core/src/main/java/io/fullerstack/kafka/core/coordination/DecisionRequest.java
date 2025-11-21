package io.fullerstack.kafka.core.coordination;

import io.humainary.substrates.ext.serventis.ext.Situations;

import java.time.Duration;
import java.util.Objects;

/**
 * Represents a decision that requires human input or can auto-execute.
 * <p>
 * Used in SUPERVISED and ADVISORY modes where human can inject approval, veto, or alternative action.
 *
 * @param action action identifier (e.g., "throttle.producer", "pause.consumer")
 * @param urgency urgency level from Situation (NORMAL, WARNING, CRITICAL)
 * @param explanation human-readable explanation of why this action is needed
 * @param impact expected impact of the action
 * @param timeout how long to wait for human response before auto-executing (ADVISORY) or canceling (SUPERVISED)
 */
public record DecisionRequest(
    String action,
    Situations.Sign urgency,
    String explanation,
    String impact,
    Duration timeout
) {
    public DecisionRequest {
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(urgency, "urgency cannot be null");
        Objects.requireNonNull(explanation, "explanation cannot be null");
        Objects.requireNonNull(impact, "impact cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
    }

    /**
     * Create a decision request with standard explanation format.
     */
    public static DecisionRequest of(
        String action,
        Situations.Sign urgency,
        Duration timeout
    ) {
        return new DecisionRequest(
            action,
            urgency,
            generateExplanation(action, urgency),
            generateImpact(action),
            timeout
        );
    }

    private static String generateExplanation(String action, Situations.Sign urgency) {
        return switch (urgency) {
            case CRITICAL -> String.format("CRITICAL: %s requires immediate action", action);
            case WARNING -> String.format("WARNING: %s recommended", action);
            case NORMAL -> String.format("INFO: %s for optimization", action);
        };
    }

    private static String generateImpact(String action) {
        if (action.contains("throttle")) {
            return "Reduced throughput, improved stability";
        } else if (action.contains("pause")) {
            return "Temporary processing halt, prevents overload";
        } else if (action.contains("scale")) {
            return "Increased resource usage, improved capacity";
        } else {
            return "Action-specific impact (see documentation)";
        }
    }
}
