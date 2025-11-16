package io.fullerstack.ocpp.example;

import io.fullerstack.ocpp.server.production.RealOcppCentralSystem;
import io.fullerstack.ocpp.observers.OcppMessageObserver;
import io.fullerstack.ocpp.monitors.ChargerConnectionMonitor;
import io.fullerstack.ocpp.reporters.ChargerHealthReporter;
import io.fullerstack.ocpp.actors.ChargerDisableActor;
import io.fullerstack.ocpp.actors.TransactionStopActor;
import io.fullerstack.ocpp.offline.OfflineStateManager;
import io.fullerstack.ocpp.api.OcppRestApi;
import io.humainary.substrates.Circuit;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Demo application showing complete OCPP integration with production library.
 * <p>
 * This demonstrates:
 * - Real OCPP 1.6 Central System using ChargeTimeEU library
 * - WebSocket server listening on port 8080
 * - Substrates signal-flow architecture (OODA loop)
 * - Adaptive coordination (auto-disable faulty chargers)
 * - Offline operation with event sourcing
 * - REST API on port 9090
 * </p>
 * <p>
 * Usage:
 * 1. Run this main class
 * 2. Connect OCPP chargers to ws://localhost:8080/{chargerId}
 * 3. Access REST API at http://localhost:9090/api/health
 * 4. Watch adaptive responses in logs
 * </p>
 */
public class OcppDemo {
    private static final Logger logger = LoggerFactory.getLogger(OcppDemo.class);

