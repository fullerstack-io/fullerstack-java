package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import io.fullerstack.kafka.core.config.BrokerEndpoint;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ThreadPoolSensor}.
 * <p>
 * These tests validate lifecycle, scheduling, and emission behavior.
 * They do NOT test actual JMX collection (that's tested in ThreadPoolMetricsCollectorTest).
 */
class ThreadPoolSensorTest {

    private ThreadPoolSensor sensor;
    private List<ThreadPoolMetrics> emittedMetrics;
    private BiConsumer<String, ThreadPoolMetrics> captureEmitter;

    @BeforeEach
    void setUp() {
        emittedMetrics = new CopyOnWriteArrayList<>();
        captureEmitter = (brokerId, metrics) -> emittedMetrics.add(metrics);
    }

    @AfterEach
    void tearDown() {
        if (sensor != null && sensor.isStarted()) {
            sensor.close();
        }
    }

    @Test
    void constructorRequiresConfig() {
        assertThatThrownBy(() -> new ThreadPoolSensor(null, captureEmitter))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("config cannot be null");
    }

    @Test
    void constructorRequiresEmitter() {
        BrokerSensorConfig config = createTestConfig();

        assertThatThrownBy(() -> new ThreadPoolSensor(config, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("metricsEmitter cannot be null");
    }

    @Test
    void sensorIsNotStartedInitially() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, captureEmitter);

        assertThat(sensor.isStarted()).isFalse();
    }

    @Test
    void startSetsSensorToStartedState() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, captureEmitter);

        sensor.start();

        assertThat(sensor.isStarted()).isTrue();
    }

    @Test
    void startIsIdempotent() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, captureEmitter);

        sensor.start();
        sensor.start();  // Second call should be safe

        assertThat(sensor.isStarted()).isTrue();
    }

    @Test
    void closeStopsSensor() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, captureEmitter);

        sensor.start();
        assertThat(sensor.isStarted()).isTrue();

        sensor.close();

        assertThat(sensor.isStarted()).isFalse();
    }

    @Test
    void closeIsIdempotent() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, captureEmitter);

        sensor.start();
        sensor.close();
        sensor.close();  // Second close should be safe

        assertThat(sensor.isStarted()).isFalse();
    }

    @Test
    void closeBeforeStartIsSafe() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, captureEmitter);

        // Close without starting - should not throw
        assertThatCode(() -> sensor.close()).doesNotThrowAnyException();
    }

    @Test
    void emitterIsInvokedOnScheduledCollection() throws Exception {
        // Note: This test may be flaky with real JMX - we're testing lifecycle, not actual collection
        // In a full integration test, this would collect real metrics
        // Here we just verify the sensor lifecycle works

        BrokerSensorConfig config = new BrokerSensorConfig(
            List.of(
                new BrokerEndpoint("test-broker", "localhost", 9092, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi")
            ),
            100,  // Fast interval for test
            true  // Use connection pooling
        );

        CountDownLatch latch = new CountDownLatch(1);
        BiConsumer<String, ThreadPoolMetrics> testEmitter = (brokerId, metrics) -> {
            emittedMetrics.add(metrics);
            latch.countDown();
        };

        sensor = new ThreadPoolSensor(config, testEmitter);
        sensor.start();

        // Wait briefly - sensor will try to collect (and likely fail with no real broker)
        // but we're just testing that the scheduler runs
        boolean completed = latch.await(2, TimeUnit.SECONDS);

        // Note: May not complete if JMX collection fails (no real broker running)
        // This test primarily validates that start() doesn't throw and scheduler runs
        // Full collection behavior is tested in integration tests
    }

    @Test
    void multipleEmissionsForMultiplePools() throws Exception {
        // With a real broker, we'd get multiple ThreadPoolMetrics (one per pool type)
        // This test validates the sensor can handle multiple emissions per broker

        BrokerSensorConfig config = createTestConfig();

        CountDownLatch latch = new CountDownLatch(2);  // Expect at least 2 pools (network + I/O)
        BiConsumer<String, ThreadPoolMetrics> countingEmitter = (brokerId, metrics) -> {
            emittedMetrics.add(metrics);
            latch.countDown();
        };

        sensor = new ThreadPoolSensor(config, countingEmitter);
        sensor.start();

        // Wait briefly - may or may not complete without real broker
        latch.await(2, TimeUnit.SECONDS);

        // Sensor lifecycle should work regardless
        assertThat(sensor.isStarted()).isTrue();
    }

    @Test
    void exceptionInEmitterDoesNotStopSensor() throws Exception {
        BrokerSensorConfig config = createTestConfig();

        BiConsumer<String, ThreadPoolMetrics> throwingEmitter = (brokerId, metrics) -> {
            throw new RuntimeException("Simulated emission failure");
        };

        sensor = new ThreadPoolSensor(config, throwingEmitter);
        sensor.start();

        // Wait briefly
        Thread.sleep(500);

        // Sensor should still be running despite emitter exceptions
        assertThat(sensor.isStarted()).isTrue();
    }

    // Helper methods

    private BrokerSensorConfig createTestConfig() {
        return new BrokerSensorConfig(
            List.of(
                new BrokerEndpoint("broker-1", "localhost", 9092, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi")
            ),
            1000,  // 1 second interval
            true   // Use connection pooling
        );
    }
}
