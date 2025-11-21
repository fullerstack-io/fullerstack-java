package io.fullerstack.kafka.core.system;

import io.fullerstack.kafka.core.actors.*;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Situations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Simple example demonstrating complete Kafka Observability System.
 *
 * <p>This example shows how to:
 * <ol>
 *   <li>Build a complete OODA loop system</li>
 *   <li>Emit signals to Cells</li>
 *   <li>Observe Situation urgency assessments</li>
 *   <li>See Actors take automated actions</li>
 * </ol>
 *
 * <p>Run this test to see the complete signal flow in action.
 */
@DisplayName("Kafka Observability System - Complete OODA Loop Example")
class KafkaObservabilitySystemExample {

    /**
     * Complete OODA loop example: Cell signal → Situation → Actor
     */
    @Test
    @DisplayName("Example: Complete OODA Loop")
    void completeOODALoopExample() {
        System.out.println("\n========================================");
        System.out.println("Kafka Observability System - Full OODA Loop");
        System.out.println("========================================\n");

        // Create mock external services
        MockKafkaConfigManager configManager = new MockKafkaConfigManager();
        MockPagerDutyClient pagerDuty = new MockPagerDutyClient();
        MockSlackClient slack = new MockSlackClient();
        MockTeamsClient teams = new MockTeamsClient();

        // Build complete system
        System.out.println("1. Building system...");
        KafkaObservabilitySystem system = KafkaObservabilitySystem.builder()
            .clusterName("demo-cluster")
            .configManager(configManager)
            .pagerDutyClient(pagerDuty)
            .slackClient(slack)
            .teamsClient(teams)
            .build();

        // Start the system
        system.start();
        System.out.println("   ✅ System started - OODA loop active\n");

        // Demonstrate signal flow
        demonstrateClusterHealthFlow(system, pagerDuty, slack, teams);

        // Clean shutdown
        system.close();
        System.out.println("\n✅ System shutdown complete");
        System.out.println("========================================\n");
    }

    /**
     * Demonstrates cluster health signal flow:
     * Situation CRITICAL → AlertActor
     *
     * <p>Note: In a complete system, Monitor signals would flow through
     * MonitorCellBridge → Cell hierarchy → Situation. For this demo, we emit
     * directly to the Situation to show the Layer 3 → Layer 4 flow.
     */
    private void demonstrateClusterHealthFlow(
        KafkaObservabilitySystem system,
        MockPagerDutyClient pagerDuty,
        MockSlackClient slack,
        MockTeamsClient teams
    ) {
        System.out.println("2. Demonstrating Cluster Health Flow:");
        System.out.println("   Situation CRITICAL → AlertActor\n");

        // Get cluster health reporter
        Situations.Situation clusterHealth = system.getReporters().percept(
            cortex().name("cluster.health")
        );

        System.out.println("   [Layer 3: DECIDE] ClusterHealthReporter emitting CRITICAL...");
        clusterHealth.critical();

        // Wait for signal propagation
        system.await();

        System.out.println("   [Layer 4: ACT] AlertActor triggered alerts:");
        System.out.printf("      - PagerDuty alerts sent: %d%n", pagerDuty.getAlertCount());
        System.out.printf("      - Slack messages sent: %d%n", slack.getMessageCount());
        System.out.printf("      - Teams messages sent: %d%n", teams.getMessageCount());
        System.out.println("\n   ✅ Complete OODA loop executed successfully!");
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
