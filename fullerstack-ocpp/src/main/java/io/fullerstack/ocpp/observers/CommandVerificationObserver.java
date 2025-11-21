package io.fullerstack.ocpp.observers;

import io.fullerstack.ocpp.server.OcppCommand;
import io.fullerstack.ocpp.server.OcppMessage;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.monitors.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Layer 1.5 (Observe - Command Verification): Closes the feedback loop by verifying command execution.
 * <p>
 * This observer tracks commands sent to chargers and verifies they were executed by correlating
 * with OCPP response messages. This transforms open-loop control into closed-loop adaptive control.
 * <p>
 * <b>Signal-First Pattern - Command Verification:</b>
 * <pre>
 * Agent sends ChangeAvailability(Inoperative)
 *   → CommandVerificationObserver tracks pending command
 *   → Waits for StatusNotification(Unavailable)
 *   → If received within 30s: Emit STABLE (command worked)
 *   → If timeout: Emit DEFECTIVE (charger not responding to commands)
 *   → Situation re-assesses → Potentially triggers different action
 * </pre>
 *
 * <h3>Closed-Loop Feedback:</h3>
 * <table border="1">
 * <tr><th>Command</th><th>Expected Response</th><th>Verification Signal</th></tr>
 * <tr><td>ChangeAvailability(Inoperative)</td><td>StatusNotification(Unavailable)</td><td>STABLE if confirmed</td></tr>
 * <tr><td>ChangeAvailability(Operative)</td><td>StatusNotification(Available)</td><td>STABLE if confirmed</td></tr>
 * <tr><td>RemoteStopTransaction</td><td>StopTransaction</td><td>STABLE if confirmed</td></tr>
 * <tr><td>Any command</td><td>(timeout 30s)</td><td>DEFECTIVE (not responding)</td></tr>
 * </table>
 *
 * <h3>Adaptive Re-Action Example:</h3>
 * <pre>
 * 1. Charger reports GroundFailure → Monitor.DOWN
 * 2. Situation assesses CRITICAL
 * 3. Agent sends ChangeAvailability(Inoperative)
 * 4a. SUCCESS PATH:
 *     → Charger responds with StatusNotification(Unavailable)
 *     → CommandVerificationObserver emits STABLE
 *     → Situation re-assesses as NORMAL (crisis resolved)
 * 4b. FAILURE PATH:
 *     → 30s timeout, no response
 *     → CommandVerificationObserver emits DEFECTIVE
 *     → Situation re-assesses as CRITICAL (charger ignoring commands)
 *     → Different agent action (e.g., alert operator, circuit breaker)
 * </pre>
 *
 * @see OcppMessageObserver
 * @see io.fullerstack.ocpp.agents.ChargerDisableAgent
 */
