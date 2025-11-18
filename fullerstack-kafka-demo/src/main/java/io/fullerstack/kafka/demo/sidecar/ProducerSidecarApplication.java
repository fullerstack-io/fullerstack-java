package io.fullerstack.kafka.demo.sidecar;

import io.fullerstack.kafka.producer.sidecar.AgentCoordinationBridge;
import io.fullerstack.kafka.producer.sidecar.KafkaCentralCommunicator;
import io.fullerstack.kafka.producer.sidecar.SidecarResponseListener;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Producer Sidecar Application - Distributed Coordination Demo.
 * <p>
 * This application demonstrates <b>Promise Theory + Speech Act Theory</b> for distributed coordination:
 * <pre>
 * Autonomous Agent Layer:
 *   Agents API → promise() → fulfill() (99% silent)
 *              → promise() → breach()  (1% request help)
 *
 * Distributed Coordination Layer:
 *   Actors API → request() → Kafka → Central Platform
 *                         ← Kafka ← acknowledge() / promise() / deliver()
 * </pre>
 *
 * <h3>Three Levels of Autonomy:</h3>
 * <ul>
 *   <li><b>Level 1 (99%)</b>: Agent fulfills promise → Silent self-regulation</li>
 *   <li><b>Level 2 (0.9%)</b>: Agent fulfills promise → REPORT to central (audit trail)</li>
 *   <li><b>Level 3 (0.1%)</b>: Agent breaches promise → REQUEST help from central</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class ProducerSidecarApplication {
    private static final Logger logger = LoggerFactory.getLogger(ProducerSidecarApplication.class);

    public static void main(String[] args) {
        // Configuration from environment or defaults
        String sidecarId = System.getenv().getOrDefault("SIDECAR_ID", "producer-sidecar-1");
        String kafkaBootstrap = System.getenv().getOrDefault("KAFKA_BOOTSTRAP", "localhost:9092");
        String requestTopic = System.getenv().getOrDefault("REQUEST_TOPIC", "observability.speech-acts");
        String responseTopic = System.getenv().getOrDefault("RESPONSE_TOPIC", "observability.responses");
        String jmxEndpoint = System.getenv().getOrDefault("JMX_ENDPOINT", inferJmxEndpoint(sidecarId));
        String sidecarType = System.getenv().getOrDefault("SIDECAR_TYPE", "producer");

        logger.info("╔══════════════════════════════════════════════════════════════════╗");
        logger.info("║       Producer Sidecar - Distributed Coordination Demo          ║");
        logger.info("║       Promise Theory + Speech Act Theory                        ║");
        logger.info("╚══════════════════════════════════════════════════════════════════╝");
        logger.info("Sidecar ID: {}", sidecarId);
        logger.info("Sidecar Type: {}", sidecarType);
        logger.info("JMX Endpoint: {}", jmxEndpoint);
        logger.info("Kafka bootstrap: {}", kafkaBootstrap);
        logger.info("Request topic: {}", requestTopic);
        logger.info("Response topic: {}", responseTopic);
        logger.info("");

        // Create sidecar circuit
        Circuit sidecarCircuit = cortex().circuit(cortex().name(sidecarId));
        logger.info("✅ Created sidecar Circuit");

        // Create Agents conduit (for local autonomous promises - Promise Theory)
        Conduit<Agents.Agent, Agents.Signal> agents = sidecarCircuit.conduit(
            cortex().name("agents"),
            Agents::composer
        );
        logger.info("✅ Created Agents conduit (Promise Theory - Local Autonomy)");

        // Create Actors conduit (for speech acts - Speech Act Theory)
        Conduit<Actors.Actor, Actors.Sign> actors = sidecarCircuit.conduit(
            cortex().name("actors"),
            Actors::composer
        );
        logger.info("✅ Created Actors conduit (Speech Act Theory - Distributed Coordination)");

        // Create Reporters conduit (for urgency assessment - DECIDE layer)
        Conduit<Reporters.Reporter, Reporters.Sign> reporters = sidecarCircuit.conduit(
            cortex().name("reporters"),
            Reporters::composer
        );
        logger.info("✅ Created Reporters conduit (Urgency Assessment)");

        // Create central communicator
        KafkaCentralCommunicator centralCommunicator = new KafkaCentralCommunicator(
            kafkaBootstrap,
            requestTopic
        );
        logger.info("✅ Created Kafka central communicator");

        // Create and start heartbeat sender (RESILIENCE FIX - keeps sidecar registered)
        SidecarHeartbeatSender heartbeatSender = new SidecarHeartbeatSender(
            sidecarId,
            sidecarType,
            jmxEndpoint,
            centralCommunicator
        );
        Thread heartbeatThread = Thread.ofVirtual()
            .name("heartbeat-sender-" + sidecarId)
            .start(heartbeatSender);
        logger.info("✅ Started heartbeat sender (interval: 10s, with metadata)");

        // Create coordination bridge (Promise Theory + Speech Act Theory)
        AgentCoordinationBridge coordinationBridge = new AgentCoordinationBridge(
            agents,
            actors,
            centralCommunicator,
            sidecarId
        );
        logger.info("✅ Created AgentCoordinationBridge (coordinates agents/actors)");

        // Create and start response listener
        SidecarResponseListener responseListener = new SidecarResponseListener(
            kafkaBootstrap,
            responseTopic,
            actors,
            sidecarId
        );
        Thread responseThread = Thread.ofVirtual()
            .name("response-listener-" + sidecarId)
            .start(responseListener);
        logger.info("✅ Started SidecarResponseListener");

        // Demo: Simulate agent promise scenarios
        Thread demoThread = Thread.ofVirtual()
            .name("demo-scenario-" + sidecarId)
            .start(() -> runDemoScenario(agents, reporters, sidecarCircuit));
        logger.info("✅ Started demo scenario thread");

        logger.info("");
        logger.info("╔══════════════════════════════════════════════════════════════════╗");
        logger.info("║                    ✅ Sidecar Fully Operational                  ║");
        logger.info("╚══════════════════════════════════════════════════════════════════╝");
        logger.info("Promise Theory:");
        logger.info("  - Agents make autonomous promises (99% fulfill, 1% breach)");
        logger.info("  - Breached promises trigger speech act coordination");
        logger.info("");
        logger.info("Speech Act Theory:");
        logger.info("  - REQUEST: Ask central for help");
        logger.info("  - ACKNOWLEDGE: Central received request");
        logger.info("  - PROMISE: Central commits to helping");
        logger.info("  - DELIVER: Central completed action");
        logger.info("");
        logger.info("Press Ctrl+C to shutdown...");

        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("");
            logger.info("Shutting down sidecar...");

            // Stop heartbeat sender first
            heartbeatSender.close();

            // Stop coordination components
            coordinationBridge.close();
            responseListener.close();
            centralCommunicator.close();

            // Wait for threads to finish
            try {
                heartbeatThread.join(2000);
                responseThread.join(5000);
                demoThread.interrupt();
                demoThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            sidecarCircuit.close();
            logger.info("✅ Sidecar shutdown complete");
        }, "shutdown-hook"));

        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            logger.info("Main thread interrupted, shutting down...");
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs demo scenario showing Level 1 (silent), Level 2 (report), and Level 3 (request).
     */
    private static void runDemoScenario(
        Conduit<Agents.Agent, Agents.Signal> agents,
        Conduit<Reporters.Reporter, Reporters.Sign> reporters,
        Circuit circuit
    ) {
        try {
            logger.info("");
            logger.info("🎬 Starting demo scenario in 5 seconds...");
            Thread.sleep(5000);

            // Scenario 1: Level 1 - Silent self-regulation (FULFILL)
            logger.info("");
            logger.info("╔══════════════════════════════════════════════════════════════════╗");
            logger.info("║  Scenario 1: Level 1 - Silent Self-Regulation (99%)            ║");
            logger.info("╚══════════════════════════════════════════════════════════════════╝");
            Agents.Agent bufferAgent1 = agents.percept(cortex().name("buffer-regulator"));
            bufferAgent1.promise(Agents.Dimension.PROMISER);
            logger.info("📝 Agent PROMISED to self-regulate buffer");
            Thread.sleep(2000);
            bufferAgent1.fulfill(Agents.Dimension.PROMISER);
            logger.info("✅ Agent FULFILLED promise - Silent success (no communication)");
            circuit.await();  // Ensure signal processed

            Thread.sleep(5000);

            // Scenario 2: Level 2 - Report to central (FULFILL + notable event)
            logger.info("");
            logger.info("╔══════════════════════════════════════════════════════════════════╗");
            logger.info("║  Scenario 2: Level 2 - Notable Event Report (0.9%)             ║");
            logger.info("╚══════════════════════════════════════════════════════════════════╝");
            Agents.Agent bufferAgent2 = agents.percept(cortex().name("buffer-regulator"));
            bufferAgent2.promise(Agents.Dimension.PROMISER);
            logger.info("📝 Agent PROMISED to self-regulate buffer");
            Thread.sleep(2000);
            bufferAgent2.fulfill(Agents.Dimension.PROMISER);
            logger.info("✅ Agent FULFILLED promise");
            logger.info("📊 Notable event detected - sending REPORT to central (audit trail)");
            circuit.await();

            Thread.sleep(5000);

            // Scenario 3: Level 3 - Request help from central (BREACH)
            logger.info("");
            logger.info("╔══════════════════════════════════════════════════════════════════╗");
            logger.info("║  Scenario 3: Level 3 - Request Help from Central (0.1%)        ║");
            logger.info("╚══════════════════════════════════════════════════════════════════╝");
            Agents.Agent bufferAgent3 = agents.percept(cortex().name("buffer-regulator"));
            bufferAgent3.promise(Agents.Dimension.PROMISER);
            logger.info("📝 Agent PROMISED to self-regulate buffer");
            Thread.sleep(2000);
            logger.info("⚠️  Local throttling FAILED - buffer still at 95%");
            bufferAgent3.breach(Agents.Dimension.PROMISER);
            logger.info("❌ Agent BREACHED promise - Cannot self-regulate!");
            logger.info("📞 Sending REQUEST to central platform for help...");
            logger.info("   Speech Act: REQUEST(SCALE_RESOURCES)");
            logger.info("   Description: Buffer exhausted despite throttling");
            logger.info("   Suggested Actions: [scale partition replicas, expand disk]");
            circuit.await();

            logger.info("");
            logger.info("🎬 Demo scenario complete - Watch for central responses above!");
            logger.info("   (If central platform is running, you'll see ACKNOWLEDGE → PROMISE → DELIVER)");

        } catch (InterruptedException e) {
            logger.info("Demo scenario interrupted");
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            logger.error("Demo scenario error", e);
        }
    }

    /**
     * Infer JMX endpoint from sidecar ID as fallback.
     * <p>
     * Convention (for demo only - production should use JMX_ENDPOINT env var):
     * - "producer-sidecar-1" → "localhost:11001"
     * - "producer-sidecar-2" → "localhost:11002"
     * - "consumer-sidecar-1" → "localhost:11101"
     */
    private static String inferJmxEndpoint(String sidecarId) {
        try {
            // Extract last numeric component
            String[] parts = sidecarId.split("-");
            String lastPart = parts[parts.length - 1];
            int number = Integer.parseInt(lastPart);

            // Determine base port from type prefix
            int basePort = sidecarId.startsWith("producer") ? 11000 : 11100;
            int port = basePort + number;

            return "localhost:" + port;
        } catch (Throwable e) {
            // Fallback to default
            logger.warn("Failed to infer JMX endpoint from sidecar ID: {}, using default", sidecarId);
            return "localhost:11001";
        }
    }
}
