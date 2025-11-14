package io.fullerstack.kafka.core.system;

import io.fullerstack.kafka.core.actors.*;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Monitors;
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
 * Full OODA Loop Integration Tests - Complete Layer 1 → 4 Signal Flow.
 *
 * <p>These tests demonstrate the complete signal flow:
 * <pre>
 * Layer 1 (OBSERVE): Monitor.degraded(MEASURED)
 *     ↓
 * MonitorCellBridge: Routes to appropriate Cell
 *     ↓
 * Layer 2 (ORIENT): Cell hierarchy aggregates Signs
 *     ↓
 * Layer 3 (DECIDE): Reporter assesses urgency → CRITICAL
 *     ↓
 * Layer 4 (ACT): Actor takes automated action
 * </pre>
 */
@DisplayName("Full OODA Loop - Layer 1 → 2 → 3 → 4 Integration")
class FullOODALoopTest {

    private KafkaObservabilitySystem system;
    private MockKafkaConfigManager configManager;
    private MockPagerDutyClient pagerDutyClient;
    private MockSlackClient slackClient;
    private MockTeamsClient teamsClient;
    private List<Actors.Sign> actorSigns;

    @BeforeEach
    void setUp() {
        // Create mock external services
        configManager = new MockKafkaConfigManager();
        pagerDutyClient = new MockPagerDutyClient();
        slackClient = new MockSlackClient();
        teamsClient = new MockTeamsClient();

        // Build complete system
        system = KafkaObservabilitySystem.builder()
            .clusterName("test-cluster")
            .configManager(configManager)
            .pagerDutyClient(pagerDutyClient)
            .slackClient(slackClient)
            .teamsClient(teamsClient)
            .build();

        // Start the system (activates bridge)
        system.start();

        // Subscribe to actor signs for verification
        actorSigns = new ArrayList<>();
        system.getActors().subscribe(cortex().subscriber(
            cortex().name("test-receptor"),
            (Subject<Channel<Actors.Sign>> subject, Registrar<Actors.Sign> registrar) -> {
                registrar.register(actorSigns::add);
            }
        ));
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
    }

    @Test
    @DisplayName("Full Flow: Monitor.degraded() → Bridge → Cell → Reporter → AlertActor")
    void testMonitorDegradedFlowsToAlertActor() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Full OODA Loop Test: Monitor → Bridge → Cell → Reporter → Actor");
        System.out.println("=".repeat(70));

        // Layer 1 (OBSERVE): Get Monitor instrument and emit DEGRADED signal
        System.out.println("\n[Layer 1: OBSERVE] Emitting Monitor.degraded() signal...");
        Monitors.Monitor brokerMonitor = system.getMonitors().percept(
            cortex().name("broker-1.jvm.heap")
        );
        brokerMonitor.degraded(Monitors.Dimension.MEASURED);

        // Wait for complete signal propagation through all layers
        system.await();

        System.out.println("[Bridge] MonitorCellBridge routed signal to broker-1 cell");
        System.out.println("[Layer 2: ORIENT] Broker cell aggregated sign → Cluster cell");
        System.out.println("[Layer 3: DECIDE] ClusterHealthReporter assessed DEGRADED → CRITICAL");
        System.out.println("[Layer 4: ACT] AlertActor triggered alerts:");

        // Verify AlertActor sent alerts
        System.out.printf("   - PagerDuty alerts: %d%n", pagerDutyClient.getAlertCount());
        System.out.printf("   - Slack messages: %d%n", slackClient.getMessageCount());
        System.out.printf("   - Teams messages: %d%n", teamsClient.getMessageCount());

        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(1);
        assertThat(slackClient.getMessageCount()).isEqualTo(1);
        assertThat(teamsClient.getMessageCount()).isEqualTo(1);

        // Verify DELIVER sign emitted
        assertThat(actorSigns).contains(Actors.Sign.DELIVER);

