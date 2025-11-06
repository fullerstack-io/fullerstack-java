package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.SystemMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;

/**
 * Collects system-level metrics via JMX from Kafka brokers.
 *
 * <p><b>JMX MBeans:</b>
 * <ul>
 *   <li>java.lang:type=OperatingSystem → ProcessCpuLoad</li>
 *   <li>java.lang:type=OperatingSystem → SystemCpuLoad</li>
 *   <li>java.lang:type=OperatingSystem → OpenFileDescriptorCount</li>
 *   <li>java.lang:type=OperatingSystem → MaxFileDescriptorCount</li>
 * </ul>
 */
public class SystemMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(SystemMetricsCollector.class);

    private static final String OS_MBEAN = "java.lang:type=OperatingSystem";

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;

    public SystemMetricsCollector(String jmxUrl, JmxConnectionPool connectionPool) {
        this.jmxUrl = jmxUrl;
        this.connectionPool = connectionPool;
    }

    /**
     * Collects system metrics from the broker.
     *
     * @param brokerId Broker identifier
     * @return System metrics
     * @throws Exception if JMX collection fails
     */
    public SystemMetrics collect(String brokerId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        try {
            MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            ObjectName osBean = new ObjectName(OS_MBEAN);

            // CPU metrics (may return -1 if not available)
            Double processCpuLoad = (Double) mbsc.getAttribute(osBean, "ProcessCpuLoad");
            Double systemCpuLoad = (Double) mbsc.getAttribute(osBean, "SystemCpuLoad");

            // File descriptor metrics
            Long openFds = (Long) mbsc.getAttribute(osBean, "OpenFileDescriptorCount");
            Long maxFds = (Long) mbsc.getAttribute(osBean, "MaxFileDescriptorCount");

            // Handle -1 values (not available)
            processCpuLoad = processCpuLoad >= 0 ? processCpuLoad : 0.0;
            systemCpuLoad = systemCpuLoad >= 0 ? systemCpuLoad : 0.0;

            logger.debug("Collected system metrics for {}: processCpu={}%, openFDs={}/{}",
                brokerId,
                (int)(processCpuLoad * 100),
                openFds,
                maxFds);

            return new SystemMetrics(
                brokerId,
                processCpuLoad,
                systemCpuLoad,
                openFds,
                maxFds,
                System.currentTimeMillis()
            );

        } finally {
            connectionPool.releaseConnection(jmxUrl);
        }
    }
}
