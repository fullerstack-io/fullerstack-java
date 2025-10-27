package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.BrokerMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.MockedStatic;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JmxMetricsCollector.
 * <p>
 * Tests retry logic, timeout handling, and metric collection.
 */
class JmxMetricsCollectorTest {

    private JmxMetricsCollector collector;
    private static final String TEST_JMX_URL = "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi";

    @BeforeEach
    void setUp() {
        collector = new JmxMetricsCollector();
    }

    @Test
    void collect_shouldThrowException_whenMaxRetriesExceeded() {
        // Create a collector that will always fail
        try (MockedStatic<JMXConnectorFactory> mockedFactory = mockStatic(JMXConnectorFactory.class)) {
            mockedFactory.when(() -> JMXConnectorFactory.connect(any(JMXServiceURL.class), any()))
                    .thenThrow(new IOException("Connection failed"));

            // Expect exception after 3 retries
            assertThatThrownBy(() -> collector.collect(TEST_JMX_URL))
                    .isInstanceOf(JmxMetricsCollector.JmxCollectionException.class)
                    .hasMessageContaining("Failed to collect metrics after 3 attempts");
        }
    }

    @Test
    void collect_shouldRetryWithExponentialBackoff() {
        long startTime = System.currentTimeMillis();

        try (MockedStatic<JMXConnectorFactory> mockedFactory = mockStatic(JMXConnectorFactory.class)) {
            mockedFactory.when(() -> JMXConnectorFactory.connect(any(JMXServiceURL.class), any()))
                    .thenThrow(new IOException("Connection failed"));

            try {
                collector.collect(TEST_JMX_URL);
                fail("Should have thrown JmxCollectionException");
            } catch (JmxMetricsCollector.JmxCollectionException e) {
                long elapsedTime = System.currentTimeMillis() - startTime;

                // Should have waited: 100ms + 200ms = 300ms minimum
                // (3 attempts: immediate, +100ms, +200ms)
                // Allow some tolerance for execution time
                assertThat(elapsedTime).isGreaterThanOrEqualTo(250L);
            }
        }
    }

    @Test
    void collect_shouldHandleInterruptedExceptionDuringRetry() {
        Thread.currentThread().interrupt();

        try (MockedStatic<JMXConnectorFactory> mockedFactory = mockStatic(JMXConnectorFactory.class)) {
            mockedFactory.when(() -> JMXConnectorFactory.connect(any(JMXServiceURL.class), any()))
                    .thenThrow(new IOException("Connection failed"));

            assertThatThrownBy(() -> collector.collect(TEST_JMX_URL))
                    .isInstanceOf(JmxMetricsCollector.JmxCollectionException.class)
                    .hasMessageContaining("Interrupted during retry backoff");

            // Verify interrupt flag is set
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Test
    void collect_shouldSucceedOnSecondAttempt() throws Exception {
        JMXConnector mockConnector = mock(JMXConnector.class);
        MBeanServerConnection mockMBeanServer = mock(MBeanServerConnection.class);

        when(mockConnector.getMBeanServerConnection()).thenReturn(mockMBeanServer);

        // Setup mock responses for all 13 metrics
        setupMockMBeanServer(mockMBeanServer);

        try (MockedStatic<JMXConnectorFactory> mockedFactory = mockStatic(JMXConnectorFactory.class)) {
            // Fail first, succeed second
            mockedFactory.when(() -> JMXConnectorFactory.connect(any(JMXServiceURL.class), any()))
                    .thenThrow(new IOException("First attempt fails"))
                    .thenReturn(mockConnector);

            BrokerMetrics metrics = collector.collect(TEST_JMX_URL);

            assertThat(metrics).isNotNull();
            assertThat(metrics.brokerId()).isEqualTo("localhost:9999");
            assertThat(metrics.heapUsed()).isEqualTo(500_000_000L);
            assertThat(metrics.heapMax()).isEqualTo(1_000_000_000L);
            assertThat(metrics.cpuUsage()).isEqualTo(0.25);

            // Verify retry happened (2 attempts)
            mockedFactory.verify(() -> JMXConnectorFactory.connect(any(JMXServiceURL.class), any()), times(2));
        }
    }

    @Test
    void collect_shouldExtractBrokerIdFromJmxUrl() throws Exception {
        JMXConnector mockConnector = mock(JMXConnector.class);
        MBeanServerConnection mockMBeanServer = mock(MBeanServerConnection.class);

        when(mockConnector.getMBeanServerConnection()).thenReturn(mockMBeanServer);
        setupMockMBeanServer(mockMBeanServer);

        try (MockedStatic<JMXConnectorFactory> mockedFactory = mockStatic(JMXConnectorFactory.class)) {
            mockedFactory.when(() -> JMXConnectorFactory.connect(any(JMXServiceURL.class), any()))
                    .thenReturn(mockConnector);

            String jmxUrl = "service:jmx:rmi:///jndi/rmi://broker-1.example.com:9999/jmxrmi";
            BrokerMetrics metrics = collector.collect(jmxUrl);

            assertThat(metrics.brokerId()).isEqualTo("broker-1.example.com:9999");
        }
    }


    private void setupMockMBeanServer(MBeanServerConnection mockMBeanServer) throws Exception {
        // Use ArgumentMatchers for ObjectName since ObjectName.equals() is finicky
        // 1. Heap Memory
        CompositeDataSupport heapMemory = mock(CompositeDataSupport.class);
        when(heapMemory.get("used")).thenReturn(500_000_000L);
        when(heapMemory.get("max")).thenReturn(1_000_000_000L);
        when(mockMBeanServer.getAttribute(any(ObjectName.class), eq("HeapMemoryUsage")))
                .thenReturn(heapMemory);

        // 2. CPU Usage
        when(mockMBeanServer.getAttribute(any(ObjectName.class), eq("ProcessCpuLoad")))
                .thenReturn(0.25);

        // 3. Request Rate
        when(mockMBeanServer.getAttribute(
                any(ObjectName.class),
                eq("OneMinuteRate")
        )).thenReturn(1000.0, 50_000.0, 100_000.0); // messagesIn, bytesIn, bytesOut

        // 5. Controller Status
        when(mockMBeanServer.getAttribute(
                any(ObjectName.class),
                eq("Value")
        )).thenReturn(1, 0, 0, 75.0, 80.0); // controller, underRep, offline, networkIdle, requestHandlerIdle

        // 8. Request Latencies
        when(mockMBeanServer.getAttribute(
                any(ObjectName.class),
                eq("Mean")
        )).thenReturn(10.5, 5.2); // fetch, produce
    }
}
