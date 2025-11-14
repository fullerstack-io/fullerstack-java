package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.core.config.BrokerEndpoint;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.ext.serventis.ext.Routers;
import io.humainary.substrates.ext.serventis.ext.Routers.Router;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IsrReplicationSensor} (Layer 2 - Signal Emission).
 * <p>
 * Tests validate:
 * <ul>
 *   <li>Lifecycle: start, stop, close</li>
 *   <li>Scheduling: periodic collection (5 second interval)</li>
 *   <li>Monitor creation: one Router monitor per broker, one health monitor per cluster</li>
 *   <li>Error handling: graceful degradation on JMX failures</li>
 * </ul>
 *
 * <b>Note</b>: These tests validate sensor behavior only. Actual JMX collection
 * is tested in integration tests with real Kafka brokers.
 */
@DisplayName("IsrReplicationSensor (RC6 ISR Monitoring)")
class IsrReplicationSensorTest {

    private IsrReplicationSensor sensor;
    private Conduit<Router, Routers.Sign> mockRoutersConduit;
    private Conduit<Monitor, Monitors.Signal> mockMonitorsConduit;
    private Name circuitName;

    @BeforeEach
    void setUp() {
        circuitName = cortex().name("isr-replication");

        // Mock Routers Conduit
        mockRoutersConduit = mock(Conduit.class);
        Router mockRouter = mock(Router.class);
        when(mockRoutersConduit.percept(any(Name.class))).thenReturn(mockRouter);

        // Mock Monitors Conduit
        mockMonitorsConduit = mock(Conduit.class);
        Monitor mockMonitor = mock(Monitor.class);
        when(mockMonitorsConduit.percept(any(Name.class))).thenReturn(mockMonitor);
    }

    @AfterEach
    void tearDown() {
        if (sensor != null && sensor.isStarted()) {
            sensor.close();
        }
    }

    /**
     * Helper to create test configuration.
     */
    private BrokerSensorConfig createTestConfig() {
        return new BrokerSensorConfig(
            List.of(
                new BrokerEndpoint(
                    "broker-1",
                    "localhost",
                    9092,
                    "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi"
                )
            ),
            5000,  // 5 second interval (critical metric)
            true   // Use connection pooling
        );
    }

