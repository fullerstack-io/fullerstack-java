package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ThreadPoolMetricsCollector}.
 */
class ThreadPoolMetricsCollectorTest {

    private JmxConnectionPool mockPool;
    private JMXConnector mockConnector;
    private MBeanServerConnection mockMbsc;
    private ThreadPoolMetricsCollector collector;

    private static final String TEST_JMX_URL = "service:jmx:rmi:///jndi/rmi://localhost:11001/jmxrmi";
    private static final String TEST_BROKER_ID = "broker-1";

    @BeforeEach
    void setUp() throws Exception {
        mockPool = mock(JmxConnectionPool.class);
        mockConnector = mock(JMXConnector.class);
        mockMbsc = mock(MBeanServerConnection.class);

        when(mockPool.getConnection(TEST_JMX_URL)).thenReturn(mockConnector);
        when(mockConnector.getMBeanServerConnection()).thenReturn(mockMbsc);

        collector = new ThreadPoolMetricsCollector(TEST_JMX_URL, mockPool);
    }

    @Test
    void constructorRequiresJmxUrl() {
        assertThatThrownBy(() -> new ThreadPoolMetricsCollector(null, mockPool))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("jmxUrl required");
    }

    @Test
    void constructorRequiresConnectionPool() {
        assertThatThrownBy(() -> new ThreadPoolMetricsCollector(TEST_JMX_URL, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("connectionPool required");
    }

    @Test
    void collectReturnsNetworkThreadMetrics() throws Exception {
        // Setup network thread metrics
        ObjectName networkMBean = new ObjectName(
            "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(networkMBean, "Value")).thenReturn(0.50);  // 50% idle

        // Setup I/O thread metrics (required)
        ObjectName ioMBean = new ObjectName(
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(ioMBean, "Value")).thenReturn(0.30);

        List<ThreadPoolMetrics> metrics = collector.collect(TEST_BROKER_ID);

        ThreadPoolMetrics networkMetrics = metrics.stream()
            .filter(m -> m.poolType() == ThreadPoolType.NETWORK)
            .findFirst()
            .orElseThrow();

        assertThat(networkMetrics.brokerId()).isEqualTo(TEST_BROKER_ID);
        assertThat(networkMetrics.poolType()).isEqualTo(ThreadPoolType.NETWORK);
        assertThat(networkMetrics.totalThreads()).isEqualTo(3);  // Default
        assertThat(networkMetrics.avgIdlePercent()).isEqualTo(0.50);
        assertThat(networkMetrics.idleThreads()).isEqualTo(2);  // 50% of 3 = 1.5 → 2
        assertThat(networkMetrics.activeThreads()).isEqualTo(1); // 3 - 2 = 1
    }

    @Test
    void collectReturnsIoThreadMetrics() throws Exception {
        // Setup network thread metrics (required)
        ObjectName networkMBean = new ObjectName(
            "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(networkMBean, "Value")).thenReturn(0.50);

        // Setup I/O thread metrics
        ObjectName ioMBean = new ObjectName(
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(ioMBean, "Value")).thenReturn(0.25);  // 25% idle

        List<ThreadPoolMetrics> metrics = collector.collect(TEST_BROKER_ID);

        ThreadPoolMetrics ioMetrics = metrics.stream()
            .filter(m -> m.poolType() == ThreadPoolType.IO)
            .findFirst()
            .orElseThrow();

        assertThat(ioMetrics.brokerId()).isEqualTo(TEST_BROKER_ID);
        assertThat(ioMetrics.poolType()).isEqualTo(ThreadPoolType.IO);
        assertThat(ioMetrics.totalThreads()).isEqualTo(8);  // Default
        assertThat(ioMetrics.avgIdlePercent()).isEqualTo(0.25);
        assertThat(ioMetrics.idleThreads()).isEqualTo(2);  // 25% of 8 = 2
        assertThat(ioMetrics.activeThreads()).isEqualTo(6); // 8 - 2 = 6
    }

    @Test
    void collectSkipsLogCleanerIfNotAvailable() throws Exception {
        // Setup network thread metrics
        ObjectName networkMBean = new ObjectName(
            "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(networkMBean, "Value")).thenReturn(0.50);

        // Setup I/O thread metrics
        ObjectName ioMBean = new ObjectName(
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(ioMBean, "Value")).thenReturn(0.30);

        // Log cleaner not registered
        ObjectName cleanerMBean = new ObjectName(
            "kafka.log:type=LogCleaner,name=cleaner-recopy-percent"
        );
        when(mockMbsc.isRegistered(cleanerMBean)).thenReturn(false);

        List<ThreadPoolMetrics> metrics = collector.collect(TEST_BROKER_ID);

        assertThat(metrics).hasSize(2);  // Only network + I/O, no log cleaner
        assertThat(metrics).noneMatch(m -> m.poolType() == ThreadPoolType.LOG_CLEANER);
    }

    @Test
    void collectIncludesLogCleanerIfAvailable() throws Exception {
        // Setup network thread metrics
        ObjectName networkMBean = new ObjectName(
            "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(networkMBean, "Value")).thenReturn(0.50);

        // Setup I/O thread metrics
        ObjectName ioMBean = new ObjectName(
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(ioMBean, "Value")).thenReturn(0.30);

        // Log cleaner available
        ObjectName cleanerMBean = new ObjectName(
            "kafka.log:type=LogCleaner,name=cleaner-recopy-percent"
        );
        when(mockMbsc.isRegistered(cleanerMBean)).thenReturn(true);
        when(mockMbsc.getAttribute(cleanerMBean, "Value")).thenReturn(0.20);  // 20% recopy

        List<ThreadPoolMetrics> metrics = collector.collect(TEST_BROKER_ID);

        assertThat(metrics).hasSize(3);  // Network + I/O + log cleaner

        ThreadPoolMetrics cleanerMetrics = metrics.stream()
            .filter(m -> m.poolType() == ThreadPoolType.LOG_CLEANER)
            .findFirst()
            .orElseThrow();

        assertThat(cleanerMetrics.brokerId()).isEqualTo(TEST_BROKER_ID);
        assertThat(cleanerMetrics.poolType()).isEqualTo(ThreadPoolType.LOG_CLEANER);
        assertThat(cleanerMetrics.avgIdlePercent()).isEqualTo(0.80);  // 1.0 - 0.20 = 0.80
    }

    @Test
    void collectHandlesNetworkThreadFailureGracefully() throws Exception {
        // Network thread query fails
        ObjectName networkMBean = new ObjectName(
            "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(networkMBean, "Value"))
            .thenThrow(new RuntimeException("Network MBean not found"));

        // I/O thread succeeds
        ObjectName ioMBean = new ObjectName(
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(ioMBean, "Value")).thenReturn(0.30);

        List<ThreadPoolMetrics> metrics = collector.collect(TEST_BROKER_ID);

        // Should return I/O metrics despite network failure
        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).poolType()).isEqualTo(ThreadPoolType.IO);
    }

    @Test
    void collectHandlesIoThreadFailureGracefully() throws Exception {
        // Network thread succeeds
        ObjectName networkMBean = new ObjectName(
            "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(networkMBean, "Value")).thenReturn(0.50);

        // I/O thread query fails
        ObjectName ioMBean = new ObjectName(
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(ioMBean, "Value"))
            .thenThrow(new RuntimeException("I/O MBean not found"));

        List<ThreadPoolMetrics> metrics = collector.collect(TEST_BROKER_ID);

        // Should return network metrics despite I/O failure
        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).poolType()).isEqualTo(ThreadPoolType.NETWORK);
    }

    @Test
    void collectReleasesConnectionAfterCollection() throws Exception {
        // Setup basic metrics
        ObjectName networkMBean = new ObjectName(
            "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(networkMBean, "Value")).thenReturn(0.50);

        ObjectName ioMBean = new ObjectName(
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(ioMBean, "Value")).thenReturn(0.30);

        collector.collect(TEST_BROKER_ID);

        // Verify connection released
        verify(mockPool).releaseConnection(TEST_JMX_URL);
    }

    @Test
    void collectCalculatesCorrectActiveIdleThreadCounts() throws Exception {
        // Network: 90% idle (very healthy)
        ObjectName networkMBean = new ObjectName(
            "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(networkMBean, "Value")).thenReturn(0.90);

        // I/O: 10% idle (degraded)
        ObjectName ioMBean = new ObjectName(
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"
        );
        when(mockMbsc.getAttribute(ioMBean, "Value")).thenReturn(0.10);

        List<ThreadPoolMetrics> metrics = collector.collect(TEST_BROKER_ID);

        ThreadPoolMetrics networkMetrics = metrics.stream()
            .filter(m -> m.poolType() == ThreadPoolType.NETWORK)
            .findFirst()
            .orElseThrow();

        // 90% of 3 = 2.7 → 3 idle, 0 active
        assertThat(networkMetrics.idleThreads()).isEqualTo(3);
        assertThat(networkMetrics.activeThreads()).isEqualTo(0);

        ThreadPoolMetrics ioMetrics = metrics.stream()
            .filter(m -> m.poolType() == ThreadPoolType.IO)
            .findFirst()
            .orElseThrow();

        // 10% of 8 = 0.8 → 1 idle, 7 active
        assertThat(ioMetrics.idleThreads()).isEqualTo(1);
        assertThat(ioMetrics.activeThreads()).isEqualTo(7);
    }
}
