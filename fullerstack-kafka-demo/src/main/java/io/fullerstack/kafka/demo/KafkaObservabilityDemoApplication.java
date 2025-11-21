package io.fullerstack.kafka.demo;

import io.fullerstack.kafka.demo.web.DashboardBroadcaster;
import io.fullerstack.kafka.demo.web.DashboardServer;
import io.fullerstack.kafka.producer.sensors.ProducerBufferMonitor;
import io.fullerstack.kafka.producer.sensors.ProducerHealthDetector;
import io.fullerstack.kafka.producer.agents.ProducerSelfRegulator;
import io.fullerstack.kafka.producer.sidecar.AgentCoordinationBridge;
import io.fullerstack.kafka.producer.sidecar.KafkaCentralCommunicator;
import io.fullerstack.kafka.producer.sidecar.SidecarResponseListener;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Agents.Agent;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
import io.humainary.substrates.ext.serventis.ext.Queues;
import io.humainary.substrates.ext.serventis.ext.Queues.Queue;
import io.humainary.substrates.ext.serventis.ext.Services;
import io.humainary.substrates.ext.serventis.ext.Services.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.humainary.substrates.api.Substrates.*;

/**
 * Kafka Observability Demo Application - Self-Healing Producer
 *
 * Demonstrates autonomous producer self-regulation using Substrates signals:
 * - ProducerBufferMonitor: Collects JMX metrics, emits Queue/Gauge/Counter signals
 * - ProducerHealthDetector: Analyzes buffer patterns, emits Monitor signals (STABLE, DEGRADED)
 * - ProducerSelfRegulator: Autonomously throttles/resumes based on health signals
 *
 * Self-Healing Flow:
 * 1. Buffer hits 95% → Queue.OVERFLOW signal
 * 2. Health detector emits → Monitor.DEGRADED signal
 * 3. Self-regulator receives signal → Throttles producer (Agent.PROMISE)
 * 4. Buffer recovers → Monitor.STABLE signal
 * 5. Self-regulator receives signal → Resumes producer (Agent.FULFILL)
 *
 * Uses Agents API (Promise Theory) for autonomous closed-loop feedback.
 * WebSocket dashboard shows real-time signals and self-healing actions.
 */
public class KafkaObservabilityDemoApplication {

    private static final Logger logger = LoggerFactory.getLogger(KafkaObservabilityDemoApplication.class);

