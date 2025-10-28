package io.fullerstack.kafka.producer.sensors;

import io.fullerstack.kafka.core.config.ClusterConfig;
import io.fullerstack.kafka.producer.models.ProducerMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ProducerMonitoringAgent}.
 */
class ProducerMonitoringAgentTest {

    private ClusterConfig config;
    private CopyOnWriteArrayList<CollectedMetrics> collectedMetrics;
    private ProducerMonitoringAgent agent;

    @BeforeEach
    void setUp() {
        config = ClusterConfig.withDefaults(
                "localhost:9092",
                "localhost:11001"
        );
        collectedMetrics = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (agent != null) {
            agent.close();
        }
    }

    @Test
    void testAgentCreation() {
        // Act
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Assert
        assertThat(agent).isNotNull();
        assertThat(agent.getMonitoredProducerCount()).isEqualTo(0);
        assertThat(agent.isStarted()).isFalse();
    }

    @Test
    void testAgentCreationWithCustomInterval() {
        // Act
        agent = new ProducerMonitoringAgent(config, 5000, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Assert
        assertThat(agent).isNotNull();
        assertThat(agent.toString()).contains("interval=5000ms");
    }

    @Test
    void testNullConfigThrows() {
        assertThatThrownBy(() ->
                new ProducerMonitoringAgent(null, (id, metrics) ->
                        collectedMetrics.add(new CollectedMetrics(id, metrics)))
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("config cannot be null");
    }

    @Test
    void testNullEmitterThrows() {
        assertThatThrownBy(() ->
                new ProducerMonitoringAgent(config, null)
        )
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("emitter cannot be null");
    }

    @Test
    void testInvalidIntervalThrows() {
        assertThatThrownBy(() ->
                new ProducerMonitoringAgent(config, 0, (id, metrics) ->
                        collectedMetrics.add(new CollectedMetrics(id, metrics)))
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collectionIntervalMs must be > 0");
    }

    @Test
    void testRegisterProducer() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Act
        boolean added = agent.registerProducer("producer-1");

        // Assert
        assertThat(added).isTrue();
        assertThat(agent.getMonitoredProducerCount()).isEqualTo(1);
        assertThat(agent.isMonitoring("producer-1")).isTrue();
        assertThat(agent.isMonitoring("producer-2")).isFalse();
    }

    @Test
    void testRegisterDuplicateProducer() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Act
        agent.registerProducer("producer-1");
        boolean addedAgain = agent.registerProducer("producer-1");

        // Assert
        assertThat(addedAgain).isFalse();
        assertThat(agent.getMonitoredProducerCount()).isEqualTo(1);
    }

    @Test
    void testRegisterMultipleProducers() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Act
        agent.registerProducer("producer-1");
        agent.registerProducer("producer-2");
        agent.registerProducer("producer-3");

        // Assert
        assertThat(agent.getMonitoredProducerCount()).isEqualTo(3);
        assertThat(agent.isMonitoring("producer-1")).isTrue();
        assertThat(agent.isMonitoring("producer-2")).isTrue();
        assertThat(agent.isMonitoring("producer-3")).isTrue();
    }

    @Test
    void testRegisterNullProducerId() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Act & Assert
        assertThatThrownBy(() -> agent.registerProducer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("producerId cannot be null");
    }

    @Test
    void testRegisterBlankProducerId() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Act & Assert
        assertThatThrownBy(() -> agent.registerProducer("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("producerId cannot be blank");
    }

    @Test
    void testUnregisterProducer() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));
        agent.registerProducer("producer-1");
        agent.registerProducer("producer-2");

        // Act
        boolean removed = agent.unregisterProducer("producer-1");

        // Assert
        assertThat(removed).isTrue();
        assertThat(agent.getMonitoredProducerCount()).isEqualTo(1);
        assertThat(agent.isMonitoring("producer-1")).isFalse();
        assertThat(agent.isMonitoring("producer-2")).isTrue();
    }

    @Test
    void testUnregisterNonExistentProducer() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Act
        boolean removed = agent.unregisterProducer("non-existent");

        // Assert
        assertThat(removed).isFalse();
    }

    @Test
    void testStartAgent() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Act
        agent.start();

        // Assert
        assertThat(agent.isStarted()).isTrue();
    }

    @Test
    void testStartAlreadyStartedThrows() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));
        agent.start();

        // Act & Assert
        assertThatThrownBy(() -> agent.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Agent already started");
    }

    @Test
    void testRegisterAfterClose() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));
        agent.close();

        // Act & Assert
        assertThatThrownBy(() -> agent.registerProducer("producer-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot register producer after agent is closed");
    }

    @Test
    void testStartAfterClose() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));
        agent.close();

        // Act & Assert
        assertThatThrownBy(() -> agent.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot start agent after it is closed");
    }

    @Test
    void testCloseIdempotent() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));

        // Act
        agent.close();
        agent.close();  // Should not throw

        // Assert
        assertThat(agent.getMonitoredProducerCount()).isEqualTo(0);
    }

    @Test
    void testToString() {
        // Arrange
        agent = new ProducerMonitoringAgent(config, 5000, (id, metrics) ->
                collectedMetrics.add(new CollectedMetrics(id, metrics)));
        agent.registerProducer("producer-1");

        // Act
        String result = agent.toString();

        // Assert
        assertThat(result)
                .contains("ProducerMonitoringAgent")
                .contains("interval=5000ms")
                .contains("producers=1")
                .contains("started=false");
    }

    /**
     * Helper record to capture emitted metrics.
     */
    private record CollectedMetrics(String producerId, ProducerMetrics metrics) {
    }
}
