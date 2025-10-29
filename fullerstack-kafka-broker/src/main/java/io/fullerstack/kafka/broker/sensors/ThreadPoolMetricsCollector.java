package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects thread pool metrics from Kafka brokers via JMX.
 *
 * <p>Kafka brokers expose thread pool metrics via JMX MBeans. This collector
 * queries the following pools:
 * <ul>
 *   <li><b>Network threads</b> - {@code kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent}</li>
 *   <li><b>I/O threads</b> - {@code kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent}</li>
 *   <li><b>Log cleaner</b> - {@code kafka.log:type=LogCleaner,name=cleaner-recopy-percent} (optional)</li>
 * </ul>
 *
 * <p><b>Thread Pool Defaults:</b>
 * <ul>
 *   <li>Network threads: 3 (configured via {@code num.network.threads})</li>
 *   <li>I/O threads: 8 (configured via {@code num.io.threads})</li>
 * </ul>
 *
 * <p><b>Limitation:</b> JMX exposes {@code avgIdlePercent} but NOT absolute thread counts.
 * This collector uses default thread counts (3 network, 8 I/O) and estimates active/idle counts
 * from avgIdlePercent. For production use, thread counts should be made configurable to match
 * actual broker configuration.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * JmxConnectionPool pool = new JmxConnectionPool();
 * ThreadPoolMetricsCollector collector = new ThreadPoolMetricsCollector(
 *     "service:jmx:rmi:///jndi/rmi://broker:11001/jmxrmi",
 *     pool
 * );
 *
 * List<ThreadPoolMetrics> metrics = collector.collect("broker-1");
 * for (ThreadPoolMetrics m : metrics) {
 *     if (m.isExhausted()) {
 *         alert("Thread pool exhaustion: " + m.poolType());
 *     }
 * }
 * }</pre>
 *
 * @see ThreadPoolMetrics
 * @see JmxConnectionPool
 */
