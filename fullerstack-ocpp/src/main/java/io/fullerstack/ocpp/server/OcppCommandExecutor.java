package io.fullerstack.ocpp.server;

/**
 * Executor interface for sending OCPP commands to chargers.
 * Implementations will translate semantic commands into OCPP protocol messages.
 */
@FunctionalInterface
public interface OcppCommandExecutor {
    /**
     * Execute a command by sending it to the target charger.
     *
     * @param command the command to execute
     * @return true if command was sent successfully, false otherwise
     */
    boolean executeCommand(OcppCommand command);
}
