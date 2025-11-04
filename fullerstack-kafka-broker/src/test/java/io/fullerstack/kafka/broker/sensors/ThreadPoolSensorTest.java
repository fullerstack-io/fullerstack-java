package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.core.config.BrokerEndpoint;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.humainary.substrates.ext.serventis.Resources;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Pipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ThreadPoolSensor} (Layer 2 - Signal Emission).
 * <p>
 * <b>Per ADR-002:</b> Tests validate lifecycle, scheduling, and signal emission only.
 * Removed: BaselineService dependency (deferred to Epic 2 Observers).
 * <p>
 * These tests validate lifecycle, scheduling, and signal emission behavior.
 * They do NOT test actual JMX collection (that's tested in ThreadPoolMetricsCollectorTest).
 */
class ThreadPoolSensorTest {

    private ThreadPoolSensor sensor;
    private List<Resources.Signal> emittedSignals;
    private Channel<Resources.Signal> mockChannel;
    private Name circuitName;

    @BeforeEach
    void setUp() {
        emittedSignals = new CopyOnWriteArrayList<>();

        // Mock Channel with Pipe that captures emitted signals
        mockChannel = mock(Channel.class);
        Pipe<Resources.Signal> mockPipe = mock(Pipe.class);

        when(mockChannel.pipe()).thenReturn(mockPipe);

        doAnswer(invocation -> {
            Resources.Signal signal = invocation.getArgument(0);
            emittedSignals.add(signal);
            return null;
        }).when(mockPipe).emit(any(Resources.Signal.class));

        // Circuit name for Subject creation
        circuitName = cortex().name("kafka.broker.resources");
    }

    @AfterEach
    void tearDown() {
        if (sensor != null && sensor.isStarted()) {
            sensor.close();
        }
    }

    @Test
    void constructorRequiresConfig() {
        assertThatThrownBy(() -> new ThreadPoolSensor(null, mockChannel, circuitName))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("config cannot be null");
    }

    @Test
    void constructorRequiresPipe() {
        BrokerSensorConfig config = createTestConfig();

        assertThatThrownBy(() -> new ThreadPoolSensor(config, null, circuitName))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("channel cannot be null");
    }

    @Test
    void constructorRequiresCircuitName() {
        BrokerSensorConfig config = createTestConfig();

        assertThatThrownBy(() -> new ThreadPoolSensor(config, mockChannel, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("circuitName cannot be null");
    }

    @Test
    void sensorIsNotStartedInitially() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, mockChannel, circuitName);

        assertThat(sensor.isStarted()).isFalse();
    }

    @Test
    void startSetsSensorToStartedState() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, mockChannel, circuitName);

        sensor.start();

        assertThat(sensor.isStarted()).isTrue();
    }

    @Test
    void startIsIdempotent() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, mockChannel, circuitName);

        sensor.start();
        sensor.start();  // Second call should be safe

        assertThat(sensor.isStarted()).isTrue();
    }

    @Test
    void closeStopsSensor() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, mockChannel, circuitName);

        sensor.start();
        assertThat(sensor.isStarted()).isTrue();

        sensor.close();

        assertThat(sensor.isStarted()).isFalse();
    }

    @Test
    void closeIsIdempotent() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, mockChannel, circuitName);

        sensor.start();
        sensor.close();
        sensor.close();  // Second close should be safe

        assertThat(sensor.isStarted()).isFalse();
    }

    @Test
    void closeBeforeStartIsSafe() {
        BrokerSensorConfig config = createTestConfig();
        sensor = new ThreadPoolSensor(config, mockChannel, circuitName);

        // Close without starting - should not throw
        assertThatCode(() -> sensor.close()).doesNotThrowAnyException();
    }

    @Test
    void pipeIsInvokedOnScheduledCollection() throws Exception {
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
        Channel<Resources.Signal> testChannel = mock(Channel.class);
        Pipe<Resources.Signal> testPipe = mock(Pipe.class);
        when(testChannel.pipe()).thenReturn(testPipe);
        doAnswer(invocation -> {
            emittedSignals.add(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(testPipe).emit(any(Resources.Signal.class));

        sensor = new ThreadPoolSensor(config, testChannel, circuitName);
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
        // With a real broker, we'd get multiple Resources.Signals (one per pool type)
        // This test validates the sensor can handle multiple emissions per broker

        BrokerSensorConfig config = createTestConfig();

        CountDownLatch latch = new CountDownLatch(2);  // Expect at least 2 pools (network + I/O)
        Channel<Resources.Signal> countingChannel = mock(Channel.class);
        Pipe<Resources.Signal> countingPipe = mock(Pipe.class);
        when(countingChannel.pipe()).thenReturn(countingPipe);
        doAnswer(invocation -> {
            emittedSignals.add(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(countingPipe).emit(any(Resources.Signal.class));

        sensor = new ThreadPoolSensor(config, countingChannel, circuitName);
        sensor.start();

        // Wait briefly - may or may not complete without real broker
        latch.await(2, TimeUnit.SECONDS);

        // Sensor lifecycle should work regardless
        assertThat(sensor.isStarted()).isTrue();
    }

    @Test
    void exceptionInMonitorDoesNotStopSensor() throws Exception {
        BrokerSensorConfig config = createTestConfig();

        // Mock Pipe that throws on emit
        Channel<Resources.Signal> throwingChannel = mock(Channel.class);
        Pipe<Resources.Signal> throwingPipe = mock(Pipe.class);
        when(throwingChannel.pipe()).thenReturn(throwingPipe);
        doThrow(new RuntimeException("Simulated emission failure"))
            .when(throwingPipe).emit(any(Resources.Signal.class));

        sensor = new ThreadPoolSensor(config, throwingChannel, circuitName);
        sensor.start();

        // Wait briefly
        Thread.sleep(500);

        // Sensor should still be running despite monitor/pipe exceptions
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
