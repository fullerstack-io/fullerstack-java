package io.fullerstack.kafka.producer.sensors;

import io.fullerstack.kafka.broker.sensors.JmxConnectionPool;
import io.fullerstack.kafka.producer.models.ProducerMetrics;
import io.fullerstack.kafka.producer.sensors.ProducerMetricsCollector.JmxCollectionException;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProducerMetricsCollector}.
 */
class ProducerMetricsCollectorTest {

    @Test
    void testCollectWithoutPooling() throws Exception {
        // Note: This test requires a running Kafka producer with JMX enabled
        // For now, we'll create a basic instantiation test
        ProducerMetricsCollector collector = new ProducerMetricsCollector();
        assertThat(collector).isNotNull();
    }

    @Test
    void testCollectWithPooling() throws Exception {
        // Arrange
        JmxConnectionPool mockPool = mock(JmxConnectionPool.class);
        JMXConnector mockConnector = mock(JMXConnector.class);
        MBeanServerConnection mockMbsc = mock(MBeanServerConnection.class);

        when(mockPool.getConnection(anyString())).thenReturn(mockConnector);
        when(mockConnector.getMBeanServerConnection()).thenReturn(mockMbsc);

        // Mock producer metrics MBean attributes
        ObjectName producerMetricsBean = new ObjectName(
                "kafka.producer:type=producer-metrics,client-id=test-producer");

        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("record-send-rate")))
                .thenReturn(1000.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("request-latency-avg")))
                .thenReturn(25.5);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("request-latency-max")))
                .thenReturn(45.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("batch-size-avg")))
                .thenReturn(50.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("compression-rate-avg")))
                .thenReturn(0.65);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("buffer-available-bytes")))
                .thenReturn(500_000.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("buffer-total-bytes")))
                .thenReturn(1_000_000.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("io-wait-ratio")))
                .thenReturn(0.05);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("record-error-rate")))
                .thenReturn(0.0);

        ProducerMetricsCollector collector = new ProducerMetricsCollector(mockPool);

        // Act
        ProducerMetrics metrics = collector.collect(
                "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi",
                "test-producer"
        );

        // Assert
        assertThat(metrics).isNotNull();
        assertThat(metrics.producerId()).isEqualTo("test-producer");
        assertThat(metrics.sendRate()).isEqualTo(1000);
        assertThat(metrics.avgLatencyMs()).isEqualTo(25.5);
        assertThat(metrics.p99LatencyMs()).isEqualTo(45.0);
        assertThat(metrics.batchSizeAvg()).isEqualTo(50);
        assertThat(metrics.compressionRatio()).isEqualTo(0.65);
        assertThat(metrics.bufferAvailableBytes()).isEqualTo(500_000);
        assertThat(metrics.bufferTotalBytes()).isEqualTo(1_000_000);
        assertThat(metrics.ioWaitRatio()).isEqualTo(5);
        assertThat(metrics.recordErrorRate()).isEqualTo(0);
        assertThat(metrics.timestamp()).isGreaterThan(0);

        // Verify pool usage
        verify(mockPool).getConnection(anyString());
        verify(mockPool).releaseConnection(anyString());
    }

    @Test
    void testCollectWithRetry() throws Exception {
        // Arrange
        JmxConnectionPool mockPool = mock(JmxConnectionPool.class);
        JMXConnector mockConnector = mock(JMXConnector.class);
        MBeanServerConnection mockMbsc = mock(MBeanServerConnection.class);

        when(mockPool.getConnection(anyString())).thenReturn(mockConnector);
        when(mockConnector.getMBeanServerConnection()).thenReturn(mockMbsc);

        ObjectName producerMetricsBean = new ObjectName(
                "kafka.producer:type=producer-metrics,client-id=test-producer");

        // Fail first 2 attempts, succeed on 3rd
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("record-send-rate")))
                .thenThrow(new RuntimeException("Connection timeout"))
                .thenThrow(new RuntimeException("Connection timeout"))
                .thenReturn(1000.0);

        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("request-latency-avg")))
                .thenReturn(25.5);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("request-latency-max")))
                .thenReturn(45.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("batch-size-avg")))
                .thenReturn(50.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("compression-rate-avg")))
                .thenReturn(0.65);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("buffer-available-bytes")))
                .thenReturn(500_000.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("buffer-total-bytes")))
                .thenReturn(1_000_000.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("io-wait-ratio")))
                .thenReturn(0.05);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("record-error-rate")))
                .thenReturn(0.0);

        ProducerMetricsCollector collector = new ProducerMetricsCollector(mockPool);

        // Act
        ProducerMetrics metrics = collector.collect(
                "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi",
                "test-producer"
        );

        // Assert
        assertThat(metrics).isNotNull();
        assertThat(metrics.producerId()).isEqualTo("test-producer");
        assertThat(metrics.sendRate()).isEqualTo(1000);
    }

    @Test
    void testCollectFailsAfterMaxRetries() throws Exception {
        // Arrange
        JmxConnectionPool mockPool = mock(JmxConnectionPool.class);
        JMXConnector mockConnector = mock(JMXConnector.class);
        MBeanServerConnection mockMbsc = mock(MBeanServerConnection.class);

        when(mockPool.getConnection(anyString())).thenReturn(mockConnector);
        when(mockConnector.getMBeanServerConnection()).thenReturn(mockMbsc);

        ObjectName producerMetricsBean = new ObjectName(
                "kafka.producer:type=producer-metrics,client-id=test-producer");

        // Always fail
        when(mockMbsc.getAttribute(eq(producerMetricsBean), anyString()))
                .thenThrow(new RuntimeException("Connection timeout"));

        ProducerMetricsCollector collector = new ProducerMetricsCollector(mockPool);

        // Act & Assert
        assertThatThrownBy(() ->
                collector.collect("service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi", "test-producer")
        )
                .isInstanceOf(JmxCollectionException.class)
                .hasMessageContaining("Failed to collect metrics after 3 attempts");
    }

    @Test
    void testMetricsCalculation() throws Exception {
        // Arrange
        JmxConnectionPool mockPool = mock(JmxConnectionPool.class);
        JMXConnector mockConnector = mock(JMXConnector.class);
        MBeanServerConnection mockMbsc = mock(MBeanServerConnection.class);

        when(mockPool.getConnection(anyString())).thenReturn(mockConnector);
        when(mockConnector.getMBeanServerConnection()).thenReturn(mockMbsc);

        ObjectName producerMetricsBean = new ObjectName(
                "kafka.producer:type=producer-metrics,client-id=test-producer");

        // High buffer utilization scenario (only 10% available)
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("record-send-rate")))
                .thenReturn(2000.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("request-latency-avg")))
                .thenReturn(120.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("request-latency-max")))
                .thenReturn(250.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("batch-size-avg")))
                .thenReturn(75.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("compression-rate-avg")))
                .thenReturn(0.72);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("buffer-available-bytes")))
                .thenReturn(100_000.0);  // Only 10% available
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("buffer-total-bytes")))
                .thenReturn(1_000_000.0);
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("io-wait-ratio")))
                .thenReturn(0.15);  // 15% waiting for I/O
        when(mockMbsc.getAttribute(eq(producerMetricsBean), eq("record-error-rate")))
                .thenReturn(2.0);  // 2 errors/sec

        ProducerMetricsCollector collector = new ProducerMetricsCollector(mockPool);

        // Act
        ProducerMetrics metrics = collector.collect(
                "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi",
                "test-producer"
        );

        // Assert
        assertThat(metrics.bufferUtilization()).isCloseTo(0.9, within(0.01));
        assertThat(metrics.isHealthy()).isFalse();  // High latency, high buffer, errors
        assertThat(metrics.ioWaitRatio()).isEqualTo(15);
        assertThat(metrics.recordErrorRate()).isEqualTo(2);
    }
}
