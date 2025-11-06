package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.LogMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Collects log metrics from Kafka brokers via JMX.
 *
 * <p>Kafka brokers expose log metrics via JMX MBeans in two patterns:
 * <pre>
 * # Broker-level metrics
 * kafka.log:type=LogFlushStats,name=LogFlushRateAndTimeMs
 *
 * # Per-partition metrics
 * kafka.log:type=Log,name={MetricName},topic={Topic},partition={Partition}
 * </pre>
 *
 * <p><b>Metrics Collected:</b>
 * <ul>
 *   <li><b>LogFlushRateAndTimeMs</b> - Broker-level flush rate (Count attribute)</li>
 *   <li><b>NumLogSegments</b> - Per-partition segment count</li>
 *   <li><b>Size</b> - Per-partition log size in bytes</li>
 *   <li><b>LogEndOffset</b> - Per-partition highest offset</li>
 *   <li><b>LogStartOffset</b> - Per-partition lowest offset</li>
 * </ul>
 *
 * <p><b>Multi-Entity Pattern:</b> This collector discovers all topic-partition
 * combinations dynamically by querying JMX MBean names with wildcards.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * JmxConnectionPool pool = new JmxConnectionPool();
 * LogMetricsCollector collector = new LogMetricsCollector(
 *     "service:jmx:rmi:///jndi/rmi://broker:11001/jmxrmi",
 *     pool,
 *     1073741824L  // 1GB retention limit
 * );
 *
 * List<LogMetrics> metrics = collector.collect("broker-1");
 * for (LogMetrics m : metrics) {
 *     if (m.isNearRetentionLimit()) {
 *         alert("Partition " + m.partitionId() + " near retention limit");
 *     }
 * }
 * }</pre>
 *
 * @see LogMetrics
 * @see JmxConnectionPool
 */
public class LogMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(LogMetricsCollector.class);

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;
    private final long defaultRetentionBytes;

    /**
     * Creates a new log metrics collector.
     *
     * @param jmxUrl                JMX service URL for the broker
     * @param connectionPool        connection pool for JMX connection reuse
     * @param defaultRetentionBytes default retention limit in bytes (0 = unlimited)
     * @throws NullPointerException     if jmxUrl or connectionPool is null
     * @throws IllegalArgumentException if defaultRetentionBytes is negative
     */
    public LogMetricsCollector(
        String jmxUrl,
        JmxConnectionPool connectionPool,
        long defaultRetentionBytes
    ) {
        this.jmxUrl = Objects.requireNonNull(jmxUrl, "jmxUrl required");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool required");
        if (defaultRetentionBytes < 0) {
            throw new IllegalArgumentException("defaultRetentionBytes must be >= 0, got: " + defaultRetentionBytes);
        }
        this.defaultRetentionBytes = defaultRetentionBytes;
    }

    /**
     * Collects metrics for all topic partitions on a broker.
     * <p>
     * Discovers partitions dynamically by querying JMX with wildcard patterns.
     * Returns a list of LogMetrics, one for each partition found.
     *
     * @param brokerId broker identifier (e.g., "broker-1", "0")
     * @return list of log metrics (one per partition)
     * @throws Exception if JMX connection or MBean query fails
     */
    public List<LogMetrics> collect(String brokerId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();

        List<LogMetrics> metrics = new ArrayList<>();
        long timestamp = System.currentTimeMillis();

        // Get broker-level flush rate (shared across all partitions)
        double flushRatePerSec = getBrokerFlushRate(mbsc);

        // Discover all topic-partition combinations
        Set<ObjectName> logMBeans = mbsc.queryNames(
            new ObjectName("kafka.log:type=Log,name=Size,topic=*,partition=*"),
            null
        );

        // Collect metrics for each partition
        for (ObjectName logMBean : logMBeans) {
            try {
                String topic = logMBean.getKeyProperty("topic");
                int partition = Integer.parseInt(logMBean.getKeyProperty("partition"));

                LogMetrics metric = collectPartition(
                    mbsc,
                    brokerId,
                    topic,
                    partition,
                    flushRatePerSec,
                    timestamp
                );
                metrics.add(metric);

            } catch (Exception e) {
                logger.debug("Failed to collect log metrics for {}: {}",
                    logMBean, e.getMessage());
                // Continue with other partitions
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Collected log metrics for {} partitions on {}",
                metrics.size(), brokerId);
        }

        return metrics;
    }

    /**
     * Gets broker-level log flush rate.
     */
    private double getBrokerFlushRate(MBeanServerConnection mbsc) throws Exception {
        try {
            ObjectName name = new ObjectName(
                "kafka.log:type=LogFlushStats,name=LogFlushRateAndTimeMs"
            );
            Number count = (Number) mbsc.getAttribute(name, "Count");
            return count.doubleValue();
        } catch (Exception e) {
            logger.debug("LogFlushStats not available, assuming 0: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Collects metrics for a specific topic-partition.
     */
    private LogMetrics collectPartition(
        MBeanServerConnection mbsc,
        String brokerId,
        String topic,
        int partition,
        double flushRatePerSec,
        long timestamp
    ) throws Exception {

        int numSegments = getNumSegments(mbsc, topic, partition);
        long sizeBytes = getSize(mbsc, topic, partition);
        long logEndOffset = getLogEndOffset(mbsc, topic, partition);
        long logStartOffset = getLogStartOffset(mbsc, topic, partition);

        return new LogMetrics(
            brokerId,
            topic,
            partition,
            flushRatePerSec,
            numSegments,
            sizeBytes,
            logEndOffset,
            logStartOffset,
            defaultRetentionBytes,
            timestamp
        );
    }

    /**
     * Gets number of log segments for a partition.
     */
    private int getNumSegments(MBeanServerConnection mbsc, String topic, int partition) throws Exception {
        ObjectName name = new ObjectName(
            "kafka.log:type=Log,name=NumLogSegments,topic=" + topic + ",partition=" + partition
        );
        Number value = (Number) mbsc.getAttribute(name, "Value");
        return value.intValue();
    }

    /**
     * Gets log size in bytes for a partition.
     */
    private long getSize(MBeanServerConnection mbsc, String topic, int partition) throws Exception {
        ObjectName name = new ObjectName(
            "kafka.log:type=Log,name=Size,topic=" + topic + ",partition=" + partition
        );
        Number value = (Number) mbsc.getAttribute(name, "Value");
        return value.longValue();
    }

    /**
     * Gets log end offset for a partition.
     */
    private long getLogEndOffset(MBeanServerConnection mbsc, String topic, int partition) throws Exception {
        ObjectName name = new ObjectName(
            "kafka.log:type=Log,name=LogEndOffset,topic=" + topic + ",partition=" + partition
        );
        Number value = (Number) mbsc.getAttribute(name, "Value");
        return value.longValue();
    }

    /**
     * Gets log start offset for a partition.
     */
    private long getLogStartOffset(MBeanServerConnection mbsc, String topic, int partition) throws Exception {
        ObjectName name = new ObjectName(
            "kafka.log:type=Log,name=LogStartOffset,topic=" + topic + ",partition=" + partition
        );
        Number value = (Number) mbsc.getAttribute(name, "Value");
        return value.longValue();
    }
}
