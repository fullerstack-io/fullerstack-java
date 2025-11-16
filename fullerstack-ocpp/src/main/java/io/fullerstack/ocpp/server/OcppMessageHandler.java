package io.fullerstack.ocpp.server;

/**
 * Handler interface for processing incoming OCPP messages.
 * Implementations will translate OCPP messages into Substrates signals.
 */
@FunctionalInterface
public interface OcppMessageHandler {
    /**
     * Handle an incoming OCPP message from a charger.
     *
     * @param message the OCPP message to handle
     */
    void handleMessage(OcppMessage message);
}
