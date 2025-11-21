package io.fullerstack.ocpp.server;

import io.fullerstack.ocpp.observers.CommandVerificationObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps an OcppCommandExecutor to enable command verification and closed-loop feedback.
 * <p>
 * This wrapper registers commands with CommandVerificationObserver before executing them,
 * enabling the system to track command success/failure and close the feedback loop.
 * <p>
 * <b>Closed-Loop Pattern:</b>
 * <pre>
 * Agent → CommandTrackingExecutor → Register with observer → Execute command
 *                                       ↓
 *                          CommandVerificationObserver tracks pending
 *                                       ↓
 *                          OCPP response received → Verify → Emit signal
 *                                       ↓
 *                          Situation re-assesses → Potential re-action
 * </pre>
 *
 * @see CommandVerificationObserver
 * @see io.fullerstack.ocpp.agents.ChargerDisableAgent
 */
public class CommandTrackingExecutor implements OcppCommandExecutor {
    private static final Logger logger = LoggerFactory.getLogger(CommandTrackingExecutor.class);

    private final OcppCommandExecutor delegate;
    private final CommandVerificationObserver verificationObserver;

    public CommandTrackingExecutor(
        OcppCommandExecutor delegate,
        CommandVerificationObserver verificationObserver
    ) {
        this.delegate = delegate;
        this.verificationObserver = verificationObserver;
        logger.info("CommandTrackingExecutor initialized - enabling closed-loop command verification");
    }

    @Override
    public boolean executeCommand(OcppCommand command) {
        // Register command for verification BEFORE executing
        verificationObserver.registerCommand(command);
        logger.debug("Registered command for verification: {} on charger {}",
            command.getClass().getSimpleName(), command.chargerId());

        // Execute the actual command
        boolean result = delegate.executeCommand(command);

        if (result) {
            logger.debug("Command execution initiated: {} (awaiting verification)",
                command.commandId());
        } else {
            logger.warn("Command execution failed immediately: {}", command.commandId());
        }

        return result;
    }
}