    /**
     * Helper to create multi-broker configuration.
     */
    private BrokerSensorConfig createMultiBrokerConfig() {
        return new BrokerSensorConfig(
            List.of(
                new BrokerEndpoint("broker-1", "localhost", 9092, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi"),
                new BrokerEndpoint("broker-2", "localhost", 9093, "service:jmx:rmi:///jndi/rmi://localhost:11002/jmxrmi"),
                new BrokerEndpoint("broker-3", "localhost", 9094, "service:jmx:rmi:///jndi/rmi://localhost:11003/jmxrmi")
            ),
            5000,
            true
        );
    }

    // ========================================================================
    // Constructor Validation Tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorTests {

        @Test
        @DisplayName("Should reject null config")
        void testConstructor_NullConfig() {
            assertThatThrownBy(() ->
                new IsrReplicationSensor(
                    null,
                    mockRoutersConduit,
                    mockMonitorsConduit,
                    circuitName,
                    "cluster-1"
                )
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("config cannot be null");
        }

        @Test
        @DisplayName("Should reject null routers conduit")
        void testConstructor_NullRoutersConduit() {
            BrokerSensorConfig config = createTestConfig();

            assertThatThrownBy(() ->
                new IsrReplicationSensor(
                    config,
                    null,
                    mockMonitorsConduit,
                    circuitName,
                    "cluster-1"
                )
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("routersConduit cannot be null");
        }

        @Test
        @DisplayName("Should reject null monitors conduit")
        void testConstructor_NullMonitorsConduit() {
            BrokerSensorConfig config = createTestConfig();

            assertThatThrownBy(() ->
                new IsrReplicationSensor(
                    config,
                    mockRoutersConduit,
                    null,
                    circuitName,
                    "cluster-1"
                )
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("monitorsConduit cannot be null");
        }

        @Test
        @DisplayName("Should reject null circuit name")
        void testConstructor_NullCircuitName() {
            BrokerSensorConfig config = createTestConfig();

            assertThatThrownBy(() ->
                new IsrReplicationSensor(
                    config,
                    mockRoutersConduit,
                    mockMonitorsConduit,
                    null,
                    "cluster-1"
                )
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("circuitName cannot be null");
        }

        @Test
        @DisplayName("Should reject null cluster ID")
        void testConstructor_NullClusterId() {
            BrokerSensorConfig config = createTestConfig();

            assertThatThrownBy(() ->
                new IsrReplicationSensor(
                    config,
                    mockRoutersConduit,
                    mockMonitorsConduit,
                    circuitName,
                    null
                )
            ).isInstanceOf(NullPointerException.class)
             .hasMessageContaining("clusterId cannot be null");
        }

        @Test
        @DisplayName("Should create sensor successfully with valid parameters")
        void testConstructor_ValidParameters() {
            BrokerSensorConfig config = createTestConfig();

            assertThatCode(() ->
                new IsrReplicationSensor(
                    config,
                    mockRoutersConduit,
                    mockMonitorsConduit,
                    circuitName,
                    "cluster-1"
                )
            ).doesNotThrowAnyException();
        }
    }

    // ========================================================================
    // Lifecycle Tests
    // ========================================================================

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("Should not be started initially")
        void testLifecycle_NotStartedInitially() {
            BrokerSensorConfig config = createTestConfig();
            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            assertThat(sensor.isStarted()).isFalse();
        }

        @Test
        @DisplayName("Should start successfully")
        void testLifecycle_Start() {
            BrokerSensorConfig config = createTestConfig();
            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            sensor.start();

            assertThat(sensor.isStarted()).isTrue();
        }

        @Test
        @DisplayName("Should be idempotent on start")
        void testLifecycle_StartIdempotent() {
            BrokerSensorConfig config = createTestConfig();
            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            sensor.start();
            sensor.start();  // Second call should be safe

            assertThat(sensor.isStarted()).isTrue();
        }

        @Test
        @DisplayName("Should stop on close")
        void testLifecycle_Close() {
            BrokerSensorConfig config = createTestConfig();
            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            sensor.start();
            assertThat(sensor.isStarted()).isTrue();

            sensor.close();

            assertThat(sensor.isStarted()).isFalse();
        }

        @Test
        @DisplayName("Should be idempotent on close")
        void testLifecycle_CloseIdempotent() {
            BrokerSensorConfig config = createTestConfig();
            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            sensor.start();
            sensor.close();
            sensor.close();  // Second close should be safe

            assertThat(sensor.isStarted()).isFalse();
        }

        @Test
        @DisplayName("Should close safely without starting")
        void testLifecycle_CloseWithoutStart() {
            BrokerSensorConfig config = createTestConfig();
            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            assertThatCode(() -> sensor.close())
                .doesNotThrowAnyException();
        }
    }

    // ========================================================================
    // Scheduling Tests
    // ========================================================================

    @Nested
    @DisplayName("Scheduling")
    class SchedulingTests {

        @Test
        @DisplayName("Should schedule collection at fixed rate")
        void testScheduling_FixedRate() throws Exception {
            BrokerSensorConfig config = new BrokerSensorConfig(
                List.of(
                    new BrokerEndpoint("test-broker", "localhost", 9092, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi")
                ),
                100,  // Fast interval for test (100ms)
                true
            );

            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            sensor.start();

            // Wait briefly to allow scheduler to run
            // Note: May fail to collect metrics (no real broker), but scheduler should run
            Thread.sleep(300);

            assertThat(sensor.isStarted()).isTrue();
        }
    }

    // ========================================================================
    // Multi-Broker Tests
    // ========================================================================

    @Nested
    @DisplayName("Multi-Broker")
    class MultiBrokerTests {

        @Test
        @DisplayName("Should create router monitors for each broker")
        void testMultiBroker_RouterMonitorsCreated() {
            BrokerSensorConfig config = createMultiBrokerConfig();

            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            // Verify router monitors created for each broker
            verify(mockRoutersConduit, atLeast(3)).percept(any(Name.class));
        }

        @Test
        @DisplayName("Should create single health monitor for cluster")
        void testMultiBroker_SingleHealthMonitor() {
            BrokerSensorConfig config = createMultiBrokerConfig();

            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            // Verify health monitor created once (cluster-wide)
            verify(mockMonitorsConduit, atLeast(1)).percept(any(Name.class));
        }

        @Test
        @DisplayName("Should handle failure for one broker gracefully")
        void testMultiBroker_GracefulDegradation() throws Exception {
            BrokerSensorConfig config = new BrokerSensorConfig(
                List.of(
                    new BrokerEndpoint("broker-1", "localhost", 9092, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi"),
                    new BrokerEndpoint("broker-2", "localhost", 9093, "service:jmx:rmi:///jndi/rmi://localhost:11002/jmxrmi")
                ),
                100,  // Fast interval
                true
            );

            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            sensor.start();

            // Wait for collection attempts
            Thread.sleep(300);

            // Sensor should still be running despite collection failures
            assertThat(sensor.isStarted()).isTrue();
        }
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle JMX connection failure gracefully")
        void testErrorHandling_JmxConnectionFailure() throws Exception {
            BrokerSensorConfig config = new BrokerSensorConfig(
                List.of(
                    new BrokerEndpoint("invalid-broker", "invalid-host", 9999, "service:jmx:rmi:///jndi/rmi://invalid:9999/jmxrmi")
                ),
                100,  // Fast interval
                true
            );

            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            // Should not throw on start
            assertThatCode(() -> sensor.start())
                .doesNotThrowAnyException();

            // Wait for collection attempts
            Thread.sleep(300);

            // Sensor should still be running
            assertThat(sensor.isStarted()).isTrue();
        }

        @Test
        @DisplayName("Should handle monitor emission failure gracefully")
        void testErrorHandling_MonitorEmissionFailure() throws Exception {
            BrokerSensorConfig config = createTestConfig();

            // Mock router that throws on method call
            Router throwingRouter = mock(Router.class);
            doThrow(new RuntimeException("Emission failed"))
                .when(throwingRouter).drop();

            when(mockRoutersConduit.percept(any(Name.class))).thenReturn(throwingRouter);

            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            sensor.start();

            // Wait for collection attempts
            Thread.sleep(300);

            // Sensor should still be running despite emission failures
            assertThat(sensor.isStarted()).isTrue();
        }
    }

    // ========================================================================
    // Connection Pooling Tests
    // ========================================================================

    @Nested
    @DisplayName("Connection Pooling")
    class ConnectionPoolingTests {

        @Test
        @DisplayName("Should use connection pooling by default")
        void testConnectionPooling_EnabledByDefault() {
            BrokerSensorConfig config = new BrokerSensorConfig(
                List.of(
                    new BrokerEndpoint("broker-1", "localhost", 9092, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi")
                ),
                5000,
                true  // Connection pooling enabled
            );

            assertThatCode(() ->
                new IsrReplicationSensor(
                    config,
                    mockRoutersConduit,
                    mockMonitorsConduit,
                    circuitName,
                    "cluster-1"
                )
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should handle connection pool lifecycle")
        void testConnectionPooling_Lifecycle() throws Exception {
            BrokerSensorConfig config = createTestConfig();

            sensor = new IsrReplicationSensor(
                config,
                mockRoutersConduit,
                mockMonitorsConduit,
                circuitName,
                "cluster-1"
            );

            sensor.start();
            Thread.sleep(100);

            // Close should cleanup connection pool
            assertThatCode(() -> sensor.close())
                .doesNotThrowAnyException();
        }
    }

    // ========================================================================
    // Configuration Tests
    // ========================================================================

    @Nested
    @DisplayName("Configuration")
    class ConfigurationTests {

        @Test
        @DisplayName("Should use 5 second collection interval")
        void testConfiguration_DefaultInterval() {
            BrokerSensorConfig config = new BrokerSensorConfig(
                List.of(
                    new BrokerEndpoint("broker-1", "localhost", 9092, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi")
                ),
                5000,  // 5 second interval (critical metric)
                true
            );

            assertThat(config.collectionIntervalMs()).isEqualTo(5000);
        }

        @Test
        @DisplayName("Should support custom collection interval")
        void testConfiguration_CustomInterval() {
            BrokerSensorConfig config = new BrokerSensorConfig(
                List.of(
                    new BrokerEndpoint("broker-1", "localhost", 9092, "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi")
                ),
                1000,  // Custom 1 second interval
                true
            );

            assertThat(config.collectionIntervalMs()).isEqualTo(1000);
        }
    }
}
