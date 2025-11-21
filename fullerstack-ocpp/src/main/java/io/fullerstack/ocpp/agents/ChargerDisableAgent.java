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

import java.util.UUID;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Autonomous agent that disables chargers when they become critically unhealthy.
 * <p>
 * Layer 4 (ACT - Agents): <b>Autonomous self-regulation using Promise Theory</b>
 * <p>
 * <b>Signal-Flow Pattern:</b>
 * <pre>
 * Layer 1: OcppMessageObserver → Monitors (charger condition: DOWN/DEFECTIVE)
 *                                    ↓
 * Layer 3: ChargerHealthReporter → Reporters (urgency: CRITICAL)
 *                                    ↓
 * Layer 4: ChargerDisableAgent → AGENTS (promise-based autonomous action)
 *           ↓
 *      promise() → ChangeAvailability(Inoperative) → fulfill()
 *      (if failure) → breach()
 * </pre>
 *
 * <h3>Agents API Usage (Promise Theory - Mark Burgess):</h3>
 * <ul>
 *   <li><b>Agent OFFERS</b> charger disabling capability</li>
 *   <li><b>Agent PROMISES</b> to disable charger when health CRITICAL</li>
 *   <li><b>Agent FULFILLS</b> promise by sending ChangeAvailability(Inoperative) command</li>
 *   <li><b>Agent BREACHES</b> promise if command fails</li>
 * </ul>
 *
 * <h3>Why Agents API (not Actors API)?</h3>
 * <ul>
 *   <li><b>Autonomous</b>: No human approval needed for safety-critical actions</li>
 *   <li><b>Fast</b>: Millisecond response to critical faults</li>
 *   <li><b>Scalable</b>: Handles thousands of chargers without coordination bottleneck</li>
 *   <li><b>Accountable</b>: Promise semantics provide clear success/failure signals</li>
 * </ul>
 *
 * <h3>Adaptive Coordination Example:</h3>
 * <pre>
 * Charger reports GroundFailure
 *   → OcppMessageObserver emits Monitor.DOWN
 *   → ChargerHealthReporter assesses as CRITICAL
 *   → ChargerDisableAgent autonomously disables charger
 *   → Agent emits: promise() → fulfill() (success)
 * </pre>
 *
 * @see io.humainary.substrates.ext.serventis.ext.agents.Agents
 * @see Agent
 */
public class ChargerDisableAgent extends BaseAgent {
    private static final Logger logger = LoggerFactory.getLogger(ChargerDisableAgent.class);
    private static final long RATE_LIMIT_MS = 300_000;  // 5 minutes

    private final OcppCommandExecutor commandExecutor;
    private final Subscription subscription;

    public ChargerDisableAgent(
        Conduit<Situations.Situation, Situations.Signal> reporters,
        Conduit<Agent, Agents.Signal> agents,
        OcppCommandExecutor commandExecutor
    ) {
        super(agents, "charger-disable-agent", RATE_LIMIT_MS);
        this.commandExecutor = commandExecutor;

        // Subscribe to all charger health reporters
        this.subscription = reporters.subscribe(
            cortex().subscriber(
                cortex().name("charger-disable-agent-subscriber"),
                this::handleReporterSignal
            )
        );

        logger.info("ChargerDisableAgent initialized (Agents API - autonomous)");
    }

    /**
     * Handle reporter signals and filter for CRITICAL charger health.
     */
    private void handleReporterSignal(
        Subject<Channel<Situations.Signal>> subject,
        Registrar<Situations.Signal> registrar
    ) {
        Name reporterName = subject.name();

        // Filter: Only register for charger health reporters (pattern: "{chargerId}.health")
        if (isChargerHealthReporter(reporterName)) {
            registrar.register(signal -> {
                if (signal.sign() == Situations.Sign.CRITICAL) {
                    handleChargerCritical(reporterName);
                }
            });
        }
    }

    /**
     * Check if reporter name matches charger health pattern.
     */
    private boolean isChargerHealthReporter(Name reporterName) {
        return reporterName.depth() == 2 &&
               "health".equals(reporterName.value());
    }

    /**
     * Handle CRITICAL health signal by autonomously disabling the charger.
     * <p>
     * Uses Promise Theory: promise() → execute → fulfill()/breach()
     */
    private void handleChargerCritical(Name reporterName) {
        String chargerId = extractChargerId(reporterName);
        String actionKey = "disable:" + chargerId;

        executeWithProtection(actionKey, () -> {
            logger.warn("[AGENTS] Autonomously disabling charger {} due to CRITICAL health", chargerId);

            // Create OCPP ChangeAvailability command (Inoperative)
            OcppCommand.ChangeAvailability command = new OcppCommand.ChangeAvailability(
                chargerId,
                UUID.randomUUID().toString(),
                0,  // Connector 0 = entire charger
                "Inoperative"
            );

            // Execute command
            boolean success = commandExecutor.executeCommand(command);
            if (!success) {
                throw new RuntimeException("Failed to send ChangeAvailability command to charger");
            }

            logger.info("[AGENTS] Successfully disabled charger {}", chargerId);
        });
    }

    /**
     * Extract charger ID from reporter name.
     * Example: "charger-001.health" → "charger-001"
     */
    private String extractChargerId(Name reporterName) {
        // Get enclosing name (parent)
        Name enclosure = reporterName.enclosure();
        return enclosure != null ? enclosure.value() : reporterName.value();
    }

    @Override
    public void close() {
        logger.info("Closing ChargerDisableAgent");
        if (subscription != null) {
            subscription.cancel();
        }
        super.close();
    }
}
