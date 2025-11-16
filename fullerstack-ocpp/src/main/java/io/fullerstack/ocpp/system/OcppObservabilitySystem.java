package io.fullerstack.ocpp.system;

import io.fullerstack.ocpp.actors.ChargerDisableActor;
import io.fullerstack.ocpp.actors.TransactionStopActor;
import io.fullerstack.ocpp.monitors.ChargerConnectionMonitor;
import io.fullerstack.ocpp.observers.OcppMessageObserver;
import io.fullerstack.ocpp.reporters.ChargerHealthReporter;
import io.fullerstack.ocpp.server.OcppCentralSystem;
import io.fullerstack.ocpp.server.OcppCommandExecutor;
import io.humainary.substrates.Circuit;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
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
 * Layer 2: ORIENT - ChargerConnectionMonitor tracks health
 *          ↓ (Monitor.Sign: STABLE, DEGRADED, DOWN, etc.)
 * Layer 3: DECIDE - ChargerHealthReporter assesses urgency
 *          ↓ (Reporter.Sign: NORMAL, WARNING, CRITICAL)
 * Layer 4: ACT - Actors execute semantic commands
 *          ↓ (ChargerDisableActor, TransactionStopActor)
 *          ↓ (Actor.Sign: DELIVER, DENY)
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

    // Layer 1-2: Observers
    private final OcppMessageObserver messageObserver;
    private final ChargerConnectionMonitor connectionMonitor;

    // Layer 3: Reporter Circuit and Components
    private final Circuit reporterCircuit;
    private final Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private final ChargerHealthReporter chargerHealthReporter;

    // Layer 4: Actor Circuit and Components
    private final Circuit actorCircuit;
    private final Conduit<Actors.Actor, Actors.Sign> actors;
    private final ChargerDisableActor chargerDisableActor;
    private final TransactionStopActor transactionStopActor;

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
        OcppCommandExecutor commandExecutor = centralSystem::executeCommand;

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
        // Layer 1-2: Observers (OBSERVE/ORIENT)
        // ====================================================================
        logger.info("Creating Layer 1-2 observers");

        this.messageObserver = new OcppMessageObserver(monitors, counters, gauges);
        this.connectionMonitor = new ChargerConnectionMonitor(monitors);

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
        // Layer 4: Actor Circuit and Components (ACT)
        // ====================================================================
        logger.info("Creating Layer 4 actor circuit (ACT)");

        this.actorCircuit = cortex().circuit(cortex().name(systemName + "-actors"));
        this.actors = actorCircuit.conduit(
            cortex().name("actors"),
            Actors::composer
        );

        this.chargerDisableActor = new ChargerDisableActor(reporters, actors, commandExecutor);
        this.transactionStopActor = new TransactionStopActor(reporters, actors, commandExecutor);

        logger.info("OCPP Observability System initialized successfully");
    }

    /**
     * Start the complete system.
     * - Starts OCPP Central System server
     * - Activates connection monitoring
     * - Enables signal flow through all circuits
     */
    public void start() {
        logger.info("Starting OCPP Observability System");

        // Start OCPP server
        centralSystem.start();

        // Start connection monitor
        connectionMonitor.start();

        logger.info("OCPP Observability System started successfully");
        logger.info("  - OCPP Central System: RUNNING");
        logger.info("  - Monitor Circuit: ACTIVE");
        logger.info("  - Reporter Circuit: ACTIVE");
        logger.info("  - Actor Circuit: ACTIVE");
        logger.info("  - Connected Chargers: {}", centralSystem.getConnectedChargerCount());
    }

    /**
     * Stop the complete system.
     */
    public void stop() {
        logger.info("Stopping OCPP Observability System");

        // Stop monitoring
        connectionMonitor.stop();

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
    public Conduit<Reporters.Reporter, Reporters.Sign> getReporters() {
        return reporters;
    }

    /**
     * Get the Actor conduit for signal inspection/testing.
     */
    public Conduit<Actors.Actor, Actors.Sign> getActors() {
        return actors;
    }

    /**
     * Get the transaction stop actor (for transaction registration).
     */
    public TransactionStopActor getTransactionStopActor() {
        return transactionStopActor;
    }

    /**
     * Await signal processing on all circuits (useful for testing).
     */
    public void awaitSignalProcessing() {
        monitorCircuit.await();
        counterCircuit.await();
        gaugeCircuit.await();
        reporterCircuit.await();
        actorCircuit.await();
    }

    @Override
    public void close() {
        logger.info("Closing OCPP Observability System");

        // Close actors (Layer 4)
        transactionStopActor.close();
        chargerDisableActor.close();

        // Close reporters (Layer 3)
        chargerHealthReporter.close();

        // Close observers (Layer 1-2)
        connectionMonitor.close();
        messageObserver.close();

        // Close central system (Layer 0)
        centralSystem.close();

        logger.info("OCPP Observability System closed");
    }
}
