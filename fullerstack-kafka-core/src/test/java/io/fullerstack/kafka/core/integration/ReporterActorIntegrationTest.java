package io.fullerstack.kafka.core.integration;

import io.fullerstack.kafka.core.actors.AlertActor;
import io.fullerstack.kafka.core.actors.PagerDutyClient;
import io.fullerstack.kafka.core.actors.SlackClient;
import io.fullerstack.kafka.core.actors.TeamsClient;
import io.fullerstack.kafka.core.actors.ThrottleActor;
import io.fullerstack.kafka.core.actors.KafkaConfigManager;
import io.fullerstack.kafka.core.reporters.ClusterHealthReporter;
import io.fullerstack.kafka.core.reporters.ProducerHealthReporter;
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
 * Integration tests demonstrating the complete OODA loop:
 * OBSERVE → ORIENT → DECIDE → ACT
 *
 * <p>Tests the full signal flow:
 * <pre>
 * Layer 1-2 (Monitors) → Layer 3 (Reporters) → Layer 4 (Actors)
 *      OBSERVE/ORIENT  →      DECIDE         →      ACT
 * </pre>
 */
@DisplayName("Reporter → Actor Integration Tests")
class ReporterActorIntegrationTest {

    private Circuit monitorCircuit;
    private Circuit reporterCircuit;
    private Circuit actorCircuit;

    private Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private Conduit<Actors.Actor, Actors.Sign> actors;

    private Cell<Monitors.Sign, Monitors.Sign> producerRootCell;
    private Cell<Monitors.Sign, Monitors.Sign> clusterRootCell;

    private ProducerHealthReporter producerHealthReporter;
    private ClusterHealthReporter clusterHealthReporter;
    private ThrottleActor throttleActor;
    private AlertActor alertActor;

    private MockKafkaConfigManager configManager;
    private MockPagerDutyClient pagerDutyClient;
    private MockSlackClient slackClient;
    private MockTeamsClient teamsClient;

    private List<Actors.Sign> actorSigns;

    // ===== Mock Implementations =====

    private static class MockKafkaConfigManager implements KafkaConfigManager {
        private final Map<String, Map<String, Integer>> producerConfigs = new ConcurrentHashMap<>();
        private final List<ConfigUpdate> updates = new ArrayList<>();

        static class ConfigUpdate {
            final String producerId;
            final String configKey;
            final String configValue;

            ConfigUpdate(String producerId, String configKey, String configValue) {
                this.producerId = producerId;
                this.configKey = configKey;
                this.configValue = configValue;
            }
        }

        @Override
        public int getProducerConfig(String producerId, String configKey) {
            return producerConfigs
                .computeIfAbsent(producerId, k -> new ConcurrentHashMap<>())
                .getOrDefault(configKey, getDefaultValue(configKey));
        }

