package io.fullerstack.kafka.core.actors;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Situations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ThrottleActor.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Producer throttling on CRITICAL signs</li>
 *   <li>max.in.flight.requests reduction (50%)</li>
 *   <li>linger.ms increase (2x, capped at 100ms)</li>
 *   <li>Rate limiting (1 throttle per 5 minutes)</li>
 *   <li>Error handling (config manager failures)</li>
 *   <li>Speech act emission (DELIVER, DENY)</li>
 *   <li>Producer ID extraction</li>
 * </ul>
 */
@DisplayName("ThrottleActor Tests")
class ThrottleActorTest {

    /**
     * Simple mock implementation of KafkaConfigManager for testing.
     */
    private static class MockKafkaConfigManager implements KafkaConfigManager {
        private final Map<String, Map<String, Integer>> producerConfigs = new ConcurrentHashMap<>();
        private final List<ConfigUpdate> updates = new ArrayList<>();
        private boolean shouldFail = false;

        /**
         * Records a config update for verification.
         */
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
        public void updateProducerConfig(String producerId, String configKey, String configValue) throws java.lang.Exception {
            if (shouldFail) {
                throw new RuntimeException("KafkaConfigManager failure");
            }

            updates.add(new ConfigUpdate(producerId, configKey, configValue));

            producerConfigs
                .computeIfAbsent(producerId, k -> new ConcurrentHashMap<>())
                .put(configKey, Integer.parseInt(configValue));
        }

        @Override
        public int getConsumerConfig(String consumerId, String configKey) {
            return 0; // Not used in ThrottleActor
        }

        @Override
        public void updateConsumerConfig(String consumerId, String configKey, String configValue) throws java.lang.Exception {
            // Not used in ThrottleActor
        }

