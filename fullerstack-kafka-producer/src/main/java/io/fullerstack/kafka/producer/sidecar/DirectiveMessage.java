package io.fullerstack.kafka.producer.sidecar;

import java.util.List;

/**
 * DIRECTIVE speech act message (REQUEST).
 * <p>
 * Sent when sidecar agent breaches promise and needs help from central platform.
 * Contains request type, description, suggested actions, and urgency level.
 *
 * @param sourceAgent the agent making the request (e.g., "producer-1")
 * @param contextAgent the specific context agent that breached (e.g., "throttle-agent")
 * @param requestType the type of help needed (e.g., "SCALE_RESOURCES")
 * @param description human-readable description of the problem
 * @param suggestedActions list of possible remediation actions
 * @param urgency urgency level ("IMMEDIATE", "SOON", "EVENTUAL")
 * @param timestamp when the request was created (epoch milliseconds)
 * @since 1.0.0
 */
public record DirectiveMessage(
    String sourceAgent,
    String contextAgent,
    String requestType,
    String description,
    List<String> suggestedActions,
    String urgency,
    long timestamp
) {}
