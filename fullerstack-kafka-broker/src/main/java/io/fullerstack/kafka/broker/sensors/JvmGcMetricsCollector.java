package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.JvmGcMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Collects JVM garbage collection metrics via JMX from Kafka brokers.
 *
 * <p><b>JMX MBeans:</b>
 * <ul>
 *   <li>java.lang:type=GarbageCollector,name=* → CollectionCount</li>
 *   <li>java.lang:type=GarbageCollector,name=* → CollectionTime</li>
 * </ul>
 *
 * <p>Supports all GC collectors:
 * <ul>
 *   <li>G1 Young Generation</li>
 *   <li>G1 Old Generation</li>
 *   <li>ZGC (if configured)</li>
 *   <li>Shenandoah (if configured)</li>
 * </ul>
 */
public class JvmGcMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(JvmGcMetricsCollector.class);

    private static final String GC_MBEAN_PATTERN = "java.lang:type=GarbageCollector,name=*";

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;

    public JvmGcMetricsCollector(String jmxUrl, JmxConnectionPool connectionPool) {
        this.jmxUrl = jmxUrl;
        this.connectionPool = connectionPool;
    }

    /**
     * Collects GC metrics for all garbage collectors.
     *
     * @param brokerId Broker identifier
     * @return List of GC metrics (one per collector)
     * @throws Exception if JMX collection fails
     */
    public List<JvmGcMetrics> collect(String brokerId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        try {
            MBeanServerConnection mbsc = connector.getMBeanServerConnection();
            ObjectName gcPattern = new ObjectName(GC_MBEAN_PATTERN);

            Set<ObjectName> gcBeans = mbsc.queryNames(gcPattern, null);
            List<JvmGcMetrics> metrics = new ArrayList<>();

            for (ObjectName gcBean : gcBeans) {
                String collectorName = gcBean.getKeyProperty("name");
                long collectionCount = (Long) mbsc.getAttribute(gcBean, "CollectionCount");
                long collectionTime = (Long) mbsc.getAttribute(gcBean, "CollectionTime");

                metrics.add(new JvmGcMetrics(
                    brokerId,
                    collectorName,
                    collectionCount,
                    collectionTime,
                    System.currentTimeMillis()
                ));

                logger.debug("Collected GC metrics for {}.{}: count={}, time={}ms",
                    brokerId, collectorName, collectionCount, collectionTime);
            }

            return metrics;

        } finally {
            connectionPool.releaseConnection(jmxUrl);
        }
    }
}