        @Override
        public void updateProducerConfig(String producerId, String configKey, String configValue) {
            updates.add(new ConfigUpdate(producerId, configKey, configValue));
            producerConfigs
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

        List<ConfigUpdate> getUpdates() {
            return updates;
        }

        void setProducerConfig(String producerId, String configKey, int value) {
            producerConfigs
                .computeIfAbsent(producerId, k -> new ConcurrentHashMap<>())
                .put(configKey, value);
        }

        private int getDefaultValue(String configKey) {
            return switch (configKey) {
                case "max.in.flight.requests.per.connection" -> 5;
                case "linger.ms" -> 0;
                default -> 0;
            };
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

    @BeforeEach
    void setUp() {
        // Create circuits
        monitorCircuit = cortex().circuit(cortex().name("monitors"));

        reporterCircuit = cortex().circuit(cortex().name("reporters"));
        reporters = reporterCircuit.conduit(cortex().name("reporters"), Reporters::composer);

        actorCircuit = cortex().circuit(cortex().name("actors"));
        actors = actorCircuit.conduit(cortex().name("actors"), Actors::composer);

        // Create root cells for aggregation
        producerRootCell = monitorCircuit.cell(
            cortex().name("producer-root"),
            Composer.pipe(),
            Composer.pipe(),
            cortex().pipe((Monitors.Sign sign) -> {})
        );

        clusterRootCell = monitorCircuit.cell(
            cortex().name("cluster-root"),
            Composer.pipe(),
            Composer.pipe(),
            cortex().pipe((Monitors.Sign sign) -> {})
        );

        // Create reporters
        producerHealthReporter = new ProducerHealthReporter(producerRootCell, reporters);
        clusterHealthReporter = new ClusterHealthReporter(clusterRootCell, reporters);

        // Create mock clients
        configManager = new MockKafkaConfigManager();
        pagerDutyClient = new MockPagerDutyClient();
        slackClient = new MockSlackClient();
        teamsClient = new MockTeamsClient();

        // Create actors
        throttleActor = new ThrottleActor(reporters, actors, configManager);
        alertActor = new AlertActor(
            reporters,
            actors,
            pagerDutyClient,
            slackClient,
            teamsClient,
            "test-cluster"
        );

        // Track actor signs
        actorSigns = new ArrayList<>();
        actors.subscribe(cortex().subscriber(
            cortex().name("test-receptor"),
            (Subject<Channel<Actors.Sign>> subject, Registrar<Actors.Sign> registrar) -> {
                registrar.register(actorSigns::add);
            }
        ));
    }

    @AfterEach
    void tearDown() {
        if (throttleActor != null) throttleActor.close();
        if (alertActor != null) alertActor.close();
        if (producerHealthReporter != null) producerHealthReporter.close();
        if (clusterHealthReporter != null) clusterHealthReporter.close();
        if (actorCircuit != null) actorCircuit.close();
        if (reporterCircuit != null) reporterCircuit.close();
        if (monitorCircuit != null) monitorCircuit.close();
    }

    @Test
    @DisplayName("Complete OODA Loop: Producer DEGRADED → Reporter CRITICAL → ThrottleActor")
    void testProducerDegradedTriggersThrottle() {
        // Given: Producer configured with defaults
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 5);
        configManager.setProducerConfig("producer-1", "linger.ms", 10);

        // OBSERVE → ORIENT → DECIDE: Simulate producer health reporter emitting CRITICAL
        // In real system: Monitor DEGRADED → Cell aggregation → Reporter assessment → CRITICAL
        // For integration test: Directly emit CRITICAL from reporter channel
        Reporters.Reporter producerHealth = reporters.percept(cortex().name("producer.producer-1.health"));
        producerHealth.critical();

        reporterCircuit.await();
        actorCircuit.await();

        // ACT: ThrottleActor should throttle producer
        List<MockKafkaConfigManager.ConfigUpdate> updates = configManager.getUpdates();
        assertThat(updates).hasSize(2);

        // Verify max.in.flight reduced by 50% (5 → 2)
        assertThat(updates.get(0).configKey).isEqualTo("max.in.flight.requests.per.connection");
        assertThat(updates.get(0).configValue).isEqualTo("2");

        // Verify linger.ms increased by 2x (10 → 20)
        assertThat(updates.get(1).configKey).isEqualTo("linger.ms");
        assertThat(updates.get(1).configValue).isEqualTo("20");

        // Verify DELIVER sign emitted (successful throttle)
        assertThat(actorSigns).contains(Actors.Sign.DELIVER);
    }

    @Test
    @DisplayName("Complete OODA Loop: Cluster DOWN → Reporter CRITICAL → AlertActor")
    void testClusterDownTriggersAlerts() {
        // OBSERVE → ORIENT → DECIDE: Simulate cluster health reporter emitting CRITICAL
        // In real system: Monitor DOWN → Cell aggregation → Reporter assessment → CRITICAL
        // For integration test: Directly emit CRITICAL from reporter channel
        Reporters.Reporter clusterHealth = reporters.percept(cortex().name("cluster.health"));
        clusterHealth.critical();

        reporterCircuit.await();
        actorCircuit.await();

        // ACT: AlertActor should send alerts
        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(1);
        assertThat(slackClient.getMessageCount()).isEqualTo(1);
        assertThat(teamsClient.getMessageCount()).isEqualTo(1);

        // Verify PagerDuty alert details
        List<MockPagerDutyClient.Alert> alerts = pagerDutyClient.getAlerts();
        assertThat(alerts.get(0).serviceKey).isEqualTo("kafka-cluster-health");
        assertThat(alerts.get(0).severity).isEqualTo("critical");

        // Verify DELIVER sign emitted (successful alert)
        assertThat(actorSigns).contains(Actors.Sign.DELIVER);
    }

    @Test
    @DisplayName("Multiple Producers: Each triggers independent throttle")
    void testMultipleProducersIndependentThrottle() {
        // Given: Two producers configured
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 5);
        configManager.setProducerConfig("producer-1", "linger.ms", 10);

        configManager.setProducerConfig("producer-2", "max.in.flight.requests.per.connection", 3);
        configManager.setProducerConfig("producer-2", "linger.ms", 20);

        // When: Producer 1 reporter emits CRITICAL
        Reporters.Reporter producer1Health = reporters.percept(cortex().name("producer.producer-1.health"));
        producer1Health.critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: Only producer-1 throttled
        assertThat(configManager.getProducerConfig("producer-1", "max.in.flight.requests.per.connection"))
            .isEqualTo(2);
        assertThat(configManager.getProducerConfig("producer-1", "linger.ms"))
            .isEqualTo(20);

        // And: producer-2 unchanged
        assertThat(configManager.getProducerConfig("producer-2", "max.in.flight.requests.per.connection"))
            .isEqualTo(3);
        assertThat(configManager.getProducerConfig("producer-2", "linger.ms"))
            .isEqualTo(20);
    }

