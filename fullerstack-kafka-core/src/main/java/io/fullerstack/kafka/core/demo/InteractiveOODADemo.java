package io.fullerstack.kafka.core.demo;

import io.fullerstack.kafka.core.actors.*;
import io.fullerstack.kafka.core.command.Command;
import io.fullerstack.kafka.core.system.KafkaObservabilitySystem;
import io.humainary.substrates.ext.serventis.ext.Monitors;

import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Interactive demo showing the complete bidirectional OODA loop in action.
 *
 * <p><b>Demo Scenarios:</b>
 * <ol>
 *   <li><b>Normal Operation</b>: Cluster running smoothly</li>
 *   <li><b>Load Spike</b>: Producers overwhelm the system</li>
 *   <li><b>Automatic Throttling</b>: OODA loop detects and responds</li>
 *   <li><b>Manual Commands</b>: User issues circuit breaker, maintenance mode</li>
 *   <li><b>Recovery</b>: System stabilizes and resumes normal operation</li>
 * </ol>
 *
 * <p><b>Architecture Demonstrated:</b>
 * <pre>
 * UPWARD FLOW (Sensing):
 *   Partition queue overflow → Monitor.overflow()
 *   Broker CPU spike → Monitor.degraded()
 *   Cell hierarchy aggregates → ClusterHealthReporter.critical()
 *   AlertActor sends alerts
 *
 * DOWNWARD FLOW (Control):
 *   User/Actor issues Command.THROTTLE
 *   CommandHierarchy broadcasts downward
 *   All partition handlers receive command
 *   Producers reduce throughput 50%
 *   System stabilizes
 * </pre>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Run the demo
 * mvn exec:java -Dexec.mainClass="io.fullerstack.kafka.core.demo.InteractiveOODADemo"
 *
 * // Or from IDE: Run this main() method
 * }</pre>
 */
public class InteractiveOODADemo {

    private static final String CLUSTER_NAME = "demo-cluster";

    private KafkaClusterSimulator simulator;
    private KafkaObservabilitySystem ooda;
    private KafkaMetricsSimulator metricsSimulator;
    private ScheduledExecutorService monitoringScheduler;

    public static void main(String[] args) {
        InteractiveOODADemo demo = new InteractiveOODADemo();
        demo.run();
    }

