package io.fullerstack.kafka.producer.sidecar;

import java.util.Map;

/**
 * ASSERTIVE speech act message (REPORT/INFORM).
 * <p>
 * Sent when sidecar agent fulfills promise but event is notable enough for audit trail.
 * Represents Level 2 communication (0.9% of events) - informational, no action required.
 *
 * @param sourceAgent the agent providing the information (e.g., "producer-sidecar-1")
 * @param contextAgent the specific context agent (e.g., "throttle-agent")
 * @param information the information being reported
 * @param timestamp when the message was created (epoch milliseconds)
 * @param metadata optional metadata (type, jmxEndpoint, hostname, version, etc.) - can be null
 * @since 1.0.0
 */
public record InformMessage(
    String sourceAgent,
    String contextAgent,
    String information,
    long timestamp,
    Map<String, Object> metadata
) {
    /**
     * Constructor for backward compatibility (no metadata).
     */
    public InformMessage(String sourceAgent, String contextAgent, String information, long timestamp) {
        this(sourceAgent, contextAgent, information, timestamp, null);
    }
}