    public static void main(String[] args) {
        printBanner();

        // Parse config
        DemoConfig config = DemoConfig.fromArgs(args);

        logger.info("🚀 Starting Kafka Observability Demo");
        logger.info("📊 Kafka brokers: {}", config.kafkaBootstrap());
        logger.info("🔍 JMX URL: {}", config.jmxUrl());
        logger.info("🎯 Mode: {}", config.mode());
        logger.info("");

        try {
            // Auto-discover Kafka cluster topology
            logger.info("🔍 AUTO-DISCOVERING Kafka cluster topology...");
            KafkaTopologyDiscovery discovery = new KafkaTopologyDiscovery(config.kafkaBootstrap());

            var brokers = discovery.discoverBrokers();
            var topics = discovery.discoverTopics();
            String clusterId = discovery.discoverClusterId();

            logger.info("✅ Cluster ID: {}", clusterId);
            logger.info("✅ Discovered {} brokers, {} topics", brokers.size(), topics.size());
            logger.info("");

            // Create Substrates Circuit
            Circuit circuit = cortex().circuit(cortex().name("kafka-demo"));

            logger.info("⚡ Circuit created: {}", circuit);
            logger.info("");

            // Create Substrates conduits for signal flow
            Conduit<Queue, Queues.Sign> queues = circuit.<Queue, Queues.Sign>conduit(
                cortex().name("queues"),
                Queues::composer
            );

            Conduit<Gauge, Gauges.Sign> gauges = circuit.<Gauge, Gauges.Sign>conduit(
                cortex().name("gauges"),
                Gauges::composer
            );

            Conduit<Counter, Counters.Sign> counters = circuit.<Counter, Counters.Sign>conduit(
                cortex().name("counters"),
                Counters::composer
            );

            Conduit<Monitor, Monitors.Signal> monitors = circuit.<Monitor, Monitors.Signal>conduit(
                cortex().name("monitors"),
                Monitors::composer
            );

            Conduit<Agent, Agents.Signal> agents = circuit.<Agent, Agents.Signal>conduit(
                cortex().name("agents"),
                Agents::composer
            );

            Conduit<Actors.Actor, Actors.Sign> actors = circuit.<Actors.Actor, Actors.Sign>conduit(
                cortex().name("actors"),
                Actors::composer
            );

            Conduit<Service, Services.Signal> services = circuit.<Service, Services.Signal>conduit(
                cortex().name("services"),
                Services::composer
            );

            logger.info("📡 Signal conduits created: Queues, Gauges, Counters, Monitors, Agents, Actors, Services");

            // Start WebSocket dashboard for real-time OODA visualization
            int dashboardPort = Integer.parseInt(System.getenv().getOrDefault("DASHBOARD_PORT", "8080"));
            try {
                DashboardServer.startServer(dashboardPort);
                logger.info("✅ Started WebSocket dashboard on port {}", dashboardPort);
                logger.info("   → Open http://localhost:{} to view live signals", dashboardPort);

                // Subscribe dashboard to all signal types
                DashboardBroadcaster broadcaster = new DashboardBroadcaster();
                queues.subscribe(broadcaster.subscriber("queues"));
                gauges.subscribe(broadcaster.subscriber("gauges"));
                counters.subscribe(broadcaster.subscriber("counters"));
                monitors.subscribe(broadcaster.subscriber("monitors"));
                agents.subscribe(broadcaster.subscriber("agents"));
                actors.subscribe(broadcaster.subscriber("actors"));
                services.subscribe(broadcaster.subscriber("services"));
                logger.info("✅ Dashboard subscribed to all signals (Queues, Gauges, Counters, Monitors, Agents, Actors, Services)");

            } catch (Throwable e) {
                logger.warn("⚠️  Dashboard server failed to start: {}", e.getMessage());
                logger.info("   Continuing without dashboard...");
            }

            // ============================================================
            // Buffer Monitoring: Get instruments for producer-1
            // ============================================================
            Queue bufferQueue = queues.percept(cortex().name("producer-1.buffer"));
            Gauge totalBytesGauge = gauges.percept(cortex().name("producer-1.buffer.total-bytes"));
            Counter exhaustedCounter = counters.percept(cortex().name("producer-1.buffer.exhausted"));
            Gauge batchSizeGauge = gauges.percept(cortex().name("producer-1.batch-size"));
            Gauge recordsPerRequestGauge = gauges.percept(cortex().name("producer-1.records-per-request"));

            logger.info("🎯 Buffer monitoring instruments created for producer-1");

            // ============================================================
            // Health Detection: Analyze buffer patterns
            // ============================================================
            Monitor healthMonitor = monitors.percept(cortex().name("producer-1.health"));
            ProducerHealthDetector healthDetector = new ProducerHealthDetector(
                "producer-1",
                healthMonitor
            );

            logger.info("🎯 Health detector created");

            // ============================================================
            // Services: Track sidecar component health (meta-monitoring)
            // ============================================================
            Service bufferMonitorService = services.percept(cortex().name("sidecar.buffer-monitor"));
            Service healthDetectorService = services.percept(cortex().name("sidecar.health-detector"));
            Service coordinationService = services.percept(cortex().name("sidecar.coordination-bridge"));

            logger.info("🔧 Service percepts created for sidecar meta-monitoring");

            // ============================================================
            // Start JMX-based producer buffer monitoring
            // ============================================================
            ProducerBufferMonitor bufferMonitor = new ProducerBufferMonitor(
                "producer-1",
                config.jmxUrl(),
                bufferQueue,
                totalBytesGauge,
                exhaustedCounter,
                batchSizeGauge,
                recordsPerRequestGauge,
                bufferMonitorService  // Pass service for lifecycle tracking
            );

            // Wire buffer monitor signals to health detector
            queues.subscribe(cortex().subscriber(
                cortex().name("health-detector-queue-subscriber"),
                (subject, registrar) -> {
                    registrar.register(signal -> {
                        if (subject.name().toString().contains("producer-1.buffer")) {
                            if (signal == Queues.Sign.OVERFLOW) {
                                healthDetector.onBufferOverflow();
                            } else if (signal == Queues.Sign.ENQUEUE) {
                                healthDetector.onBufferNormal();
                            } else if (signal == Queues.Sign.UNDERFLOW || signal == Queues.Sign.DEQUEUE) {
                                healthDetector.onBufferUnderflow();
                            }
                        }
                    });
                }
            ));

            counters.subscribe(cortex().subscriber(
                cortex().name("health-detector-counter-subscriber"),
                (subject, registrar) -> {
                    registrar.register(signal -> {
                        if (subject.name().toString().contains("exhausted") &&
                            signal == Counters.Sign.INCREMENT) {
                            healthDetector.onBufferExhaustion();
                        }
                    });
                }
            ));

            logger.info("🔍 Starting ProducerBufferMonitor (JMX: {})", config.jmxUrl());
            bufferMonitor.start();

            // ============================================================
            // Autonomous Agent: Monitor health and trigger breach on degradation
            // ============================================================
            Agent sidecarAgent = agents.percept(cortex().name("sidecar.producer-1"));

            // Subscribe to health monitor signals → trigger Agent BREACH on DEGRADED
            monitors.subscribe(cortex().subscriber(
                cortex().name("sidecar-agent-subscriber"),
                (subject, registrar) -> {
                    registrar.register(signal -> {
                        if (subject.name().toString().contains("producer-1.health")) {
                            switch (signal.sign()) {
                                case DEGRADED, DEFECTIVE -> {
                                    logger.warn("[SIDECAR] Health {} detected → Agent BREACH (cannot regulate locally)", signal.sign());
                                    sidecarAgent.breach(Agents.Dimension.PROMISER);
                                    // Signal processes asynchronously - will be picked up by coordination bridge
                                }
                            }
                        }
                    });
                }
            ));

            logger.info("✅ Sidecar agent monitoring health signals (will breach → escalate on degradation)");

            // ============================================================
            // Distributed Coordination: Escalation to central platform
            // ============================================================
            String requestTopic = System.getenv().getOrDefault("REQUEST_TOPIC", "observability.speech-acts");
            String responseTopic = System.getenv().getOrDefault("RESPONSE_TOPIC", "observability.responses");

            // Create central communicator for sending escalation requests
            KafkaCentralCommunicator centralCommunicator = new KafkaCentralCommunicator(
                config.kafkaBootstrap(),
                requestTopic
            );
            logger.info("✅ Created Kafka central communicator (topic: {})", requestTopic);

            // Create coordination bridge to connect Agents → Actors (breach → REQUEST)
            AgentCoordinationBridge coordinationBridge = new AgentCoordinationBridge(
                agents,
                actors,
                centralCommunicator,
                "producer-1"
            );
            logger.info("✅ Created AgentCoordinationBridge (Promise Theory + Speech Act Theory)");

            // Create and start response listener for central platform replies
            SidecarResponseListener responseListener = new SidecarResponseListener(
                config.kafkaBootstrap(),
                responseTopic,
                actors,
                "producer-1"
            );
            Thread responseThread = Thread.ofVirtual()
                .name("response-listener-producer-1")
                .start(responseListener);
            logger.info("✅ Started SidecarResponseListener (topic: {})", responseTopic);

            logger.info("");
            logger.info("✅ SELF-HEALING PRODUCER ACTIVE!");
            logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            logger.info("");
            logger.info("📊 Buffer Monitor: JMX metrics from producer-1 at {}", config.jmxUrl());
            logger.info("   └─ Signals: Queue, Gauge, Counter (buffer state)");
            logger.info("");
            logger.info("🔍 Health Detector: Analyzing buffer patterns");
            logger.info("   └─ Signals: Monitor.STABLE, Monitor.DEGRADED, Monitor.DEFECTIVE");
            logger.info("");
            logger.info("⚡ Sidecar Agent: Read-only monitoring (no direct control)");
            logger.info("   └─ Detects: Monitor.DEGRADED → Agent.BREACH");
            logger.info("   └─ Cannot regulate locally (read-only JMX access)");
            logger.info("   └─ Signals: Agent.BREACH → triggers escalation");
            logger.info("");
            logger.info("🎭 Coordination Bridge: Escalation to central platform");
            logger.info("   └─ Agent.BREACH → Actor.REQUEST → Kafka escalation");
            logger.info("   └─ Listens for: Actor.ACKNOWLEDGE, Actor.PROMISE, Actor.DELIVER");
            logger.info("");
            logger.info("🌐 Dashboard: http://localhost:{}", dashboardPort);
            logger.info("");
            logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            logger.info("");
            logger.info("🚀 SIDECAR CAPABILITIES (Read-Only Monitoring):");
            logger.info("   ✅ Monitor producer buffer via JMX (standard metrics)");
            logger.info("   ✅ Detect degradation patterns (Queue/Monitor signals)");
            logger.info("   ✅ Emit autonomous promises (Agent API - Promise Theory)");
            logger.info("   ✅ Escalate to central when local action fails (Actor API)");
            logger.info("   ❌ CANNOT directly throttle producer (requires custom MBean)");
            logger.info("");
            logger.info("📊 What This Demo Shows:");
            logger.info("   1. JMX monitoring detects buffer state");
            logger.info("   2. Health detector analyzes patterns → Monitor.DEGRADED");
            logger.info("   3. Agent makes promise to regulate → Agent.PROMISE");
            logger.info("   4. If agent cannot fix locally → Agent.BREACH");
            logger.info("   5. Coordination bridge escalates → Actor.REQUEST");
            logger.info("   6. Central platform receives escalation via Kafka");
            logger.info("");
            logger.info("🎯 Value Proposition:");
            logger.info("   - Intelligent detection (99% of the value)");
            logger.info("   - Pattern-based health assessment");
            logger.info("   - Autonomous local promises when possible");
            logger.info("   - Smart escalation when local action insufficient");
            logger.info("   - Central coordination for cluster-level actions");
            logger.info("");
            logger.info("📡 Real-time signals to watch:");
            logger.info("   Buffer: Queue.OVERFLOW, Gauge.INCREMENT, Counter.INCREMENT");
            logger.info("   Health: Monitor.DEGRADED, Monitor.STABLE, Monitor.CONVERGING");
            logger.info("   Agents: Agent.PROMISE, Agent.FULFILL, Agent.BREACH");
            logger.info("   Actors: Actor.REQUEST, Actor.ACKNOWLEDGE, Actor.DELIVER");
            logger.info("   Services: Service.START, Service.CALL, Service.SUCCESS, Service.FAIL");
            logger.info("");
            logger.info("🔧 Meta-Monitoring (Sidecar Self-Awareness):");
            logger.info("   buffer-monitor: Tracks JMX connection health");
            logger.info("   health-detector: Tracks analysis component health");
            logger.info("   coordination-bridge: Tracks escalation component health");
            logger.info("");

            // Start periodic broadcast of real message count and rate
            java.util.concurrent.ScheduledExecutorService statsScheduler =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

            statsScheduler.scheduleAtFixedRate(() -> {
                try {
                    long messageCount = io.fullerstack.kafka.demo.chaos.ChaosController.getMessageCount();
                    int rate = io.fullerstack.kafka.demo.chaos.ChaosController.getCurrentRate();
                    int bufferUtilization = io.fullerstack.kafka.demo.chaos.ChaosController.getBufferUtilization();

                    if (messageCount >= 0 && rate >= 0) {
                        io.fullerstack.kafka.demo.web.DashboardWebSocket.broadcastEvent("producer-stats",
                            java.util.Map.of(
                                "messageCount", messageCount,
                                "rate", rate,
                                "bufferUtilization", bufferUtilization
                            )
                        );
                    }
                } catch (java.lang.Exception e) {
                    // Silently ignore - JMX might not be ready yet
                }
            }, 1, 2, java.util.concurrent.TimeUnit.SECONDS);

            logger.info("📈 Broadcasting real producer stats every 2 seconds");

            // Keep application running (wait for Ctrl+C)
            logger.info("Press Ctrl+C to stop...");
            Thread.currentThread().join();

            // Cleanup
            logger.info("Shutting down...");
            logger.info("Stopping response listener...");
            responseListener.close();
            logger.info("Stopping coordination bridge...");
            coordinationBridge.close();
            logger.info("Stopping health detector...");
            healthDetector.close();
            logger.info("Stopping buffer monitor...");
            bufferMonitor.stop();
            logger.info("Closing central communicator...");
            centralCommunicator.close();
            logger.info("Closing circuit...");
            circuit.close();
            try {
                DashboardServer.stopServer();
                logger.info("✅ Dashboard server stopped");
            } catch (Throwable e) {
                // Ignore shutdown errors
            }
            logger.info("✅ Self-healing system shutdown complete");

        } catch (Throwable e) {
            logger.error("❌ Demo failed", e);
            System.exit(1);
        }
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                                                                  ║");
        System.out.println("║    Kafka Self-Healing Producer Demo                             ║");
        System.out.println("║    Fullerstack + Humainary Substrates                            ║");
        System.out.println("║                                                                  ║");
        System.out.println("║    Autonomous producer regulation using semiotic signals         ║");
        System.out.println("║                                                                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
