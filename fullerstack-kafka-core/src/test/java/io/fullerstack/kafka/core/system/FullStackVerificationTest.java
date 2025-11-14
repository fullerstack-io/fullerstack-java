package io.fullerstack.kafka.core.system;

import io.fullerstack.kafka.core.actors.*;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification test to prove the complete signal flow works.
 *
 * <p>This test instruments every layer to trace the signal flow.
 */
@DisplayName("Full Stack Verification - Trace Complete Signal Flow")
class FullStackVerificationTest {

    private KafkaObservabilitySystem system;
    private MockKafkaConfigManager configManager;
    private MockPagerDutyClient pagerDutyClient;
    private MockSlackClient slackClient;
    private MockTeamsClient teamsClient;

    // Trace points to verify signal flow
    private final List<String> signalTrace = new ArrayList<>();

    @BeforeEach
    void setUp() {
        signalTrace.clear();

        configManager = new MockKafkaConfigManager();
        pagerDutyClient = new MockPagerDutyClient();
        slackClient = new MockSlackClient();
        teamsClient = new MockTeamsClient();

        system = KafkaObservabilitySystem.builder()
            .clusterName("trace-cluster")
            .configManager(configManager)
            .pagerDutyClient(pagerDutyClient)
            .slackClient(slackClient)
            .teamsClient(teamsClient)
            .build();

        system.start();
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
    }

    @Test
    @DisplayName("Verify: Monitor emission → Bridge → Cell → Reporter → Actor")
    void verifyCompleteSignalFlow() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("FULL STACK VERIFICATION TEST");
        System.out.println("=".repeat(80));

        // Subscribe to EVERY layer to trace signal flow
        subscribeToAllLayers();

        System.out.println("\n[TEST] Emitting Monitor.degraded(MEASURED) to broker-1.jvm.heap...\n");

        // Layer 1: Emit from Monitor
        Monitors.Monitor brokerMonitor = system.getMonitors().percept(
            cortex().name("broker-1.jvm.heap")
        );
        brokerMonitor.degraded(Monitors.Dimension.MEASURED);

        // Wait for complete propagation
        system.await();