    public static void main(String[] args) throws IOException, InterruptedException {
        logger.info("====================================================");
        logger.info("  Fullerstack OCPP - Production Demo");
        logger.info("====================================================");
        logger.info("");
        logger.info("Starting OCPP Central System with Substrates integration...");
        logger.info("");

        // ================================================================
        // Layer 0: OCPP Central System (Production WebSocket Server)
        // ================================================================
        logger.info("[Layer 0] Creating OCPP Central System (port 8080)");
        RealOcppCentralSystem centralSystem = new RealOcppCentralSystem(8080);

        // ================================================================
        // Layer 1: Instrumentation Circuits and Conduits
        // ================================================================
        logger.info("[Layer 1] Creating instrumentation circuits");

        Circuit monitorCircuit = cortex().circuit(cortex().name("ocpp-demo-monitors"));
        Conduit<Monitors.Monitor, Monitors.Sign> monitors = monitorCircuit.conduit(
            cortex().name("monitors"),
            Monitors::composer
        );

        Circuit counterCircuit = cortex().circuit(cortex().name("ocpp-demo-counters"));
        Conduit<Counters.Counter, Counters.Sign> counters = counterCircuit.conduit(
            cortex().name("counters"),
            Counters::composer
        );

        Circuit gaugeCircuit = cortex().circuit(cortex().name("ocpp-demo-gauges"));
        Conduit<Gauges.Gauge, Gauges.Sign> gauges = gaugeCircuit.conduit(
            cortex().name("gauges"),
            Gauges::composer
        );

        // ================================================================
        // Layer 1-2: Observers (OBSERVE/ORIENT)
        // ================================================================
        logger.info("[Layer 1-2] Creating observers");

        OcppMessageObserver messageObserver = new OcppMessageObserver(monitors, counters, gauges);
        ChargerConnectionMonitor connectionMonitor = new ChargerConnectionMonitor(monitors);

        // Register observer with Central System
        centralSystem.registerMessageHandler(messageObserver);

        // ================================================================
        // Layer 3: Reporters (DECIDE)
        // ================================================================
        logger.info("[Layer 3] Creating reporter circuit");

        Circuit reporterCircuit = cortex().circuit(cortex().name("ocpp-demo-reporters"));
        Conduit<Reporters.Reporter, Reporters.Sign> reporters = reporterCircuit.conduit(
            cortex().name("reporters"),
            Reporters::composer
        );

        ChargerHealthReporter healthReporter = new ChargerHealthReporter(monitors, reporters);

        // ================================================================
        // Layer 4: Actors (ACT)
        // ================================================================
        logger.info("[Layer 4] Creating actor circuit");

        Circuit actorCircuit = cortex().circuit(cortex().name("ocpp-demo-actors"));
        Conduit<Actors.Actor, Actors.Sign> actors = actorCircuit.conduit(
            cortex().name("actors"),
            Actors::composer
        );

        ChargerDisableActor disableActor = new ChargerDisableActor(reporters, actors, centralSystem);
        TransactionStopActor stopActor = new TransactionStopActor(reporters, actors, centralSystem);

        // ================================================================
        // Cross-cutting: Offline State Management
        // ================================================================
        logger.info("[Offline] Creating state manager");
        OfflineStateManager stateManager = new OfflineStateManager();

        // ================================================================
        // API: REST API Server
        // ================================================================
        logger.info("[API] Creating REST API (port 9090)");
        OcppRestApi restApi = new OcppRestApi(stateManager, 9090);

        // ================================================================
        // Start Everything
        // ================================================================
        logger.info("");
        logger.info("Starting all systems...");

        centralSystem.start();
        connectionMonitor.start();
        restApi.start();

        logger.info("");
        logger.info("====================================================");
        logger.info("  OCPP Central System READY");
        logger.info("====================================================");
        logger.info("");
        logger.info("OCPP WebSocket: ws://localhost:8080/{{chargerId}}");
        logger.info("REST API:       http://localhost:9090/api/health");
        logger.info("");
        logger.info("Signal Flow Architecture:");
        logger.info("  Layer 0: OCPP Protocol (WebSocket)");
        logger.info("  Layer 1: OBSERVE (Message → Signals)");
        logger.info("  Layer 2: ORIENT (Health Monitoring)");
        logger.info("  Layer 3: DECIDE (Urgency Assessment)");
        logger.info("  Layer 4: ACT (Adaptive Commands)");
        logger.info("");
        logger.info("Adaptive Responses:");
        logger.info("  - CRITICAL health → Auto-disable charger");
        logger.info("  - CRITICAL connector → Remote stop transaction");
        logger.info("");
        logger.info("Press Ctrl+C to shutdown");
        logger.info("====================================================");
        logger.info("");

        // Subscribe to signals for logging
        setupSignalLogging(monitors, reporters, actors);

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("");
            logger.info("Shutting down OCPP Central System...");

            try {
                restApi.close();
                stopActor.close();
                disableActor.close();
                healthReporter.close();
                connectionMonitor.close();
                messageObserver.close();
                centralSystem.close();
                stateManager.close();

                logger.info("Shutdown complete");
            } catch (Exception e) {
                logger.error("Error during shutdown: {}", e.getMessage(), e);
            }
        }));

        // Keep running
        Thread.currentThread().join();
    }

    /**
     * Setup signal logging for demonstration.
     */
    private static void setupSignalLogging(
        Conduit<Monitors.Monitor, Monitors.Sign> monitors,
        Conduit<Reporters.Reporter, Reporters.Sign> reporters,
        Conduit<Actors.Actor, Actors.Sign> actors
    ) {
        // Log critical monitor signals
        monitors.subscribe(cortex().subscriber(
            cortex().name("demo-monitor-logger"),
            (subject, registrar) -> {
                String entityName = subject.name().path();
                registrar.register(sign -> {
                    if (sign == Monitors.Sign.DOWN || sign == Monitors.Sign.DEFECTIVE) {
                        logger.warn("🔴 MONITOR [{}] → {}", entityName, sign);
                    } else if (sign == Monitors.Sign.DEGRADED || sign == Monitors.Sign.ERRATIC) {
                        logger.warn("🟡 MONITOR [{}] → {}", entityName, sign);
                    } else {
                        logger.info("🟢 MONITOR [{}] → {}", entityName, sign);
                    }
                });
            }
        ));

        // Log all reporter signals
        reporters.subscribe(cortex().subscriber(
            cortex().name("demo-reporter-logger"),
            (subject, registrar) -> {
                String reporterName = subject.name().path();
                registrar.register(sign -> {
                    switch (sign) {
                        case CRITICAL -> logger.error("🚨 REPORTER [{}] → CRITICAL", reporterName);
                        case WARNING -> logger.warn("⚠️  REPORTER [{}] → WARNING", reporterName);
                        case NORMAL -> logger.info("✅ REPORTER [{}] → NORMAL", reporterName);
                    }
                });
            }
        ));

        // Log all actor speech acts
        actors.subscribe(cortex().subscriber(
            cortex().name("demo-actor-logger"),
            (subject, registrar) -> {
                String actorName = subject.name().path();
                registrar.register(sign -> {
                    switch (sign) {
                        case DELIVER -> logger.info("✓ ACTOR [{}] → DELIVER (action succeeded)", actorName);
                        case DENY -> logger.warn("✗ ACTOR [{}] → DENY (action blocked/failed)", actorName);
                    }
                });
            }
        ));
    }
}
