package io.fullerstack.kafka.demo;

import io.fullerstack.kafka.demo.web.DashboardBroadcaster;
import io.fullerstack.kafka.demo.web.DashboardServer;
import io.fullerstack.kafka.producer.sensors.ProducerBufferMonitor;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import io.humainary.substrates.ext.serventis.ext.Queues;
import io.humainary.substrates.ext.serventis.ext.Queues.Queue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static io.humainary.substrates.api.Substrates.*;

/**
 * Kafka Observability Demo Application
 *
 * Demonstrates full OODA loop (Observe → Orient → Decide → Act) with:
 * - Layer 1 (OBSERVE): Probes, Services, Queues, Gauges
 * - Layer 2 (ORIENT): Monitors, Resources
 * - Layer 3 (DECIDE): Reporters
 * - Layer 4 (ACT): Agents (autonomous) + Actors (human approval)
 *
 * Phase 1: Console output (this class)
 * Phase 2: REST API + WebSocket (see api package)
 * Phase 3: Vue.js UI (see frontend/)
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

            // Create Serventis instruments (Conduits for dynamic entities)
            Conduit<Queue, Queues.Sign> queues = circuit.<Queue, Queues.Sign>conduit(
                cortex().name("queues"),
                Queues::composer
            );

            Conduit<Gauge, io.humainary.substrates.ext.serventis.ext.Gauges.Sign> gauges = circuit.<Gauge, io.humainary.substrates.ext.serventis.ext.Gauges.Sign>conduit(
                cortex().name("gauges"),
                io.humainary.substrates.ext.serventis.ext.Gauges::composer
            );

            Conduit<Counter, io.humainary.substrates.ext.serventis.ext.Counters.Sign> counters = circuit.<Counter, io.humainary.substrates.ext.serventis.ext.Counters.Sign>conduit(
                cortex().name("counters"),
                io.humainary.substrates.ext.serventis.ext.Counters::composer
            );

            logger.info("📡 Conduits created: Queues, Gauges, Counters");

            // Start WebSocket dashboard for real-time OODA visualization
            int dashboardPort = Integer.parseInt(System.getenv().getOrDefault("DASHBOARD_PORT", "8080"));
            try {
                DashboardServer.startServer(dashboardPort);
                logger.info("✅ Started WebSocket dashboard on port {}", dashboardPort);
                logger.info("   → Open http://localhost:{} to view live signals", dashboardPort);

                // Subscribe dashboard (proper Substrates Subscriber pattern)
                DashboardBroadcaster broadcaster = new DashboardBroadcaster();
                queues.subscribe(broadcaster.subscriber("queues"));
                gauges.subscribe(broadcaster.subscriber("gauges"));
                counters.subscribe(broadcaster.subscriber("counters"));
                logger.info("✅ Dashboard subscribed to signals (Queues, Gauges, Counters)");

            } catch (Throwable e) {
                logger.warn("⚠️  Dashboard server failed to start: {}", e.getMessage());
                logger.info("   Continuing without dashboard...");
            }

            // Get instruments for producer-1 buffer monitoring
            Queue bufferQueue = queues.percept(cortex().name("producer-1.buffer"));
            Gauge totalBytesGauge = gauges.percept(cortex().name("producer-1.buffer.total-bytes"));
            Counter exhaustedCounter = counters.percept(cortex().name("producer-1.buffer.exhausted"));
            Gauge batchSizeGauge = gauges.percept(cortex().name("producer-1.batch-size"));
            Gauge recordsPerRequestGauge = gauges.percept(cortex().name("producer-1.records-per-request"));

            logger.info("🎯 Instruments created for producer-1");

            // Start REAL producer buffer monitoring
            ProducerBufferMonitor bufferMonitor = new ProducerBufferMonitor(
                "producer-1",
                config.jmxUrl(),
                bufferQueue,
                totalBytesGauge,
                exhaustedCounter,
                batchSizeGauge,
                recordsPerRequestGauge
            );

            logger.info("🔍 Starting ProducerBufferMonitor (JMX: {})", config.jmxUrl());
            bufferMonitor.start();

            // Start Kafka producer to generate traffic and JMX metrics
            logger.info("🚀 Starting Kafka producer (client-id: producer-1)");
            SimpleKafkaProducer kafkaProducer = new SimpleKafkaProducer(
                config.kafkaBootstrap(),
                "producer-1",
                "observability-demo-topic"
            );
            kafkaProducer.start(10);  // 10 messages per second

            logger.info("");
            logger.info("✅ REAL observability active!");
            logger.info("📊 Monitoring: producer-1 buffer via JMX");
            logger.info("📡 Emitting: Queue, Gauge, Counter signals");
            logger.info("📨 Producer: Sending 10 msg/sec to 'observability-demo-topic'");
            logger.info("🌐 Dashboard: http://localhost:{}", dashboardPort);
            logger.info("");
            logger.info("🔥 Watch the REAL-TIME visualization:");
            logger.info("   - Open dashboard in browser to see live OODA loop");
            logger.info("   - Queue.OVERFLOW when buffer hits 95%");
            logger.info("   - Gauge.INCREMENT/DECREMENT for buffer changes");
            logger.info("   - Counter.INCREMENT for exhaustion events");
            logger.info("");
            logger.info("💡 Try chaos scenarios:");
            logger.info("   ./scenarios/01-broker-failure.sh");
            logger.info("   (Watch signal changes during broker failure!)");
            logger.info("");

            // Keep application running (wait for Ctrl+C)
            logger.info("Press Ctrl+C to stop...");
            Thread.currentThread().join();

            // Cleanup
            logger.info("Shutting down...");
            kafkaProducer.stop();
            bufferMonitor.stop();
            circuit.close();
            try {
                DashboardServer.stopServer();
                logger.info("✅ Dashboard server stopped");
            } catch (Throwable e) {
                // Ignore shutdown errors
            }
            logger.info("✅ Demo shutdown complete (sent {} messages)", kafkaProducer.getMessageCount());

        } catch (Throwable e) {
            logger.error("❌ Demo failed", e);
            System.exit(1);
        }
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                                                                  ║");
        System.out.println("║    Kafka Semiotic Observability Demo                           ║");
        System.out.println("║    Fullerstack + Humainary Substrates                           ║");
        System.out.println("║                                                                  ║");
        System.out.println("║    OODA Loop: Observe → Orient → Decide → Act                   ║");
        System.out.println("║                                                                  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