public class ThreadPoolMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolMetricsCollector.class);

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;

    // Default thread pool sizes (should match broker config)
    // TODO: Make configurable via BrokerSensorConfig
    private static final int DEFAULT_NETWORK_THREADS = 3;
    private static final int DEFAULT_IO_THREADS = 8;

    /**
     * Creates a new thread pool metrics collector.
     *
     * @param jmxUrl         JMX service URL for the broker
     * @param connectionPool connection pool for JMX connection reuse
     * @throws NullPointerException if jmxUrl or connectionPool is null
     */
    public ThreadPoolMetricsCollector(String jmxUrl, JmxConnectionPool connectionPool) {
        this.jmxUrl = Objects.requireNonNull(jmxUrl, "jmxUrl required");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool required");
    }

    /**
     * Collects metrics for all thread pools on a broker.
     * <p>
     * Returns a list of ThreadPoolMetrics, one for each pool type that is available:
     * <ul>
     *   <li>NETWORK - Always present (network processor threads)</li>
     *   <li>IO - Always present (request handler pool)</li>
     *   <li>LOG_CLEANER - Optional (only if log compaction enabled)</li>
     * </ul>
     *
     * @param brokerId broker identifier (e.g., "broker-1", "0")
     * @return list of thread pool metrics (2-3 items depending on log cleaner availability)
     * @throws Exception if JMX connection or MBean query fails
     */
    public List<ThreadPoolMetrics> collect(String brokerId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();

        List<ThreadPoolMetrics> metrics = new ArrayList<>();

        // Network threads (always available)
        try {
            metrics.add(collectNetworkThreads(brokerId, mbsc));
        } catch (Exception e) {
            logger.warn("Failed to collect network thread metrics for {}: {}",
                brokerId, e.getMessage());
        }

        // I/O threads (always available)
        try {
            metrics.add(collectIoThreads(brokerId, mbsc));
        } catch (Exception e) {
            logger.warn("Failed to collect I/O thread metrics for {}: {}",
                brokerId, e.getMessage());
        }

        // Log cleaner (optional - only if log compaction enabled)
        try {
            ThreadPoolMetrics cleanerMetrics = collectLogCleanerThreads(brokerId, mbsc);
            if (cleanerMetrics != null) {
                metrics.add(cleanerMetrics);
            }
        } catch (Exception e) {
            logger.debug("Log cleaner metrics not available for {}: {}",
                brokerId, e.getMessage());
        }

        connectionPool.releaseConnection(jmxUrl);
        return metrics;
    }

    /**
     * Collects network thread pool metrics.
     * <p>
     * JMX MBean: {@code kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent}
     * <p>
     * Network threads handle socket I/O: accept connections, read requests, write responses.
     * Low idle percentage indicates network saturation (client connection delays).
     *
     * @param brokerId broker identifier
     * @param mbsc     MBean server connection
     * @return network thread pool metrics
     * @throws Exception if MBean query fails
     */
    private ThreadPoolMetrics collectNetworkThreads(String brokerId, MBeanServerConnection mbsc)
        throws Exception {
        ObjectName objectName = new ObjectName(
            "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent"
        );

        double avgIdlePercent = (Double) mbsc.getAttribute(objectName, "Value");

        // Calculate estimated active/idle counts
        int totalThreads = DEFAULT_NETWORK_THREADS;
        int idleThreads = (int) Math.round(totalThreads * avgIdlePercent);
        int activeThreads = totalThreads - idleThreads;

        return new ThreadPoolMetrics(
            brokerId,
            ThreadPoolType.NETWORK,
            totalThreads,
            activeThreads,
            idleThreads,
            avgIdlePercent,
            0L,  // Queue size not exposed for network threads
            0L,  // Total tasks not exposed
            0L,  // Rejections not tracked
            System.currentTimeMillis()
        );
    }

    /**
     * Collects I/O thread pool (request handler pool) metrics.
     * <p>
     * JMX MBean: {@code kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent}
     * <p>
     * I/O threads process requests from the request queue (produce, fetch, metadata).
     * Low idle percentage indicates request processing saturation (high latency).
     *
     * @param brokerId broker identifier
     * @param mbsc     MBean server connection
     * @return I/O thread pool metrics
     * @throws Exception if MBean query fails
     */
    private ThreadPoolMetrics collectIoThreads(String brokerId, MBeanServerConnection mbsc)
        throws Exception {
        ObjectName objectName = new ObjectName(
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"
        );

        double avgIdlePercent = (Double) mbsc.getAttribute(objectName, "Value");

        // Calculate estimated active/idle counts
        int totalThreads = DEFAULT_IO_THREADS;
        int idleThreads = (int) Math.round(totalThreads * avgIdlePercent);
        int activeThreads = totalThreads - idleThreads;

        return new ThreadPoolMetrics(
            brokerId,
            ThreadPoolType.IO,
            totalThreads,
            activeThreads,
            idleThreads,
            avgIdlePercent,
            0L,  // Queue size available via RequestQueueSize metric (future enhancement)
            0L,  // Total tasks not exposed
            0L,  // Rejections not tracked
            System.currentTimeMillis()
        );
    }

    /**
     * Collects log cleaner thread metrics (optional).
     * <p>
     * JMX MBean: {@code kafka.log:type=LogCleaner,name=cleaner-recopy-percent}
     * <p>
     * Log cleaner threads compact log segments (only if {@code log.cleanup.policy=compact} enabled).
     * This method returns null if log cleaner is not enabled or MBean is not available.
     *
     * @param brokerId broker identifier
     * @param mbsc     MBean server connection
     * @return log cleaner thread pool metrics, or null if not available
     */
    private ThreadPoolMetrics collectLogCleanerThreads(String brokerId, MBeanServerConnection mbsc) {
        try {
            ObjectName objectName = new ObjectName(
                "kafka.log:type=LogCleaner,name=cleaner-recopy-percent"
            );

            // Check if MBean exists
            if (!mbsc.isRegistered(objectName)) {
                return null;
            }

            double recopyPercent = (Double) mbsc.getAttribute(objectName, "Value");

            // Log cleaner doesn't expose idle percent like other pools
            // recopyPercent indicates work done (not idle), so invert it
            double avgIdlePercent = Math.max(0.0, 1.0 - recopyPercent);

            // Log cleaner thread count is typically 1, but can be configured
            int totalThreads = 1;
            int idleThreads = avgIdlePercent > 0.5 ? 1 : 0;
            int activeThreads = totalThreads - idleThreads;

            return new ThreadPoolMetrics(
                brokerId,
                ThreadPoolType.LOG_CLEANER,
                totalThreads,
                activeThreads,
                idleThreads,
                avgIdlePercent,
                0L,
                0L,
                0L,
                System.currentTimeMillis()
            );
        } catch (Exception e) {
            logger.debug("Log cleaner not available for {}: {}", brokerId, e.getMessage());
            return null;
        }
    }
}
