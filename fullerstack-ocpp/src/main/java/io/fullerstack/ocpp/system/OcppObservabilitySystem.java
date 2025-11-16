package io.fullerstack.ocpp.system;

import io.fullerstack.ocpp.agents.ChargerDisableAgent;
import io.fullerstack.ocpp.agents.TransactionStopAgent;
import io.fullerstack.ocpp.observers.OcppMessageObserver;
import io.fullerstack.ocpp.observers.CommandVerificationObserver;
import io.fullerstack.ocpp.reporters.ChargerHealthReporter;
import io.fullerstack.ocpp.server.OcppCentralSystem;
import io.fullerstack.ocpp.server.OcppCommandExecutor;
import io.fullerstack.ocpp.server.CommandTrackingExecutor;
import io.humainary.substrates.Circuit;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.agents.Agents;
import io.humainary.substrates.ext.serventis.ext.agents.Agents.Agent;
import io.humainary.substrates.ext.serventis.ext.counters.Counters;
import io.humainary.substrates.ext.serventis.ext.gauges.Gauges;
import io.humainary.substrates.ext.serventis.ext.monitors.Monitors;
import io.humainary.substrates.ext.serventis.ext.reporters.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Complete OCPP observability and adaptive coordination system.
 * <p>
 * Integrates all layers of the signal-flow architecture:
 * </p>
 * <pre>
 * Layer 0: OCPP Protocol (WebSocket + JSON)
 *          ↓
 * Layer 1: OBSERVE - OcppMessageObserver translates messages to signals
 *          ↓ (Monitors, Counters, Gauges)
 * Layer 2: ORIENT - Monitor signals flow through circuits
 *          ↓ (Monitor.Sign: STABLE, DEGRADED, DOWN, etc.)
 * Layer 3: DECIDE - ChargerHealthReporter assesses urgency
 *          ↓ (Reporter.Sign: NORMAL, WARNING, CRITICAL)
 * Layer 4: ACT - Agents execute autonomous actions (Promise Theory)
 *          ↓ (ChargerDisableAgent, TransactionStopAgent)
 *          ↓ (promise → fulfill/breach)
 * Layer 5: Physical Actions (ChangeAvailability, RemoteStopTransaction)
 * </pre>
 * <p>
 * Pattern follows KafkaObservabilitySystem - wire all circuits, conduits,
 * observers, reporters, and actors together.
 * </p>
 */