        void setShouldFail(boolean shouldFail) {
            this.shouldFail = shouldFail;
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
                case "max.in.flight.requests.per.connection" -> 5; // Kafka default
                case "linger.ms" -> 0; // Kafka default
                default -> 0;
            };
        }
    }

    private Circuit reporterCircuit;
    private Circuit actorCircuit;
    private Conduit<Situations.Situation, Situations.Signal> reporters;
    private Conduit<Actors.Actor, Actors.Sign> actors;
    private MockKafkaConfigManager configManager;
    private ThrottleActor throttleActor;
    private List<Actors.Sign> actorSigns;

    @BeforeEach
    void setUp() {
        reporterCircuit = cortex().circuit(cortex().name("reporters"));
        reporters = reporterCircuit.conduit(cortex().name("reporters"), Situations::composer);

        actorCircuit = cortex().circuit(cortex().name("actors"));
        actors = actorCircuit.conduit(cortex().name("actors"), Actors::composer);

        configManager = new MockKafkaConfigManager();

        throttleActor = new ThrottleActor(
            reporters,
            actors,
            configManager
        );

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
        if (actorCircuit != null) actorCircuit.close();
        if (reporterCircuit != null) reporterCircuit.close();
    }

    @Test
    @DisplayName("ThrottleActor reduces max.in.flight.requests on CRITICAL")
    void testReducesMaxInflightOnCritical() {
        // Given: Producer has max.in.flight.requests = 5
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 5);

        // When: ProducerHealthReporter emits CRITICAL
        reporters.percept(cortex().name("producer.producer-1.health")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: max.in.flight.requests reduced by 50% (5 → 2)
        assertThat(configManager.getProducerConfig("producer-1", "max.in.flight.requests.per.connection"))
            .isEqualTo(2);

        // And: DELIVER sign emitted (successful action)
        assertThat(actorSigns).contains(Actors.Sign.DELIVER);
    }

    @Test
    @DisplayName("ThrottleActor increases linger.ms on CRITICAL")
    void testIncreasesLingerOnCritical() {
        // Given: Producer has linger.ms = 10
        configManager.setProducerConfig("producer-1", "linger.ms", 10);

        // When: ProducerHealthReporter emits CRITICAL
        reporters.percept(cortex().name("producer.producer-1.health")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: linger.ms increased by 2x (10 → 20)
        assertThat(configManager.getProducerConfig("producer-1", "linger.ms"))
            .isEqualTo(20);

        // And: DELIVER sign emitted
        assertThat(actorSigns).contains(Actors.Sign.DELIVER);
    }

    @Test
    @DisplayName("ThrottleActor caps linger.ms at 100ms")
    void testLingerCappedAt100ms() {
        // Given: Producer has linger.ms = 60
        configManager.setProducerConfig("producer-1", "linger.ms", 60);

        // When: CRITICAL sign emitted
        reporters.percept(cortex().name("producer.producer-1.health")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: linger.ms capped at 100 (would be 120 without cap)
        assertThat(configManager.getProducerConfig("producer-1", "linger.ms"))
            .isEqualTo(100);
    }

    @Test
    @DisplayName("ThrottleActor keeps max.in.flight.requests at minimum 1")
    void testMaxInflightMinimumIs1() {
        // Given: Producer has max.in.flight.requests = 1
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 1);

        // When: CRITICAL sign emitted
        reporters.percept(cortex().name("producer.producer-1.health")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: max.in.flight.requests remains at 1 (doesn't go to 0)
        assertThat(configManager.getProducerConfig("producer-1", "max.in.flight.requests.per.connection"))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("ThrottleActor updates both configs in single action")
    void testUpdatesBothConfigs() {
        // Given: Producer with default configs
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 5);
        configManager.setProducerConfig("producer-1", "linger.ms", 10);

        // When: CRITICAL sign emitted
        reporters.percept(cortex().name("producer.producer-1.health")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: Both configs updated
        List<MockKafkaConfigManager.ConfigUpdate> updates = configManager.getUpdates();
        assertThat(updates).hasSize(2);

        assertThat(updates.get(0).configKey).isEqualTo("max.in.flight.requests.per.connection");
        assertThat(updates.get(0).configValue).isEqualTo("2");

        assertThat(updates.get(1).configKey).isEqualTo("linger.ms");
        assertThat(updates.get(1).configValue).isEqualTo("20");
    }

    @Test
    @DisplayName("ThrottleActor rate limiting prevents throttle storm")
    void testRateLimiting() {
        // When: 3 rapid CRITICAL signs
        for (int i = 0; i < 3; i++) {
            reporters.percept(cortex().name("producer.producer-1.health")).critical();
        }

        reporterCircuit.await();
        actorCircuit.await();

        // Then: Only 1 throttle action executed (2 configs × 1 action = 2 updates)
        assertThat(configManager.getUpdates()).hasSize(2);

        // And: DENY signs emitted for rate-limited attempts
        long denyCount = actorSigns.stream()
            .filter(s -> s == Actors.Sign.DENY)
            .count();
        assertThat(denyCount).isGreaterThan(0);
    }

    @Test
    @DisplayName("ThrottleActor ignores WARNING signs")
    void testIgnoresWarning() {
        // When: Situation emits WARNING
        reporters.percept(cortex().name("producer.producer-1.health")).warning();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: No throttle actions
        assertThat(configManager.getUpdates()).isEmpty();
    }

    @Test
    @DisplayName("ThrottleActor ignores NORMAL signs")
    void testIgnoresNormal() {
        // When: Situation emits NORMAL
        reporters.percept(cortex().name("producer.producer-1.health")).normal();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: No throttle actions
        assertThat(configManager.getUpdates()).isEmpty();
    }

    @Test
    @DisplayName("ThrottleActor emits DENY sign when config update fails")
    void testDenySignOnConfigFailure() {
        // Given: ConfigManager throws exception
        configManager.setShouldFail(true);

        // When: CRITICAL sign emitted
        reporters.percept(cortex().name("producer.producer-1.health")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: DENY sign emitted (action failed)
        assertThat(actorSigns).contains(Actors.Sign.DENY);

        // And: No config updates applied
        assertThat(configManager.getUpdates()).isEmpty();
    }

    @Test
    @DisplayName("ThrottleActor only acts on producer-health reporters")
    void testTargetedReporter() {
        // When: Different reporter emits CRITICAL
        reporters.percept(cortex().name("cluster-health")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: No throttle actions (not subscribed to cluster-health)
        assertThat(configManager.getUpdates()).isEmpty();
    }

    @Test
    @DisplayName("ThrottleActor extracts producer ID correctly")
    void testProducerIdExtraction() {
        // When: Multiple producers emit CRITICAL
        reporters.percept(cortex().name("producer.producer-1.health")).critical();
        actorCircuit.await();

        reporters.percept(cortex().name("producer.producer-2.health")).critical();
        actorCircuit.await();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: Correct producer IDs used (rate limit prevents second action, but first works)
        List<MockKafkaConfigManager.ConfigUpdate> updates = configManager.getUpdates();
        assertThat(updates).isNotEmpty();
        assertThat(updates.get(0).producerId).isEqualTo("producer-1");
    }

    @Test
    @DisplayName("ThrottleActor handles multiple producers independently")
    void testMultipleProducersIndependent() {
        // Given: Two producers with different configs
        configManager.setProducerConfig("producer-1", "max.in.flight.requests.per.connection", 5);
        configManager.setProducerConfig("producer-1", "linger.ms", 10);

        configManager.setProducerConfig("producer-2", "max.in.flight.requests.per.connection", 3);
        configManager.setProducerConfig("producer-2", "linger.ms", 20);

        // When: First producer emits CRITICAL
        reporters.percept(cortex().name("producer.producer-1.health")).critical();

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
    @DisplayName("ThrottleActor closes cleanly and stops acting")
    void testCloseStopsActing() {
        // Given: ThrottleActor closed
        throttleActor.close();

        // When: CRITICAL sign emitted after close
        reporters.percept(cortex().name("producer.producer-1.health")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: No throttle actions (subscription closed)
        assertThat(configManager.getUpdates()).isEmpty();
    }

    @Test
    @DisplayName("ThrottleActor handles default Kafka configs correctly")
    void testDefaultKafkaConfigs() {
        // Given: Producer with default configs (5 and 0)
        // (MockKafkaConfigManager returns defaults if not explicitly set)

        // When: CRITICAL sign emitted
        reporters.percept(cortex().name("producer.producer-1.health")).critical();

        reporterCircuit.await();
        actorCircuit.await();

        // Then: Defaults throttled correctly
        // max.in.flight.requests: 5 → 2
        assertThat(configManager.getProducerConfig("producer-1", "max.in.flight.requests.per.connection"))
            .isEqualTo(2);

        // linger.ms: 0 → 0 (0 * 2 = 0)
        assertThat(configManager.getProducerConfig("producer-1", "linger.ms"))
            .isEqualTo(0);
    }
}
