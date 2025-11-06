package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.JvmMemoryMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.remote.JMXConnector;

/**
 * Collects JVM memory metrics via JMX from Kafka brokers.
 *
 * <p><b>JMX MBeans:</b>
 * <ul>
 *   <li>java.lang:type=Memory → HeapMemoryUsage</li>
 *   <li>java.lang:type=Memory → NonHeapMemoryUsage</li>
 * </ul>
 *
 * <p><b>Metrics Collected:</b>
 * <ul>
 *   <li>Heap: used, max, committed</li>
 *   <li>Non-Heap: used, max, committed</li>
 * </ul>
 */
public class JvmMemoryMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(JvmMemoryMetricsCollector.class);

    private static final String MEMORY_MBEAN = "java.lang:type=Memory";

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;

    public JvmMemoryMetricsCollector(String jmxUrl, JmxConnectionPool connectionPool) {
        this.jmxUrl = jmxUrl;
        this.connectionPool = connectionPool;
    }

    /**
     * Collects JVM memory metrics from the broker.
     *
     * @param brokerId Broker identifier
     * @return JVM memory metrics
     * @throws Exception if JMX collection fails
     */
    public JvmMemoryMetrics collect(String brokerId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        try {
            MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            ObjectName memoryBean = new ObjectName(MEMORY_MBEAN);

            // Get heap memory usage
            CompositeData heapUsage = (CompositeData) mbsc.getAttribute(memoryBean, "HeapMemoryUsage");
            long heapUsed = (Long) heapUsage.get("used");
            long heapMax = (Long) heapUsage.get("max");
            long heapCommitted = (Long) heapUsage.get("committed");

            // Get non-heap memory usage
            CompositeData nonHeapUsage = (CompositeData) mbsc.getAttribute(memoryBean, "NonHeapMemoryUsage");
            long nonHeapUsed = (Long) nonHeapUsage.get("used");
            long nonHeapMax = (Long) nonHeapUsage.get("max");
            long nonHeapCommitted = (Long) nonHeapUsage.get("committed");

            logger.debug("Collected JVM memory metrics for {}: heap={}MB/{} MB",
                brokerId,
                heapUsed / 1024 / 1024,
                heapMax / 1024 / 1024);

            return new JvmMemoryMetrics(
                brokerId,
                heapUsed,
                heapMax,
                heapCommitted,
                nonHeapUsed,
                nonHeapMax,
                nonHeapCommitted,
                System.currentTimeMillis()
            );

        } finally {
            connectionPool.releaseConnection(jmxUrl);
        }
    }
}
