package io.fullerstack.ocpp.agents;

import io.fullerstack.ocpp.server.OcppCommand;
import io.fullerstack.ocpp.server.OcppCommandExecutor;
import io.humainary.substrates.Channel;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.Name;
import io.humainary.substrates.Registrar;
import io.humainary.substrates.Subject;
import io.humainary.substrates.Subscription;
import io.humainary.substrates.ext.serventis.ext.agents.Agents;
import io.humainary.substrates.ext.serventis.ext.agents.Agents.Agent;
import io.humainary.substrates.ext.serventis.ext.reporters.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Autonomous agent that remotely stops transactions when charger health is critical.
 * <p>
 * Layer 4 (ACT - Agents): <b>Autonomous self-regulation using Promise Theory</b>
 * <p>
 * <b>Signal-Flow Pattern:</b>
 * <pre>
 * Layer 1: OcppMessageObserver → Monitors (connector condition: DEFECTIVE)
 *                                    ↓
 * Layer 3: ConnectorHealthReporter → Reporters (urgency: CRITICAL)
 *                                    ↓
 * Layer 4: TransactionStopAgent → AGENTS (promise-based autonomous action)
 *           ↓
 *      promise() → RemoteStopTransaction → fulfill()
 *      (if failure) → breach()
 * </pre>
 *
 * <h3>Agents API Usage (Promise Theory - Mark Burgess):</h3>
 * <ul>
 *   <li><b>Agent OFFERS</b> transaction stopping capability</li>
 *   <li><b>Agent PROMISES</b> to stop transaction when connector health CRITICAL</li>
 *   <li><b>Agent FULFILLS</b> promise by sending RemoteStopTransaction command</li>
 *   <li><b>Agent BREACHES</b> promise if command fails</li>
 * </ul>
 *
 * <h3>Why Agents API (not Actors API)?</h3>
 * <ul>
 *   <li><b>Autonomous</b>: No human approval needed for safety-critical actions</li>
 *   <li><b>Fast</b>: Millisecond response to critical faults during charging</li>
 *   <li><b>Scalable</b>: Handles thousands of active transactions without bottleneck</li>
 *   <li><b>Accountable</b>: Promise semantics provide clear success/failure signals</li>
 * </ul>
 *
 * <h3>Adaptive Coordination Example:</h3>
 * <pre>
 * Connector reports Overheating during charging
 *   → OcppMessageObserver emits Monitor.DEFECTIVE
 *   → ConnectorHealthReporter assesses as CRITICAL
 *   → TransactionStopAgent autonomously stops transaction
 *   → Agent emits: promise() → fulfill() (success)
 *   → Charging session safely terminated
 * </pre>
 *
 * @see io.humainary.substrates.ext.serventis.ext.agents.Agents
 * @see Agent
 */
public class TransactionStopAgent extends BaseAgent {
    private static final Logger logger = LoggerFactory.getLogger(TransactionStopAgent.class);
    private static final long RATE_LIMIT_MS = 60_000;  // 1 minute

    private final OcppCommandExecutor commandExecutor;
    private final Map<String, String> activeTransactions;  // chargerId -> transactionId
    private final Subscription subscription;

    public TransactionStopAgent(
        Conduit<Reporters.Reporter, Reporters.Signal> reporters,
        Conduit<Agent, Agents.Signal> agents,
        OcppCommandExecutor commandExecutor
    ) {
        super(agents, "transaction-stop-agent", RATE_LIMIT_MS);
        this.commandExecutor = commandExecutor;
        this.activeTransactions = new ConcurrentHashMap<>();

        // Subscribe to connector health reporters (more granular than charger-level)
        this.subscription = reporters.subscribe(
            cortex().subscriber(
                cortex().name("transaction-stop-agent-subscriber"),
                this::handleReporterSignal
            )
        );

        logger.info("TransactionStopAgent initialized (Agents API - autonomous)");
    }

    /**
     * Handle reporter signals and filter for CRITICAL connector health.
     */
    private void handleReporterSignal(
        Subject<Channel<Reporters.Signal>> subject,
        Registrar<Reporters.Signal> registrar
    ) {
        Name reporterName = subject.name();

        // Filter: Only register for connector health reporters
        if (isConnectorHealthReporter(reporterName)) {
            registrar.register(signal -> {
                if (signal.sign() == Reporters.Sign.CRITICAL) {
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
     * Handle CRITICAL connector health by autonomously stopping active transaction.
     * <p>
     * Uses Promise Theory: promise() → execute → fulfill()/breach()
     */
    private void handleConnectorCritical(Name reporterName) {
        String chargerId = extractChargerId(reporterName);
        String transactionId = activeTransactions.get(chargerId);

        if (transactionId == null) {
            logger.debug("[AGENTS] No active transaction on charger {} to stop", chargerId);
            return;  // No transaction to stop
        }

        String actionKey = "stop-transaction:" + transactionId;

        executeWithProtection(actionKey, () -> {
            logger.warn("[AGENTS] Autonomously stopping transaction {} on charger {} due to CRITICAL health",
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

            logger.info("[AGENTS] Successfully stopped transaction {} on charger {}", transactionId, chargerId);
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
        logger.info("Closing TransactionStopAgent");
        if (subscription != null) {
            subscription.cancel();
        }
        activeTransactions.clear();
        super.close();
    }
}
