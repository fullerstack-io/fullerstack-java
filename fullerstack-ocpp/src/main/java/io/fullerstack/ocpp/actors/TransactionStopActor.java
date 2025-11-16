package io.fullerstack.ocpp.actors;

import io.fullerstack.ocpp.server.OcppCommand;
import io.fullerstack.ocpp.server.OcppCommandExecutor;
import io.humainary.substrates.Channel;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.Name;
import io.humainary.substrates.Registrar;
import io.humainary.substrates.Subject;
import io.humainary.substrates.Subscription;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Actor that remotely stops transactions when charger health is critical.
 * <p>
 * Semantic Command: TRANSACTION_REMOTE_STOP
 * - Subscribes to ChargerHealthReporter CRITICAL signals
 * - Executes RemoteStopTransaction OCPP command
 * - Emits DELIVER/DENY speech acts for observability
 * </p>
 * <p>
 * Use case: Safely terminate charging sessions when charger faults detected
 * (e.g., overheating, ground fault, etc.)
 * </p>
 */
public class TransactionStopActor extends BaseActor {
    private static final Logger logger = LoggerFactory.getLogger(TransactionStopActor.class);
    private static final long RATE_LIMIT_MS = 60_000;  // 1 minute

    private final OcppCommandExecutor commandExecutor;
    private final Map<String, String> activeTransactions;  // chargerId -> transactionId
    private final Subscription subscription;

    public TransactionStopActor(
        Conduit<Reporters.Reporter, Reporters.Sign> reporters,
        Conduit<Actors.Actor, Actors.Sign> actors,
        OcppCommandExecutor commandExecutor
    ) {
        super(actors, "transaction-stop-actor", RATE_LIMIT_MS);
        this.commandExecutor = commandExecutor;
        this.activeTransactions = new ConcurrentHashMap<>();

        // Subscribe to connector health reporters (more granular than charger-level)
        this.subscription = reporters.subscribe(
            cortex().subscriber(
                cortex().name("transaction-stop-actor-subscriber"),
                this::handleReporterSignal
            )
        );

        logger.info("TransactionStopActor initialized");
    }

    /**
     * Handle reporter signals and filter for CRITICAL connector health.
     */
    private void handleReporterSignal(
        Subject<Channel<Reporters.Sign>> subject,
        Registrar<Reporters.Sign> registrar
    ) {
        Name reporterName = subject.name();

        // Filter: Only register for connector health reporters
        if (isConnectorHealthReporter(reporterName)) {
            registrar.register(sign -> {
                if (sign == Reporters.Sign.CRITICAL) {
                    handleConnectorCritical(reporterName);
                }
            });
        }
    }

    /**
     * Check if reporter name matches connector health pattern.
     * Pattern: "{chargerId}.connector.{connectorId}.health"
     */
    private boolean isConnectorHealthReporter(Name reporterName) {
        String path = reporterName.path();
        return path.contains(".connector.") && path.endsWith(".health");
    }

    /**
     * Handle CRITICAL connector health by stopping active transaction.
     */
    private void handleConnectorCritical(Name reporterName) {
        String chargerId = extractChargerId(reporterName);
        String transactionId = activeTransactions.get(chargerId);

        if (transactionId == null) {
            logger.debug("No active transaction on charger {} to stop", chargerId);
            return;  // No transaction to stop
        }

        String actionKey = "stop-transaction:" + transactionId;

        executeWithProtection(actionKey, () -> {
            logger.warn("Remotely stopping transaction {} on charger {} due to critical health",
                transactionId, chargerId);

            // Create OCPP RemoteStopTransaction command
            OcppCommand.RemoteStopTransaction command = new OcppCommand.RemoteStopTransaction(
                chargerId,
                UUID.randomUUID().toString(),
                transactionId
            );

            // Execute command
            boolean success = commandExecutor.executeCommand(command);
            if (!success) {
                throw new RuntimeException("Failed to send RemoteStopTransaction command");
            }

            // Remove from active transactions
            activeTransactions.remove(chargerId);

            logger.info("Successfully stopped transaction {} on charger {}", transactionId, chargerId);
        });
    }

    /**
     * Extract charger ID from reporter name.
     * Example: "charger-001.connector.1.health" → "charger-001"
     */
    private String extractChargerId(Name reporterName) {
        String path = reporterName.path();
        int connectorIndex = path.indexOf(".connector.");
        return connectorIndex > 0 ? path.substring(0, connectorIndex) : path;
    }

    /**
     * Register an active transaction (called when StartTransaction received).
     */
    public void registerTransaction(String chargerId, String transactionId) {
        activeTransactions.put(chargerId, transactionId);
        logger.debug("Registered active transaction {} on charger {}", transactionId, chargerId);
    }

    /**
     * Unregister a transaction (called when StopTransaction received).
     */
    public void unregisterTransaction(String chargerId) {
        String transactionId = activeTransactions.remove(chargerId);
        if (transactionId != null) {
            logger.debug("Unregistered transaction {} on charger {}", transactionId, chargerId);
        }
    }

    @Override
    public void close() {
        logger.info("Closing TransactionStopActor");
        if (subscription != null) {
            subscription.cancel();
        }
        activeTransactions.clear();
        super.close();
    }
}