public class CommandVerificationObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(CommandVerificationObserver.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SLOW_RESPONSE_THRESHOLD = Duration.ofSeconds(10);

    private final Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private final Map<String, PendingCommand> pendingCommands;
    private final ScheduledExecutorService timeoutScheduler;

    /**
     * Represents a command awaiting verification.
     */
    private static class PendingCommand {
        final String chargerId;
        final OcppCommand command;
        final Instant sentAt;
        final String expectedMessageType;  // e.g., "StatusNotification", "StopTransaction"

        PendingCommand(String chargerId, OcppCommand command, String expectedMessageType) {
            this.chargerId = chargerId;
            this.command = command;
            this.sentAt = Instant.now();
            this.expectedMessageType = expectedMessageType;
        }

        boolean isTimedOut() {
            return Duration.between(sentAt, Instant.now()).compareTo(COMMAND_TIMEOUT) > 0;
        }

        boolean isSlowResponse() {
            return Duration.between(sentAt, Instant.now()).compareTo(SLOW_RESPONSE_THRESHOLD) > 0;
        }
    }

    public CommandVerificationObserver(Conduit<Monitors.Monitor, Monitors.Sign> monitors) {
        this.monitors = monitors;
        this.pendingCommands = new ConcurrentHashMap<>();
        this.timeoutScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "command-verification-timeout");
            t.setDaemon(true);
            return t;
        });

        // Start timeout checker
        startTimeoutChecker();

        logger.info("CommandVerificationObserver initialized - closing feedback loop");
    }

    /**
     * Register a command that was sent, to track its verification.
     *
     * @param command the command sent to charger
     */
    public void registerCommand(OcppCommand command) {
        String expectedMessageType = determineExpectedResponse(command);
        String commandKey = generateCommandKey(command);

        PendingCommand pending = new PendingCommand(
            command.chargerId(),
            command,
            expectedMessageType
        );

        pendingCommands.put(commandKey, pending);

        logger.debug("Registered command for verification: {} (expecting: {})",
            commandKey, expectedMessageType);
    }

    /**
     * Verify an incoming OCPP message against pending commands.
     * This closes the feedback loop.
     *
     * @param message the OCPP message received from charger
     */
    public void verifyMessage(OcppMessage message) {
        String chargerId = message.chargerId();

        // Find matching pending commands for this charger
        pendingCommands.entrySet().stream()
            .filter(entry -> entry.getValue().chargerId.equals(chargerId))
            .filter(entry -> matchesExpectedResponse(entry.getValue(), message))
            .forEach(entry -> {
                PendingCommand pending = entry.getValue();
                String commandKey = entry.getKey();

                // Command verified successfully
                Duration responseTime = Duration.between(pending.sentAt, Instant.now());

                if (pending.isSlowResponse()) {
                    logger.warn("Command verified with SLOW response: {} (took {}ms)",
                        commandKey, responseTime.toMillis());
                    emitDegraded(chargerId, "command-response", "slow response: " + responseTime.toMillis() + "ms");
                } else {
                    logger.info("Command verified successfully: {} (took {}ms)",
                        commandKey, responseTime.toMillis());
                    emitStable(chargerId, "command-response");
                }

                // Remove from pending
                pendingCommands.remove(commandKey);
            });
    }

    /**
     * Check for timed-out commands periodically.
     */
    private void startTimeoutChecker() {
        timeoutScheduler.scheduleAtFixedRate(() -> {
            try {
                checkTimeouts();
            } catch (Exception e) {
                logger.error("Error checking command timeouts", e);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Check for commands that have timed out.
     */
    private void checkTimeouts() {
        pendingCommands.entrySet().removeIf(entry -> {
            PendingCommand pending = entry.getValue();
            String commandKey = entry.getKey();

            if (pending.isTimedOut()) {
                logger.error("Command TIMEOUT: {} - charger not responding to commands", commandKey);

                // Emit DEFECTIVE signal - charger is not responding to commands
                emitDefective(pending.chargerId, "command-response",
                    "timeout: no response after " + COMMAND_TIMEOUT.getSeconds() + "s");

                return true;  // Remove from pending
            }

            return false;
        });
    }

    /**
     * Determine what OCPP message type we expect in response to a command.
     */
    private String determineExpectedResponse(OcppCommand command) {
        return switch (command) {
            case OcppCommand.ChangeAvailability ca -> "StatusNotification";
            case OcppCommand.RemoteStopTransaction rst -> "StopTransaction";
            case OcppCommand.RemoteStartTransaction rst -> "StartTransaction";
            case OcppCommand.UnlockConnector uc -> "StatusNotification";
            case OcppCommand.Reset r -> "BootNotification";  // After reset, charger reboots
            // Smart Charging commands - these have synchronous confirmation responses
            // rather than async message responses, so verification is immediate
            case OcppCommand.SetChargingProfile scp -> "None";  // Synchronous confirmation only
            case OcppCommand.ClearChargingProfile ccp -> "None";  // Synchronous confirmation only
            case OcppCommand.GetCompositeSchedule gcs -> "None";  // Response is synchronous
            default -> "Unknown";
        };
    }

    /**
     * Check if an OCPP message matches the expected response for a pending command.
     */
    private boolean matchesExpectedResponse(PendingCommand pending, OcppMessage message) {
        // Check message type matches
        String messageType = message.getClass().getSimpleName();
        if (!pending.expectedMessageType.equals(messageType)) {
            return false;
        }

        // Additional command-specific validation
        return switch (pending.command) {
            case OcppCommand.ChangeAvailability ca -> {
                if (message instanceof OcppMessage.StatusNotification sn) {
                    String expectedStatus = ca.type().equals("Inoperative") ? "Unavailable" : "Available";
                    yield sn.status().equals(expectedStatus) && sn.connectorId() == ca.connectorId();
                }
                yield false;
            }
            case OcppCommand.RemoteStopTransaction rst -> {
                if (message instanceof OcppMessage.StopTransaction st) {
                    yield st.transactionId().equals(rst.transactionId());
                }
                yield false;
            }
            default -> true;  // Simple type match for other commands
        };
    }

    /**
     * Generate unique key for command tracking.
     */
    private String generateCommandKey(OcppCommand command) {
        return command.chargerId() + ":" + command.commandId();
    }

    /**
     * Emit STABLE monitor signal - command verified successfully.
     */
    private void emitStable(String chargerId, String aspect) {
        Monitors.Monitor monitor = monitors.percept(
            cortex().name(chargerId).name(aspect)
        );
        monitor.stable(Monitors.Dimension.CONFIRMED);
        logger.debug("Emitted STABLE for {}.{}", chargerId, aspect);
    }

    /**
     * Emit DEGRADED monitor signal - command response was slow.
     */
    private void emitDegraded(String chargerId, String aspect, String reason) {
        Monitors.Monitor monitor = monitors.percept(
            cortex().name(chargerId).name(aspect)
        );
        monitor.degraded(Monitors.Dimension.MEASURED);
        logger.warn("Emitted DEGRADED for {}.{}: {}", chargerId, aspect, reason);
    }

    /**
     * Emit DEFECTIVE monitor signal - command timeout (charger not responding).
     */
    private void emitDefective(String chargerId, String aspect, String reason) {
        Monitors.Monitor monitor = monitors.percept(
            cortex().name(chargerId).name(aspect)
        );
        monitor.defective(Monitors.Dimension.CONFIRMED);
        logger.error("Emitted DEFECTIVE for {}.{}: {}", chargerId, aspect, reason);
    }

    /**
     * Get count of pending commands (for monitoring/testing).
     */
    public int getPendingCommandCount() {
        return pendingCommands.size();
    }

    @Override
    public void close() {
        logger.info("Closing CommandVerificationObserver");
        timeoutScheduler.shutdown();
        try {
            if (!timeoutScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timeoutScheduler.shutdownNow();
        }
        pendingCommands.clear();
        logger.info("CommandVerificationObserver closed");
    }
}