    public void run() {
        printBanner();

        try {
            // Setup
            setupSimulator();
            setupOODASystem();
            setupMetricsSimulator();
            setupMonitoring();

            // Start
            simulator.start();
            ooda.start();
            metricsSimulator.start();

            // Run scenarios
            runInteractiveDemo();

        } catch (Exception e) {
            System.err.println("❌ Demo error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            cleanup();
        }
    }

    private void setupSimulator() {
        System.out.println("\n🏗️  STEP 1: Setting up Kafka cluster simulator...\n");

        simulator = new KafkaClusterSimulator(CLUSTER_NAME);

        // Add brokers
        simulator.addBroker("broker-1");
        simulator.addBroker("broker-2");

        // Add partitions
        simulator.addPartition("broker-1.orders.p0", 1000);
        simulator.addPartition("broker-1.orders.p1", 1000);
        simulator.addPartition("broker-1.payments.p0", 1000);
        simulator.addPartition("broker-2.orders.p0", 1000);

        // Add producers with AGGRESSIVE rates (will cause overflow)
        simulator.addProducer("producer-1", "broker-1.orders.p0", 100);
        simulator.addProducer("producer-2", "broker-1.orders.p1", 100);
        simulator.addProducer("producer-3", "broker-1.payments.p0", 80);
        simulator.addProducer("producer-4", "broker-2.orders.p0", 90);

        System.out.println("\n✅ Cluster simulator configured");
    }

    private void setupOODASystem() {
        System.out.println("\n🏗️  STEP 2: Setting up OODA observability system...\n");

        ooda = KafkaObservabilitySystem.builder()
            .clusterName(CLUSTER_NAME)
            .configManager(new DemoKafkaConfigManager())
            .pagerDutyClient(new DemoPagerDutyClient())
            .slackClient(new DemoSlackClient())
            .teamsClient(new DemoTeamsClient())
            .build();

        // Register command handlers for all partitions
        registerCommandHandlers();

        System.out.println("✅ OODA system configured");
    }

    private void setupMetricsSimulator() {
        System.out.println("\n🏗️  STEP 2.5: Setting up Serventis metrics simulator...\n");

        monitoringScheduler = Executors.newScheduledThreadPool(2);
        metricsSimulator = new KafkaMetricsSimulator(ooda, simulator, monitoringScheduler);

        System.out.println("✅ Metrics simulator configured");
        System.out.println("   → Using REAL production receptor classes:");
        System.out.println("      • LocalProducerBufferMonitor (from ProducerBufferMonitor)");
        System.out.println("      • LocalProducerSendObserver (from ProducerSendObserver)");
        System.out.println("   → Feeding all 7 Serventis instrument types:");
        System.out.println("      • Queues, Probes, Services, Gauges, Counters, Resources, Caches");
        System.out.println("      → Aggregating into Monitors (condition assessment)");
    }

    private void registerCommandHandlers() {
        String[] partitions = {
            "broker-1.orders.p0",
            "broker-1.orders.p1",
            "broker-1.payments.p0",
            "broker-2.orders.p0"
        };

        for (String partition : partitions) {
            ooda.getCommandHierarchy().registerHandler(partition, command -> {
                System.out.printf("   📨 [%s] Received command: %s%n", partition, command);

                switch (command) {
                    case THROTTLE -> {
                        // Find producer for this partition and throttle it
                        String producerId = getProducerForPartition(partition);
                        ProducerSimulator producer = simulator.getProducer(producerId);
                        if (producer != null) {
                            producer.throttle();
                        }
                        // Reduce broker load
                        String brokerId = partition.split("\\.")[0];
                        BrokerSimulator broker = simulator.getBroker(brokerId);
                        if (broker != null) {
                            broker.decreaseLoad();
                        }
                    }
                    case RESUME -> {
                        String producerId = getProducerForPartition(partition);
                        ProducerSimulator producer = simulator.getProducer(producerId);
                        if (producer != null) {
                            producer.resume();
                        }
                    }
                    case CIRCUIT_OPEN -> {
                        String producerId = getProducerForPartition(partition);
                        ProducerSimulator producer = simulator.getProducer(producerId);
                        if (producer != null) {
                            producer.openCircuit();
                        }
                    }
                    case CIRCUIT_CLOSE -> {
                        String producerId = getProducerForPartition(partition);
                        ProducerSimulator producer = simulator.getProducer(producerId);
                        if (producer != null) {
                            producer.closeCircuit();
                        }
                    }
                    case SHUTDOWN -> {
                        System.out.printf("   🛑 [%s] SHUTDOWN command received%n", partition);
                    }
                    case MAINTENANCE -> {
                        System.out.printf("   🔧 [%s] Entering maintenance mode%n", partition);
                    }
                }
            });
        }

        System.out.printf("   → Registered command handlers for %d partitions%n", partitions.length);
    }

    private void setupMonitoring() {
        System.out.println("\n🏗️  STEP 3: Setting up real-time monitoring...\n");

        // Monitor partition queues and broker health, emit signals
        monitoringScheduler.scheduleAtFixedRate(() -> {
            // Check all partition queues
            String[] partitions = {
                "broker-1.orders.p0",
                "broker-1.orders.p1",
                "broker-1.payments.p0",
                "broker-2.orders.p0"
            };

            for (String partition : partitions) {
                PartitionSimulator p = simulator.getPartition(partition);
                if (p != null && p.isOverflowing()) {
                    Monitors.Monitor monitor = ooda.getMonitors().percept(
                        cortex().name(partition)
                    );
                    monitor.degraded(Monitors.Dimension.MEASURED);
                }
            }

            // Check broker health
            BrokerSimulator broker1 = simulator.getBroker("broker-1");
            if (broker1 != null) {
                BrokerSimulator.BrokerHealth health = broker1.getHealth();
                if (health == BrokerSimulator.BrokerHealth.DEGRADED ||
                    health == BrokerSimulator.BrokerHealth.CRITICAL) {

                    Monitors.Monitor cpuMonitor = ooda.getMonitors().percept(
                        cortex().name("broker-1.jvm.heap")
                    );
                    cpuMonitor.degraded(Monitors.Dimension.MEASURED);
                }
            }

            ooda.await();  // Flush signals

        }, 1, 1, TimeUnit.SECONDS);  // Check every second

        System.out.println("✅ Real-time monitoring active");
    }

    private void runInteractiveDemo() {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("🎬 INTERACTIVE OODA LOOP DEMO");
        System.out.println("═".repeat(80));

        Scanner scanner = new Scanner(System.in);

        while (true) {
            printMenu();
            String choice = scanner.nextLine().trim();

            switch (choice) {
                case "1" -> simulator.printStatus();
                case "2" -> metricsSimulator.printInstrumentMetrics();
                case "3" -> issueThrottleCommand();
                case "4" -> issueResumeCommand();
                case "5" -> issueCircuitBreakerCommand();
                case "6" -> runAutomatedScenario();
                case "7" -> {
                    System.out.println("\n👋 Exiting demo...");
                    return;
                }
                default -> System.out.println("❌ Invalid choice, try again");
            }
        }
    }

    private void printMenu() {
        System.out.println("\n" + "─".repeat(80));
        System.out.println("📋 MENU:");
        System.out.println("  1. Show cluster status (brokers, partitions, producers)");
        System.out.println("  2. Show instrument metrics (Queues, Probes, Services, Gauges, Counters)");
        System.out.println("  3. Issue THROTTLE command (reduce load 50%)");
        System.out.println("  4. Issue RESUME command (restore normal rates)");
        System.out.println("  5. Issue CIRCUIT_OPEN command (stop all traffic)");
        System.out.println("  6. Run automated degradation → recovery scenario");
        System.out.println("  7. Exit");
        System.out.print("\nChoice: ");
    }

    private void issueThrottleCommand() {
        System.out.println("\n🔽 Issuing THROTTLE command...");
        ooda.getCommandHierarchy().broadcast(Command.THROTTLE);
        ooda.await();
        System.out.println("✅ THROTTLE command sent to all partitions");

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulator.printStatus();
    }

    private void issueResumeCommand() {
        System.out.println("\n🔼 Issuing RESUME command...");
        ooda.getCommandHierarchy().broadcast(Command.RESUME);
        ooda.await();
        System.out.println("✅ RESUME command sent to all partitions");

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulator.printStatus();
    }

    private void issueCircuitBreakerCommand() {
        System.out.println("\n⛔ Issuing CIRCUIT_OPEN command...");
        ooda.getCommandHierarchy().broadcast(Command.CIRCUIT_OPEN);
        ooda.await();
        System.out.println("✅ CIRCUIT_OPEN command sent - all traffic stopped");

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        simulator.printStatus();
    }

    private void runAutomatedScenario() {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("🎭 AUTOMATED SCENARIO: Degradation → Detection → Response → Recovery");
        System.out.println("═".repeat(80));

        try {
            System.out.println("\n[Phase 1] Normal operation (5 seconds)...");
            Thread.sleep(5000);
            simulator.printStatus();

            System.out.println("\n[Phase 2] Load spike - producers overwhelm system...");
            // Brokers will naturally degrade as queues fill
            Thread.sleep(5000);
            simulator.printStatus();

            System.out.println("\n[Phase 3] OODA loop responds - issuing THROTTLE...");
            issueThrottleCommand();

            System.out.println("\n[Phase 4] System stabilizes (5 seconds)...");
            Thread.sleep(5000);
            simulator.printStatus();

            System.out.println("\n[Phase 5] Recovery - issuing RESUME...");
            issueResumeCommand();

            System.out.println("\n✅ Scenario complete!");
            System.out.println("═".repeat(80));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String getProducerForPartition(String partition) {
        return switch (partition) {
            case "broker-1.orders.p0" -> "producer-1";
            case "broker-1.orders.p1" -> "producer-2";
            case "broker-1.payments.p0" -> "producer-3";
            case "broker-2.orders.p0" -> "producer-4";
            default -> null;
        };
    }

    private void cleanup() {
        System.out.println("\n🧹 Cleaning up...");
        if (metricsSimulator != null) {
            metricsSimulator.close();
        }
        if (monitoringScheduler != null) {
            monitoringScheduler.shutdown();
        }
        if (ooda != null) {
            ooda.close();
        }
        if (simulator != null) {
            simulator.close();
        }
        System.out.println("✅ Cleanup complete");
    }

    private void printBanner() {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("  ____   ____  ____    _      _");
        System.out.println(" / __ \\ / __ \\|  _ \\  / \\    | |");
        System.out.println("| |  | | |  | | | | |/ _ \\   | |     ___   ___  _ __");
        System.out.println("| |  | | |  | | | | / ___ \\  | |    / _ \\ / _ \\| '_ \\");
        System.out.println("| |__| | |__| | |_| / /   \\ \\ | |___| (_) | (_) | |_) |");
        System.out.println(" \\____/ \\____/|____/_/     \\_\\|______\\___/ \\___/| .__/");
        System.out.println("                                                 | |");
        System.out.println("    Bidirectional OODA Loop Interactive Demo    |_|");
        System.out.println("═".repeat(80));
        System.out.println("🎯 Observe → Orient → Decide → Act (with Feedback Loop!)");
        System.out.println("═".repeat(80));
    }

    // Mock implementations
    private static class DemoKafkaConfigManager implements KafkaConfigManager {
        @Override
        public int getProducerConfig(String producerId, String configKey) { return 5; }
        @Override
        public void updateProducerConfig(String producerId, String configKey, String configValue) {}
        @Override
        public int getConsumerConfig(String consumerId, String configKey) { return 0; }
        @Override
        public void updateConsumerConfig(String consumerId, String configKey, String configValue) {}
    }

    private static class DemoPagerDutyClient implements PagerDutyClient {
        @Override
        public void sendAlert(String serviceKey, String description, String severity) {
            System.out.printf("📟 PAGERDUTY: [%s] %s - %s%n", severity, serviceKey, description);
        }
    }

    private static class DemoSlackClient implements SlackClient {
        @Override
        public void sendMessage(String channel, String message) {
            System.out.printf("💬 SLACK: [%s] %s%n", channel, message);
        }
    }

    private static class DemoTeamsClient implements TeamsClient {
        @Override
        public void sendMessage(String channel, String message) {
            System.out.printf("👥 TEAMS: [%s] %s%n", channel, message);
        }
    }
}