    @Test
    @DisplayName("Rate Limiting: Multiple CRITICAL signs only trigger once")
    void testActorRateLimiting() {
        // Given: Producer configured
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 5);

        // When: Emit 3 rapid CRITICAL signs
        Reporters.Reporter producerHealth = reporters.percept(cortex().name("producer.producer-1.health"));
        for (int i = 0; i < 3; i++) {
            producerHealth.critical();
        }

        reporterCircuit.await();
        actorCircuit.await();

        // Then: Only 1 throttle action (rate limited)
        assertThat(configManager.getUpdates()).hasSize(2); // 2 configs × 1 action

        // And: DENY signs emitted for rate-limited attempts
        long denyCount = actorSigns.stream()
            .filter(s -> s == Actors.Sign.DENY)
            .count();
        assertThat(denyCount).isGreaterThan(0);
    }

    @Test
    @DisplayName("WARNING signs don't trigger actors")
    void testWarningDoesNotTriggerActors() {
        // Given: Producer configured
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 5);

        // When: Reporter emits WARNING
        Reporters.Reporter producerHealth = reporters.percept(cortex().name("producer.producer-1.health"));
        producerHealth.warning();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: No throttle actions
        assertThat(configManager.getUpdates()).isEmpty();

        // And: No alerts
        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("NORMAL signs don't trigger actors")
    void testNormalDoesNotTriggerActors() {
        // When: Reporter emits NORMAL
        Reporters.Reporter producerHealth = reporters.percept(cortex().name("producer.producer-1.health"));
        producerHealth.normal();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: No actions
        assertThat(configManager.getUpdates()).isEmpty();
        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Hierarchical Names: Producer health reporters use correct pattern")
    void testHierarchicalProducerHealthNames() {
        // Given: Producer configured
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 5);

        // When: Reporter emits CRITICAL with hierarchical name
        Reporters.Reporter producerHealth = reporters.percept(cortex().name("producer.producer-1.health"));
        producerHealth.critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: ThrottleActor correctly extracted producer ID using hierarchical Name
        // (Verified by correct producer being throttled)
        List<MockKafkaConfigManager.ConfigUpdate> updates = configManager.getUpdates();
        assertThat(updates).isNotEmpty();
        assertThat(updates.get(0).producerId).isEqualTo("producer-1");
    }

    @Test
    @DisplayName("End-to-End: Multiple signals flow through complete OODA loop")
    void testEndToEndMultipleSignals() {
        // Given: System configured
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 5);

        // When: Multiple different signals

        // Signal 1: Producer health CRITICAL
        Reporters.Reporter producerHealth = reporters.percept(cortex().name("producer.producer-1.health"));
        producerHealth.critical();
        reporterCircuit.await();
        actorCircuit.await();

        // Signal 2: Cluster health CRITICAL
        Reporters.Reporter clusterHealth = reporters.percept(cortex().name("cluster.health"));
        clusterHealth.critical();
        reporterCircuit.await();
        actorCircuit.await();

        // Then: Both actors triggered
        assertThat(configManager.getUpdates()).isNotEmpty(); // ThrottleActor
        assertThat(pagerDutyClient.getAlertCount()).isEqualTo(1); // AlertActor

        // And: Multiple DELIVER signs
        long deliverCount = actorSigns.stream()
            .filter(s -> s == Actors.Sign.DELIVER)
            .count();
        assertThat(deliverCount).isGreaterThanOrEqualTo(2);
    }
}
