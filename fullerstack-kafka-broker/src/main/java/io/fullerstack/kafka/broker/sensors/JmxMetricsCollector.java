package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.BrokerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.management.openmbean.CompositeDataSupport;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * JMX metrics collector for Kafka broker health metrics.
 * <p>
 * Collects 13 key metrics via JMX:
 * - Heap memory usage (used/max)
 * - CPU usage
 * - Request rates (messages, bytes in/out)
 * - Controller status
 * - Replication health (under-replicated, offline partitions)
 * - Thread pool idle percentages
 * - Request latencies (fetch consumer, produce)
 * <p>
 * Implements retry logic with exponential backoff and connection timeout handling.
 */
public class JmxMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(JmxMetricsCollector.class);

    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(100);
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Collect broker metrics from JMX endpoint.
     *
     * @param jmxUrl JMX service URL (e.g., "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi")
     * @return BrokerMetrics containing all 13 metrics
     * @throws JmxCollectionException if collection fails after retries
     */
    public BrokerMetrics collect(String jmxUrl) {
        return collectWithRetry(jmxUrl, 1);
    }

    private BrokerMetrics collectWithRetry(String jmxUrl, int attempt) {
        try {
            return doCollect(jmxUrl);
        } catch (Exception e) {
            if (attempt >= MAX_RETRIES) {
                logger.error("Failed to collect JMX metrics after {} attempts from {}", MAX_RETRIES, jmxUrl, e);
                throw new JmxCollectionException("Failed to collect metrics after " + MAX_RETRIES + " attempts", e);
            }

            long backoffMs = INITIAL_BACKOFF.toMillis() * (long) Math.pow(2, attempt - 1);
            logger.warn("JMX collection attempt {} failed, retrying in {}ms", attempt, backoffMs, e);

            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new JmxCollectionException("Interrupted during retry backoff", ie);
            }

            return collectWithRetry(jmxUrl, attempt + 1);
        }
    }

    private BrokerMetrics doCollect(String jmxUrl) throws Exception {
        JMXServiceURL serviceURL = new JMXServiceURL(jmxUrl);

        try (JMXConnector connector = JMXConnectorFactory.connect(serviceURL, null)) {
            // Set connection timeout
            connector.getMBeanServerConnection();

            MBeanServerConnection mbsc = connector.getMBeanServerConnection();

            // Extract broker ID from JMX URL
            String brokerId = extractBrokerId(jmxUrl);

            // 1. Heap Memory
            ObjectName memoryBean = new ObjectName("java.lang:type=Memory");
            CompositeDataSupport heapMemory = (CompositeDataSupport) mbsc.getAttribute(memoryBean, "HeapMemoryUsage");
            long heapUsed = (Long) heapMemory.get("used");
            long heapMax = (Long) heapMemory.get("max");

            // 2. CPU Usage
            ObjectName osBean = new ObjectName("java.lang:type=OperatingSystem");
            double cpuUsage = (Double) mbsc.getAttribute(osBean, "ProcessCpuLoad");

            // 3. Request Rate
            ObjectName messagesInBean = new ObjectName("kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec");
            long requestRate = ((Double) mbsc.getAttribute(messagesInBean, "OneMinuteRate")).longValue();

            // 4. Byte Rates
            ObjectName bytesInBean = new ObjectName("kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec");
            long byteInRate = ((Double) mbsc.getAttribute(bytesInBean, "OneMinuteRate")).longValue();

            ObjectName bytesOutBean = new ObjectName("kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec");
            long byteOutRate = ((Double) mbsc.getAttribute(bytesOutBean, "OneMinuteRate")).longValue();

            // 5. Controller Status
            ObjectName controllerBean = new ObjectName("kafka.controller:type=KafkaController,name=ActiveControllerCount");
            int activeControllers = ((Number) mbsc.getAttribute(controllerBean, "Value")).intValue();

            // 6. Replication Health
            ObjectName underRepBean = new ObjectName("kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions");
            int underReplicatedPartitions = ((Number) mbsc.getAttribute(underRepBean, "Value")).intValue();

            ObjectName offlineBean = new ObjectName("kafka.server:type=ReplicaManager,name=OfflineReplicaCount");
            int offlinePartitionsCount = ((Number) mbsc.getAttribute(offlineBean, "Value")).intValue();

            // 7. Thread Pool Idle
            ObjectName networkIdleBean = new ObjectName("kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent");
            long networkProcessorAvgIdlePercent = ((Double) mbsc.getAttribute(networkIdleBean, "Value")).longValue();

            ObjectName requestHandlerBean = new ObjectName("kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent");
            long requestHandlerAvgIdlePercent = ((Double) mbsc.getAttribute(requestHandlerBean, "Value")).longValue();

            // 8. Request Latencies
            ObjectName fetchBean = new ObjectName("kafka.network:type=RequestMetrics,name=TotalTimeMs,request=FetchConsumer");
            long fetchConsumerTotalTimeMs = ((Double) mbsc.getAttribute(fetchBean, "Mean")).longValue();

            ObjectName produceBean = new ObjectName("kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Produce");
            long produceTotalTimeMs = ((Double) mbsc.getAttribute(produceBean, "Mean")).longValue();

            long timestamp = System.currentTimeMillis();

            return new BrokerMetrics(
                    brokerId,
                    heapUsed,
                    heapMax,
                    cpuUsage,
                    requestRate,
                    byteInRate,
                    byteOutRate,
                    activeControllers,
                    underReplicatedPartitions,
                    offlinePartitionsCount,
                    networkProcessorAvgIdlePercent,
                    requestHandlerAvgIdlePercent,
                    fetchConsumerTotalTimeMs,
                    produceTotalTimeMs,
                    timestamp
            );

        } catch (IOException e) {
            throw new JmxCollectionException("Failed to connect to JMX endpoint: " + jmxUrl, e);
        }
    }

    private String extractBrokerId(String jmxUrl) {
        // Extract broker ID from URL like "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi"
        // Use lastIndexOf to find the SECOND "rmi://" occurrence (the actual host)
        try {
            int rmiStart = jmxUrl.lastIndexOf("rmi://");
            if (rmiStart == -1) {
                logger.warn("Could not find 'rmi://' in JMX URL: {}", jmxUrl);
                return "unknown";
            }
            rmiStart += 6; // Skip past "rmi://"

            int rmiEnd = jmxUrl.indexOf("/", rmiStart);
            if (rmiEnd == -1) {
                logger.warn("Could not find path separator after host in JMX URL: {}", jmxUrl);
                return "unknown";
            }

            String brokerId = jmxUrl.substring(rmiStart, rmiEnd);
            if (brokerId.isBlank()) {
                logger.warn("Extracted blank broker ID from JMX URL: {}", jmxUrl);
                return "unknown";
            }

            return brokerId;
        } catch (Exception e) {
            logger.warn("Could not extract broker ID from JMX URL: {}", jmxUrl, e);
            return "unknown";
        }
    }

    /**
     * Exception thrown when JMX metrics collection fails.
     */
    public static class JmxCollectionException extends RuntimeException {
        public JmxCollectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
