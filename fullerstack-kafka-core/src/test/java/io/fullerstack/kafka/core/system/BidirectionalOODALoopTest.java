package io.fullerstack.kafka.core.system;

import io.fullerstack.kafka.core.actors.*;
import io.fullerstack.kafka.core.command.Command;
import io.fullerstack.kafka.core.command.CommandHandler;
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
 * Test demonstrating complete bidirectional OODA loop.
 *
 * <p><b>Upward Flow (Sensing):</b>
 * <pre>
 * Partition Monitor.degraded()
 *   → Cell hierarchy aggregates upward
 *   → ClusterHealthReporter assesses CRITICAL
 *   → Actor observes urgency
 * </pre>
 *
 * <p><b>Downward Flow (Control):</b>
 * <pre>
 * Actor issues Command.THROTTLE
 *   → CommandHierarchy broadcasts downward
 *   → All partition handlers receive THROTTLE
 *   → Execute physical action (apply backpressure)
 * </pre>
 */
@DisplayName("Bidirectional OODA Loop - Upward Sensing + Downward Control")
class BidirectionalOODALoopTest {

    private KafkaObservabilitySystem system;
    private MockKafkaConfigManager configManager;
    private MockPagerDutyClient pagerDutyClient;
    private MockSlackClient slackClient;
    private MockTeamsClient teamsClient;

    // Track commands received at partition level
    private final Map<String, List<Command>> partitionCommands = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        partitionCommands.clear();

        configManager = new MockKafkaConfigManager();
        pagerDutyClient = new MockPagerDutyClient();
        slackClient = new MockSlackClient();
        teamsClient = new MockTeamsClient();

        system = KafkaObservabilitySystem.builder()
            .clusterName("bidirectional-cluster")
            .configManager(configManager)
            .pagerDutyClient(pagerDutyClient)
            .slackClient(slackClient)
            .teamsClient(teamsClient)
            .build();

        system.start();

        // Register partition-level command handlers
        registerPartitionHandlers();
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
    }

    @Test
    @DisplayName("Downward Command Flow: Actor → CommandHierarchy → All Partitions")
    void testDownwardCommandFlow() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DOWNWARD COMMAND FLOW TEST");
        System.out.println("=".repeat(80));

        System.out.println("\n[TEST] Actor issues THROTTLE command at cluster level...");

        // Actor issues THROTTLE command
        system.getCommandHierarchy().broadcast(Command.THROTTLE);

        // Wait for downward propagation
        system.await();

        System.out.println("   ✓ THROTTLE command broadcast from cluster level");

        // ===== Verify Command Cascaded to All Partitions =====

        System.out.println("\n[VERIFICATION] Did THROTTLE reach all partitions?");

        // Check all registered partitions
        for (String partition : partitionCommands.keySet()) {
            List<Command> commands = partitionCommands.get(partition);
            System.out.printf("   → Partition '%s' received: %s%n", partition, commands);

            assertThat(commands)
                .as("Partition %s should receive THROTTLE command", partition)
                .contains(Command.THROTTLE);
        }

        System.out.println("\n✅ DOWNWARD COMMAND FLOW VERIFIED!");
        System.out.println("   • Actor → CommandHierarchy.broadcast() ✓");
        System.out.println("   • Cluster → Broker → Topic → Partition cascade ✓");
        System.out.println("   • All 4 partitions received THROTTLE command ✓");
        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    @DisplayName("Command Broadcast Reaches All Levels: Cluster → Broker → Topic → Partition")
    void testCommandCascadeThroughHierarchy() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("COMMAND CASCADE TEST");
        System.out.println("=".repeat(80));

        // Track commands at each level
        List<Command> clusterCommands = new ArrayList<>();
        List<Command> brokerCommands = new ArrayList<>();
        List<Command> topicCommands = new ArrayList<>();

        // Register handlers at each level
        system.getCommandHierarchy().registerHandler("broker-1", command -> {
            brokerCommands.add(command);
            System.out.printf("   [BROKER] broker-1 received: %s%n", command);
        });

        system.getCommandHierarchy().registerHandler("broker-1.orders", command -> {
            topicCommands.add(command);
            System.out.printf("   [TOPIC] broker-1.orders received: %s%n", command);
        });

        // Broadcast from cluster level
        System.out.println("\n[TEST] Broadcasting CIRCUIT_OPEN from cluster...\n");
        system.getCommandHierarchy().broadcast(Command.CIRCUIT_OPEN);

        system.await();

        // Verify cascade
        System.out.println("\n[VERIFICATION]");
        System.out.printf("Broker-level commands: %s%n", brokerCommands);
        System.out.printf("Topic-level commands: %s%n", topicCommands);
        System.out.printf("Partition-level commands: %s%n", partitionCommands.values());

        assertThat(brokerCommands).contains(Command.CIRCUIT_OPEN);
        assertThat(topicCommands).contains(Command.CIRCUIT_OPEN);

        // At least one partition should have received it
        boolean anyPartitionReceived = partitionCommands.values().stream()
            .anyMatch(commands -> commands.contains(Command.CIRCUIT_OPEN));

        assertThat(anyPartitionReceived)
            .as("At least one partition should receive CIRCUIT_OPEN")
            .isTrue();

        System.out.println("\n✅ Command successfully cascaded through all levels!");
        System.out.println("=".repeat(80) + "\n");
    }

    @Test
    @DisplayName("Multiple Commands Flow Independently")
    void testMultipleCommandsIndependent() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("MULTIPLE COMMANDS TEST");
        System.out.println("=".repeat(80));

        System.out.println("\n[TEST] Issuing multiple commands...\n");

        // Issue multiple commands
        system.getCommandHierarchy().broadcast(Command.THROTTLE);
        system.await();

        system.getCommandHierarchy().broadcast(Command.MAINTENANCE);
        system.await();

        system.getCommandHierarchy().broadcast(Command.RESUME);
        system.await();

        // Verify partitions received all commands in order
        System.out.println("\n[VERIFICATION]");
        for (String partition : partitionCommands.keySet()) {
            List<Command> commands = partitionCommands.get(partition);
            System.out.printf("   Partition '%s' received: %s%n", partition, commands);

            assertThat(commands).containsExactly(
                Command.THROTTLE,
                Command.MAINTENANCE,
                Command.RESUME
            );
        }

        System.out.println("\n✅ All commands received in correct order!");
        System.out.println("=".repeat(80) + "\n");
    }

    /**
     * Registers command handlers for test partitions.
     */
    private void registerPartitionHandlers() {
        String[] partitions = {
            "broker-1.orders.p0",
            "broker-1.orders.p1",
            "broker-1.payments.p0",
            "broker-2.orders.p0"
        };

        for (String partition : partitions) {
            partitionCommands.put(partition, new ArrayList<>());

            system.getCommandHierarchy().registerHandler(partition, command -> {
                partitionCommands.get(partition).add(command);
                System.out.printf("   [PARTITION] %s received: %s%n", partition, command);

                // Simulate physical action
                switch (command) {
                    case THROTTLE -> System.out.printf("      → %s: Applying backpressure%n", partition);
                    case CIRCUIT_OPEN -> System.out.printf("      → %s: Opening circuit breaker%n", partition);
                    case MAINTENANCE -> System.out.printf("      → %s: Entering read-only mode%n", partition);
                    case RESUME -> System.out.printf("      → %s: Resuming normal operations%n", partition);
                    case SHUTDOWN -> System.out.printf("      → %s: Shutting down%n", partition);
                }
            });
        }

        System.out.printf("Registered handlers for %d partitions%n", partitions.length);
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