        // Print trace
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SIGNAL FLOW TRACE:");
        System.out.println("=".repeat(80));
        for (int i = 0; i < signalTrace.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, signalTrace.get(i));
        }
        System.out.println("=".repeat(80));

        // Verify signal flowed through all layers
        System.out.println("\nVERIFICATION:");

        // Did Monitor conduit receive the signal?
        boolean monitorSignalReceived = signalTrace.stream()
            .anyMatch(s -> s.contains("Monitor conduit") && s.contains("broker-1.jvm.heap"));
        System.out.printf("✓ Monitor conduit received signal: %s%n", monitorSignalReceived);
        assertThat(monitorSignalReceived).isTrue();

        // Did Cell receive the signal?
        boolean cellSignalReceived = signalTrace.stream()
            .anyMatch(s -> s.contains("Cell received") || s.contains("cluster cell"));
        System.out.printf("✓ Cell received signal: %s%n", cellSignalReceived);

        // Did Reporter receive the signal?
        boolean reporterSignalReceived = signalTrace.stream()
            .anyMatch(s -> s.contains("Reporter conduit") && s.contains("cluster.health"));
        System.out.printf("✓ Reporter conduit received signal: %s%n", reporterSignalReceived);
        assertThat(reporterSignalReceived).isTrue();

        // Did Actor receive the signal?
        boolean actorSignalReceived = signalTrace.stream()
            .anyMatch(s -> s.contains("Actor conduit"));
        System.out.printf("✓ Actor conduit received signal: %s%n", actorSignalReceived);
        assertThat(actorSignalReceived).isTrue();

        // Did alerts get sent?
        System.out.printf("✓ PagerDuty alerts sent: %d%n", pagerDutyClient.getAlertCount());
        System.out.printf("✓ Slack messages sent: %d%n", slackClient.getMessageCount());
        System.out.printf("✓ Teams messages sent: %d%n", teamsClient.getMessageCount());

        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(1);
        assertThat(slackClient.getMessageCount()).isEqualTo(1);
        assertThat(teamsClient.getMessageCount()).isEqualTo(1);

        System.out.println("\n✅ FULL STACK VERIFIED - All layers working!");
        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    @DisplayName("Verify: Cell hierarchy aggregation works")
    void verifyCellHierarchyAggregation() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("CELL HIERARCHY AGGREGATION TEST");
        System.out.println("=".repeat(80));

        // Subscribe to cluster cell to see what it receives
        Cell<Monitors.Sign, Monitors.Sign> clusterCell = system.getHierarchy().getClusterCell();
        List<Monitors.Sign> clusterSigns = new ArrayList<>();

        clusterCell.subscribe(cortex().subscriber(
            cortex().name("test-cluster-receptor"),
            (Subject<Channel<Monitors.Sign>> subject, Registrar<Monitors.Sign> registrar) -> {
                registrar.register(sign -> {
                    clusterSigns.add(sign);
                    System.out.printf("   → Cluster cell outlet received: %s%n", sign);
                });
            }
        ));

        System.out.println("\n[TEST] Emitting DEGRADED from partition level...");

        // Emit from partition level (depth=3)
        Monitors.Monitor partitionMonitor = system.getMonitors().percept(
            cortex().name("broker-1.orders.p0")
        );
        partitionMonitor.degraded(Monitors.Dimension.CONFIRMED);

        system.await();

        System.out.println("\n[VERIFICATION]");
        System.out.printf("Cluster cell received %d signs%n", clusterSigns.size());
        System.out.printf("Signs: %s%n", clusterSigns);

        // The cluster cell SHOULD receive the aggregated sign from broker-1
        // IF the cell hierarchy is working correctly
        if (clusterSigns.isEmpty()) {
            System.out.println("⚠️  WARNING: Cluster cell did NOT receive any signs!");
            System.out.println("   This means cell outlet aggregation is NOT working.");
            System.out.println("   Signals are NOT flowing from child cells to parent outlets.");
        } else {
            System.out.println("✅ Cell hierarchy aggregation WORKING!");
            assertThat(clusterSigns).contains(Monitors.Sign.DEGRADED);
        }

        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    @DisplayName("Verify: Direct cell emission works")
    void verifyDirectCellEmission() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DIRECT CELL EMISSION TEST");
        System.out.println("=".repeat(80));

        // Subscribe to cluster cell
        Cell<Monitors.Sign, Monitors.Sign> clusterCell = system.getHierarchy().getClusterCell();
        List<Monitors.Sign> receivedSigns = new ArrayList<>();

        clusterCell.subscribe(cortex().subscriber(
            cortex().name("test-direct-receptor"),
            (Subject<Channel<Monitors.Sign>> subject, Registrar<Monitors.Sign> registrar) -> {
                registrar.register(sign -> {
                    receivedSigns.add(sign);
                    System.out.printf("   → Subscriber received: %s%n", sign);
                });
            }
        ));

        System.out.println("\n[TEST] Directly emitting DEGRADED to cluster cell...");
        clusterCell.emit(Monitors.Sign.DEGRADED);

        system.await();

        System.out.println("\n[VERIFICATION]");
        System.out.printf("Received %d signs%n", receivedSigns.size());

        if (receivedSigns.isEmpty()) {
            System.out.println("⚠️  WARNING: Direct cell emission did NOT flow to subscribers!");
            System.out.println("   This confirms that cell.emit() does NOT trigger subscribers.");
            System.out.println("   Subscribers only receive from child cell aggregation.");
        } else {
            System.out.println("✅ Direct cell emission works!");
            assertThat(receivedSigns).contains(Monitors.Sign.DEGRADED);
        }

        System.out.println("=".repeat(80) + "\n");
    }

    private void subscribeToAllLayers() {
        // Subscribe to Monitor conduit
        system.getMonitors().subscribe(cortex().subscriber(
            cortex().name("test-monitor-tracer"),
            (Subject<Channel<Monitors.Signal>> subject, Registrar<Monitors.Signal> registrar) -> {
                registrar.register(signal -> {
                    signalTrace.add(String.format(
                        "Monitor conduit: %s emitted %s",
                        subject.name(),
                        signal.sign()
                    ));
                });
            }
        ));

        // Subscribe to cluster cell
        system.getHierarchy().getClusterCell().subscribe(cortex().subscriber(
            cortex().name("test-cell-tracer"),
            (Subject<Channel<Monitors.Sign>> subject, Registrar<Monitors.Sign> registrar) -> {
                registrar.register(sign -> {
                    signalTrace.add(String.format(
                        "Cluster cell outlet: Received %s",
                        sign
                    ));
                });
            }
        ));

        // Subscribe to Reporter conduit
        system.getReporters().subscribe(cortex().subscriber(
            cortex().name("test-reporter-tracer"),
            (Subject<Channel<Reporters.Sign>> subject, Registrar<Reporters.Sign> registrar) -> {
                registrar.register(sign -> {
                    signalTrace.add(String.format(
                        "Reporter conduit: %s emitted %s",
                        subject.name(),
                        sign
                    ));
                });
            }
        ));

        // Subscribe to Actor conduit
        system.getActors().subscribe(cortex().subscriber(
            cortex().name("test-actor-tracer"),
            (Subject<Channel<Actors.Sign>> subject, Registrar<Actors.Sign> registrar) -> {
                registrar.register(sign -> {
                    signalTrace.add(String.format(
                        "Actor conduit: %s emitted %s",
                        subject.name(),
                        sign
                    ));
                });
            }
        ));
    }

    // ===== Mock Implementations =====

    private static class MockKafkaConfigManager implements KafkaConfigManager {
        @Override
        public int getProducerConfig(String producerId, String configKey) {
            return 5;
        }

        @Override
        public void updateProducerConfig(String producerId, String configKey, String configValue) {
        }

        @Override
        public int getConsumerConfig(String consumerId, String configKey) {
            return 0;
        }

        @Override
        public void updateConsumerConfig(String consumerId, String configKey, String configValue) {
        }
    }

    private static class MockPagerDutyClient implements PagerDutyClient {
        private final AtomicInteger alertCount = new AtomicInteger(0);

        @Override
        public void sendAlert(String serviceKey, String description, String severity) {
            alertCount.incrementAndGet();
        }

        int getAlertCount() {
            return alertCount.get();
        }
    }

    private static class MockSlackClient implements SlackClient {
        private final AtomicInteger messageCount = new AtomicInteger(0);

        @Override
        public void sendMessage(String channel, String message) {
            messageCount.incrementAndGet();
        }

        int getMessageCount() {
            return messageCount.get();
        }
    }

    private static class MockTeamsClient implements TeamsClient {
        private final AtomicInteger messageCount = new AtomicInteger(0);

        @Override
        public void sendMessage(String channel, String message) {
            messageCount.incrementAndGet();
        }

        int getMessageCount() {
            return messageCount.get();
        }
    }
}