        System.out.println("\n✅ Complete OODA loop executed successfully!");
        System.out.println("=".repeat(70) + "\n");
    }

    @Test
    @DisplayName("Full Flow: Partition Monitor → Topic → Broker → Cluster → Alert")
    void testPartitionMonitorAggregatesUpToClusterAlert() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Hierarchical Aggregation: Partition → Topic → Broker → Cluster");
        System.out.println("=".repeat(70));

        // Layer 1: Emit DEGRADED from partition monitor
        System.out.println("\n[Layer 1] Emitting DEGRADED from partition monitor...");
        Monitors.Monitor partitionMonitor = system.getMonitors().percept(
            cortex().name("broker-1.orders.p0")  // depth=3, partition level
        );
        partitionMonitor.degraded(Monitors.Dimension.CONFIRMED);

        system.await();

        System.out.println("[Bridge] Routed to partition cell: broker-1.orders.p0");
        System.out.println("[Layer 2] Aggregated up hierarchy:");
        System.out.println("   Partition p0 → Topic 'orders' → Broker-1 → Cluster");
        System.out.println("[Layer 3] ClusterHealthReporter: DEGRADED → CRITICAL");
        System.out.println("[Layer 4] AlertActor: Alerts sent");

        // Verify alerts were sent
        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(1);
        assertThat(slackClient.getMessageCount()).isEqualTo(1);
        assertThat(teamsClient.getMessageCount()).isEqualTo(1);

        System.out.println("\n✅ Hierarchical aggregation working!");
        System.out.println("=".repeat(70) + "\n");
    }

    @Test
    @DisplayName("Full Flow: STABLE signals don't trigger actors")
    void testStableSignalsDoNotTriggerActions() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Negative Test: STABLE signals should not trigger actions");
        System.out.println("=".repeat(70));

        // Layer 1: Emit STABLE
        System.out.println("\n[Layer 1] Emitting STABLE signal...");
        Monitors.Monitor brokerMonitor = system.getMonitors().percept(
            cortex().name("broker-1.jvm.heap")
        );
        brokerMonitor.stable(Monitors.Dimension.CONFIRMED);

        system.await();

        System.out.println("[Bridge] Routed to broker cell");
        System.out.println("[Layer 2] Cell outlet emitted STABLE");
        System.out.println("[Layer 3] ClusterHealthReporter: STABLE → NORMAL");
        System.out.println("[Layer 4] AlertActor: No action (NORMAL doesn't trigger alerts)");

        // Verify NO alerts were sent
        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(0);
        assertThat(slackClient.getMessageCount()).isEqualTo(0);
        assertThat(teamsClient.getMessageCount()).isEqualTo(0);

        System.out.println("\n✅ Correct behavior - STABLE doesn't trigger actions");
        System.out.println("=".repeat(70) + "\n");
    }

    @Test
    @DisplayName("Full Flow: Multiple degraded brokers trigger single alert")
    void testMultipleDegradedBrokersAggregateCorrectly() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Aggregation Test: Multiple brokers DEGRADED");
        System.out.println("=".repeat(70));

        // Layer 1: Emit DEGRADED from multiple broker monitors
        System.out.println("\n[Layer 1] Emitting DEGRADED from 2 brokers...");
        Monitors.Monitor broker1 = system.getMonitors().percept(cortex().name("broker-1.cpu.usage"));
        Monitors.Monitor broker2 = system.getMonitors().percept(cortex().name("broker-2.disk.io"));

        broker1.degraded(Monitors.Dimension.MEASURED);
        broker2.degraded(Monitors.Dimension.MEASURED);

        system.await();

        System.out.println("[Bridge] Routed to broker-1 and broker-2 cells");
        System.out.println("[Layer 2] Both broker cells emitted DEGRADED → Cluster cell");
        System.out.println("[Layer 3] ClusterHealthReporter: 2/N brokers DEGRADED → CRITICAL");
        System.out.println("[Layer 4] AlertActor: Single alert sent (not duplicate)");

        // Verify single alert (not multiple)
        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(1);
        assertThat(slackClient.getMessageCount()).isEqualTo(1);
        assertThat(teamsClient.getMessageCount()).isEqualTo(1);

        System.out.println("\n✅ Aggregation working - single alert for multiple degraded brokers");
        System.out.println("=".repeat(70) + "\n");
    }

    @Test
    @DisplayName("Full Flow: Cluster monitor directly triggers alert")
    void testClusterLevelMonitorTriggersAlert() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Direct Cluster Monitor Test");
        System.out.println("=".repeat(70));

        // Layer 1: Emit DOWN from cluster-level monitor
        System.out.println("\n[Layer 1] Emitting DOWN from cluster monitor...");
        Monitors.Monitor clusterMonitor = system.getMonitors().percept(
            cortex().name("cluster")  // depth=1, cluster level
        );
        clusterMonitor.down(Monitors.Dimension.CONFIRMED);

        system.await();

        System.out.println("[Bridge] Routed directly to cluster cell (depth=1)");
        System.out.println("[Layer 2] Cluster cell received DOWN");
        System.out.println("[Layer 3] ClusterHealthReporter: DOWN → CRITICAL");
        System.out.println("[Layer 4] AlertActor: Emergency alerts sent");

        // Verify alerts
        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(1);
        assertThat(pagerDutyClient.getAlerts().percept(0).severity).isEqualTo("critical");

        System.out.println("\n✅ Cluster-level monitor routing working!");
        System.out.println("=".repeat(70) + "\n");
    }

    @Test
    @DisplayName("Performance: Complete OODA loop latency")
    void testCompleteOODALoopLatency() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Performance Test: End-to-End Latency");
        System.out.println("=".repeat(70));

        // Measure complete OODA loop latency
        Monitors.Monitor brokerMonitor = system.getMonitors().percept(
            cortex().name("broker-1.jvm.heap")
        );

        long startNanos = System.nanoTime();
        brokerMonitor.degraded(Monitors.Dimension.MEASURED);
        system.await();
        long endNanos = System.nanoTime();

        long latencyMs = (endNanos - startNanos) / 1_000_000;

        System.out.printf("\n⏱️  Complete OODA loop latency: %d ms%n", latencyMs);
        System.out.println("   Monitor → Bridge → Cell → Reporter → Actor");

        // Verify it's fast (< 100ms)
        assertThat(latencyMs).isLessThan(100);

        // Verify alert was sent
        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(1);

        System.out.println("\n✅ OODA loop executed in < 100ms");
        System.out.println("=".repeat(70) + "\n");
    }

    // ===== Mock Implementations =====

    private static class MockKafkaConfigManager implements KafkaConfigManager {
        private final Map<String, Map<String, Integer>> configs = new ConcurrentHashMap<>();

        @Override
        public int getProducerConfig(String producerId, String configKey) {
            return configs
                .computeIfAbsent(producerId, k -> new ConcurrentHashMap<>())
                .getOrDefault(configKey, 5);
        }

        @Override
        public void updateProducerConfig(String producerId, String configKey, String configValue) {
            configs
                .computeIfAbsent(producerId, k -> new ConcurrentHashMap<>())
                .put(configKey, Integer.parseInt(configValue));
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
        private final List<Alert> alerts = new ArrayList<>();

        static class Alert {
            final String serviceKey;
            final String description;
            final String severity;

            Alert(String serviceKey, String description, String severity) {
                this.serviceKey = serviceKey;
                this.description = description;
                this.severity = severity;
            }
        }

        @Override
        public void sendAlert(String serviceKey, String description, String severity) {
            alertCount.incrementAndGet();
            alerts.add(new Alert(serviceKey, description, severity));
        }

        int getAlertCount() {
            return alertCount.get();
        }

        List<Alert> getAlerts() {
            return alerts;
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