public class OcppObservabilitySystem implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(OcppObservabilitySystem.class);

    // Layer 0: OCPP Protocol Infrastructure
    private final OcppCentralSystem centralSystem;

    // Layer 1: Instrumentation Circuits and Conduits
    private final Circuit monitorCircuit;
    private final Circuit counterCircuit;
    private final Circuit gaugeCircuit;
    private final Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private final Conduit<Counters.Counter, Counters.Sign> counters;
    private final Conduit<Gauges.Gauge, Gauges.Sign> gauges;

    // Layer 1: Observers
    private final OcppMessageObserver messageObserver;
    private final CommandVerificationObserver commandVerificationObserver;

    // Layer 3: Reporter Circuit and Components
    private final Circuit reporterCircuit;
    private final Conduit<Reporters.Reporter, Reporters.Signal> reporters;
    private final ChargerHealthReporter chargerHealthReporter;

    // Layer 4: Agent Circuit and Components (Autonomous Self-Regulation)
    private final Circuit agentCircuit;
    private final Conduit<Agent, Agents.Signal> agents;
    private final ChargerDisableAgent chargerDisableAgent;
    private final TransactionStopAgent transactionStopAgent;

    /**
     * Create the complete OCPP observability system.
     *
     * @param systemName unique name for this system instance
     * @param ocppPort port for OCPP Central System server
     */
    public OcppObservabilitySystem(String systemName, int ocppPort) {
        logger.info("Initializing OCPP Observability System: {}", systemName);

        // ====================================================================
        // Layer 0: OCPP Central System
        // ====================================================================
        this.centralSystem = new OcppCentralSystem(ocppPort);

        // ====================================================================
        // Layer 1: Instrumentation Circuits and Conduits
        // ====================================================================
        logger.info("Creating Layer 1 circuits (OBSERVE)");

        this.monitorCircuit = cortex().circuit(cortex().name(systemName + "-monitors"));
        this.monitors = monitorCircuit.conduit(
            cortex().name("monitors"),
            Monitors::composer
        );

        this.counterCircuit = cortex().circuit(cortex().name(systemName + "-counters"));
        this.counters = counterCircuit.conduit(
            cortex().name("counters"),
            Counters::composer
        );

        this.gaugeCircuit = cortex().circuit(cortex().name(systemName + "-gauges"));
        this.gauges = gaugeCircuit.conduit(
            cortex().name("gauges"),
            Gauges::composer
        );

        // ====================================================================
        // Layer 1: Observers (OBSERVE)
        // ====================================================================
        logger.info("Creating Layer 1 observers");

        this.messageObserver = new OcppMessageObserver(monitors, counters, gauges);

        // Command verification observer for closed-loop feedback
        this.commandVerificationObserver = new CommandVerificationObserver(monitors);
        messageObserver.setCommandVerificationObserver(commandVerificationObserver);

        // Register message observer with central system
        centralSystem.registerMessageHandler(messageObserver);

        // ====================================================================
        // Layer 3: Reporter Circuit and Components (DECIDE)
        // ====================================================================
        logger.info("Creating Layer 3 reporter circuit (DECIDE)");

        this.reporterCircuit = cortex().circuit(cortex().name(systemName + "-reporters"));
        this.reporters = reporterCircuit.conduit(
            cortex().name("reporters"),
            Reporters::composer
        );

        this.chargerHealthReporter = new ChargerHealthReporter(monitors, reporters);

        // ====================================================================
        // Layer 4: Agent Circuit and Components (ACT - Autonomous)
        // ====================================================================
        logger.info("Creating Layer 4 agent circuit (ACT - Agents API for autonomous self-regulation)");

        this.agentCircuit = cortex().circuit(cortex().name(systemName + "-agents"));
        this.agents = agentCircuit.conduit(
            cortex().name("agents"),
            Agents::composer
        );

        // Wrap command executor with tracking for closed-loop feedback
        OcppCommandExecutor baseExecutor = centralSystem::executeCommand;
        OcppCommandExecutor trackingExecutor = new CommandTrackingExecutor(
            baseExecutor,
            commandVerificationObserver
        );

        this.chargerDisableAgent = new ChargerDisableAgent(reporters, agents, trackingExecutor);
        this.transactionStopAgent = new TransactionStopAgent(reporters, agents, trackingExecutor);

        logger.info("OCPP Observability System initialized successfully");
    }

    /**
     * Start the complete system.
     * - Starts OCPP Central System server
     * - Enables signal flow through all circuits
     */
    public void start() {
        logger.info("Starting OCPP Observability System");

        // Start OCPP server
        centralSystem.start();

        logger.info("OCPP Observability System started successfully");
        logger.info("  - OCPP Central System: RUNNING");
        logger.info("  - Monitor Circuit: ACTIVE");
        logger.info("  - Reporter Circuit: ACTIVE");
        logger.info("  - Agent Circuit: ACTIVE (Autonomous Self-Regulation)");
        logger.info("  - Command Verification: ENABLED (Closed-Loop Feedback)");
        logger.info("  - Connected Chargers: {}", centralSystem.getConnectedChargerCount());
    }

    /**
     * Stop the complete system.
     */
    public void stop() {
        logger.info("Stopping OCPP Observability System");

        // Stop OCPP server
        centralSystem.stop();

        logger.info("OCPP Observability System stopped");
    }

    /**
     * Get the OCPP Central System for direct access (e.g., for testing).
     */
    public OcppCentralSystem getCentralSystem() {
        return centralSystem;
    }

    /**
     * Get the Monitor conduit for signal inspection/testing.
     */
    public Conduit<Monitors.Monitor, Monitors.Sign> getMonitors() {
        return monitors;
    }

    /**
     * Get the Reporter conduit for signal inspection/testing.
     */
    public Conduit<Reporters.Reporter, Reporters.Signal> getReporters() {
        return reporters;
    }

    /**
     * Get the Agent conduit for signal inspection/testing.
     */
    public Conduit<Agent, Agents.Signal> getAgents() {
        return agents;
    }

    /**
     * Get the transaction stop agent (for transaction registration).
     */
    public TransactionStopAgent getTransactionStopAgent() {
        return transactionStopAgent;
    }

    /**
     * Await signal processing on all circuits (useful for testing).
     */
    public void awaitSignalProcessing() {
        monitorCircuit.await();
        counterCircuit.await();
        gaugeCircuit.await();
        reporterCircuit.await();
        agentCircuit.await();
    }

    @Override
    public void close() {
        logger.info("Closing OCPP Observability System");

        // Close agents (Layer 4)
        transactionStopAgent.close();
        chargerDisableAgent.close();

        // Close reporters (Layer 3)
        chargerHealthReporter.close();

        // Close observers (Layer 1)
        commandVerificationObserver.close();
        messageObserver.close();

        // Close central system (Layer 0)
        centralSystem.close();

        logger.info("OCPP Observability System closed");
    }
}
