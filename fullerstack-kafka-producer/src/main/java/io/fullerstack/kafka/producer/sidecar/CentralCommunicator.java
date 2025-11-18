package io.fullerstack.kafka.producer.sidecar;

/**
 * Communication interface for sending speech acts to central platform.
 * <p>
 * Implements the outbound side of distributed agent coordination using Speech Act Theory.
 * Sidecar agents use this to send directives (requests) and assertives (reports) when
 * they cannot fulfill promises locally.
 *
 * @see AgentCoordinationBridge
 * @since 1.0.0
 */
public interface CentralCommunicator {

    /**
     * Send DIRECTIVE speech act (REQUEST for help).
     * <p>
     * Used when agent breaches promise and needs external assistance.
     *
     * @param message the directive message containing request details
     */
    void sendDirective(DirectiveMessage message);

    /**
     * Send ASSERTIVE speech act (REPORT/INFORM).
     * <p>
     * Used for audit trail when agent fulfills promise but event is notable.
     *
     * @param message the inform message containing information
     */
    void sendInform(InformMessage message);

    /**
     * Close communicator and release resources.
     */
    void close();
}
