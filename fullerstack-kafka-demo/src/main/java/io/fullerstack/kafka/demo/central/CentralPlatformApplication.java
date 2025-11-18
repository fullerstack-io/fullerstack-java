package io.fullerstack.kafka.demo.central;

import io.fullerstack.kafka.coordination.central.RequestHandler;
import io.fullerstack.kafka.coordination.central.ResponseSender;
import io.fullerstack.kafka.coordination.central.SidecarRegistry;
import io.fullerstack.kafka.coordination.central.SpeechActListener;
import io.fullerstack.kafka.demo.web.DashboardBroadcaster;
import io.fullerstack.kafka.demo.web.DashboardServer;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Actors;
import org.apache.kafka.clients.admin.AdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Central Platform Application - Distributed Speech Act Coordination.
 * <p>
 * Listens for speech acts from sidecars, processes requests using Agents/Actors APIs,
 * and sends responses back via Kafka.
 * <p>
 * <b>Architecture:</b>
 * <pre>
 * Sidecars emit REQUEST speech acts
 *     ↓ (via Kafka: observability.speech-acts)
 * SpeechActListener consumes
 *     ↓
 * RequestHandler processes
 *     ├→ Agents API: promise() → fulfill() / breach()
 *     └→ Actors API: acknowledge() → promise() → deliver() / deny()
 *     ↓
 * ResponseSender sends back
 *     ↓ (via Kafka: observability.responses)
 * Sidecars receive responses
 * </pre>
 *
 * @since 1.0.0
 */
public class CentralPlatformApplication {
    private static final Logger logger = LoggerFactory.getLogger(CentralPlatformApplication.class);

    public static void main(String[] args) {
        // Configuration from environment or defaults
        String kafkaBootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092");
        String requestTopic = System.getenv().getOrDefault("REQUEST_TOPIC", "observability.speech-acts");
        String responseTopic = System.getenv().getOrDefault("RESPONSE_TOPIC", "observability.responses");
        int dashboardPort = Integer.parseInt(System.getenv().getOrDefault("DASHBOARD_PORT", "8080"));

        logger.info("╔══════════════════════════════════════════════════════════════════╗");
        logger.info("║          Central Platform - Distributed Coordination            ║");
        logger.info("╚══════════════════════════════════════════════════════════════════╝");
        logger.info("Kafka bootstrap: {}", kafkaBootstrap);
        logger.info("Request topic: {}", requestTopic);
        logger.info("Response topic: {}", responseTopic);
        logger.info("Dashboard port: {}", dashboardPort);

        // Create central circuit
        Circuit centralCircuit = cortex().circuit(cortex().name("central-platform"));
        logger.info("✅ Created central Circuit");

        // Create Agents conduit (for central promises - Promise Theory)
        Conduit<Agents.Agent, Agents.Signal> agents = centralCircuit.conduit(
            cortex().name("agents"),
            Agents::composer
        );
        logger.info("✅ Created Agents conduit (Promise Theory)");

        // Create Actors conduit (for speech acts - Speech Act Theory)
        Conduit<Actors.Actor, Actors.Sign> actors = centralCircuit.conduit(
            cortex().name("actors"),
            Actors::composer
        );
        logger.info("✅ Created Actors conduit (Speech Act Theory)");

        // Create Kafka AdminClient for cluster operations
        Properties adminProps = new Properties();
        adminProps.put("bootstrap.servers", kafkaBootstrap);
        AdminClient adminClient = AdminClient.create(adminProps);
        logger.info("✅ Created Kafka AdminClient");

        // Create SidecarRegistry (PRODUCTION auto-discovery)
        SidecarRegistry registry = new SidecarRegistry();
        logger.info("✅ Created SidecarRegistry (auto-discovery enabled)");

        // Create ResponseSender
        ResponseSender responseSender = new ResponseSender(kafkaBootstrap, responseTopic);
        logger.info("✅ Created ResponseSender");

        // Create RequestHandler
        RequestHandler requestHandler = new RequestHandler(
            agents, actors, adminClient, responseSender
        );
        logger.info("✅ Created RequestHandler");

        // Create SpeechActListener with registry integration
        SpeechActListener listener = new SpeechActListener(
            kafkaBootstrap, requestTopic, requestHandler, registry
        );
        logger.info("✅ Created SpeechActListener (with auto-discovery)");

        // Start WebSocket dashboard for real-time visualization
        try {
            DashboardServer.startServer(dashboardPort);
            logger.info("✅ Started WebSocket dashboard on port {}", dashboardPort);
            logger.info("   → Open http://localhost:{} to view live signals", dashboardPort);

            // Subscribe dashboard (proper Substrates Subscriber pattern)
            DashboardBroadcaster broadcaster = new DashboardBroadcaster();
            actors.subscribe(broadcaster.subscriber("actors"));
            logger.info("✅ Dashboard subscribed to signals (Actors)");

        } catch (Throwable e) {
            logger.error("❌ Failed to start dashboard server", e);
            // Continue without dashboard
        }

        // Start listener in background virtual thread
        Thread listenerThread = Thread.ofVirtual().name("speech-act-listener").start(listener);
        logger.info("✅ Started SpeechActListener in virtual thread");

        logger.info("");
        logger.info("╔══════════════════════════════════════════════════════════════════╗");
        logger.info("║                    ✅ Central Platform Ready                     ║");
        logger.info("╚══════════════════════════════════════════════════════════════════╝");
        logger.info("Listening for speech acts on topic: {}", requestTopic);
        logger.info("Sending responses on topic: {}", responseTopic);
        logger.info("Dashboard: http://localhost:{}", dashboardPort);
        logger.info("");
        logger.info("Press Ctrl+C to shutdown...");

        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("");
            logger.info("Shutting down Central Platform...");
            listener.stop();
            try {
                listenerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            responseSender.close();
            adminClient.close();
            centralCircuit.close();
            try {
                DashboardServer.stopServer();
                logger.info("✅ Dashboard server stopped");
            } catch (Throwable e) {
                logger.warn("Dashboard server shutdown error: {}", e.getMessage());
            }
            logger.info("✅ Central Platform shutdown complete");
        }, "shutdown-hook"));

        // Keep main thread alive
        try {
            listenerThread.join();
        } catch (InterruptedException e) {
            logger.info("Main thread interrupted, shutting down...");
            Thread.currentThread().interrupt();
        }
    }
}
