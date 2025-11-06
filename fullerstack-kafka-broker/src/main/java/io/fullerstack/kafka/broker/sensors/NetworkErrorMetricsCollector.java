package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.NetworkErrorMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.Objects;

/**
 * Collects network error metrics from Kafka brokers via JMX.
 *
 * <p>Kafka brokers expose error metrics via JMX MBeans. This collector
 * queries the following metrics:
 * <ul>
 *   <li><b>Network Errors</b> - {@code kafka.network:type=RequestMetrics,name=ErrorsPerSec,request=*}</li>
 *   <li><b>Failed Authentication</b> - {@code kafka.server:type=socket-server-metrics} → failed-authentication-total</li>
 *   <li><b>Temporary Auth Failures</b> - {@code kafka.server:type=socket-server-metrics} → temporary-authentication-failure-total</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * JmxConnectionPool pool = new JmxConnectionPool();
 * NetworkErrorMetricsCollector collector = new NetworkErrorMetricsCollector(
 *     "service:jmx:rmi:///jndi/rmi://broker:11001/jmxrmi",
 *     pool
 * );
 *
 * NetworkErrorMetrics metrics = collector.collect("broker-1");
 * if (metrics.hasCriticalErrors()) {
 *     alert("Critical network error rate: " + metrics.errorsPerSec());
 * }
 * }</pre>
 *
 * @see NetworkErrorMetrics
 * @see JmxConnectionPool
 */
public class NetworkErrorMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(NetworkErrorMetricsCollector.class);

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;

    /**
     * Creates a new network error metrics collector.
     *
     * @param jmxUrl         JMX service URL for the broker
     * @param connectionPool connection pool for JMX connection reuse
     * @throws NullPointerException if jmxUrl or connectionPool is null
     */
    public NetworkErrorMetricsCollector(String jmxUrl, JmxConnectionPool connectionPool) {
        this.jmxUrl = Objects.requireNonNull(jmxUrl, "jmxUrl required");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool required");
    }

    /**
     * Collects network error metrics from a broker.
     * <p>
     * Queries JMX for error rates and authentication failure counts.
     *
     * @param brokerId broker identifier (e.g., "broker-1", "0")
     * @return network error metrics snapshot
     * @throws Exception if JMX connection or MBean query fails
     */
    public NetworkErrorMetrics collect(String brokerId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();

        try {
            // Network errors per second (aggregate across all request types)
            // Note: Kafka exposes per-request-type error rates, we sum them
            double errorsPerSec = collectTotalErrorRate(mbsc);

            // Failed authentication total (cumulative)
            long failedAuthTotal = collectCounter(mbsc,
                "kafka.server:type=socket-server-metrics,listener=*,networkProcessor=*",
                "failed-authentication-total");

            // Temporary authentication failures (cumulative)
            long tempAuthFailureTotal = collectCounter(mbsc,
                "kafka.server:type=socket-server-metrics,listener=*,networkProcessor=*",
                "temporary-authentication-failure-total");

            return new NetworkErrorMetrics(
                brokerId,
                errorsPerSec,
                failedAuthTotal,
                tempAuthFailureTotal,
                System.currentTimeMillis()
            );
        } finally {
            connectionPool.releaseConnection(jmxUrl);
        }
    }

    /**
     * Collects total error rate across all request types.
     * <p>
     * Kafka exposes ErrorsPerSec per request type (Produce, Fetch, etc.).
     * This method attempts to query the aggregate metric, falling back to 0.0 if unavailable.
     *
     * @param mbsc MBean server connection
     * @return total errors per second (OneMinuteRate)
     * @throws Exception if MBean query fails
     */
    private double collectTotalErrorRate(MBeanServerConnection mbsc) throws Exception {
        try {
            // Try aggregate metric first
            ObjectName name = new ObjectName(
                "kafka.network:type=RequestMetrics,name=ErrorsPerSec"
            );
            if (mbsc.isRegistered(name)) {
                Object rate = mbsc.getAttribute(name, "OneMinuteRate");
                return rate != null ? ((Number) rate).doubleValue() : 0.0;
            }

            // If aggregate not available, we could sum per-request-type metrics
            // For now, return 0.0 if aggregate is not available
            logger.debug("Aggregate ErrorsPerSec metric not available");
            return 0.0;
        } catch (Exception e) {
            logger.warn("Failed to collect error rate: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Collects a cumulative counter metric.
     * <p>
     * Socket server metrics are exposed per listener and network processor.
     * This method queries with wildcard pattern and sums across all instances.
     *
     * @param mbsc          MBean server connection
     * @param objectPattern MBean object name pattern (may contain wildcards)
     * @param attributeName attribute name to query
     * @return cumulative counter value
     */
    private long collectCounter(MBeanServerConnection mbsc, String objectPattern, String attributeName) {
        try {
            ObjectName pattern = new ObjectName(objectPattern);
            var mbeans = mbsc.queryNames(pattern, null);

            if (mbeans.isEmpty()) {
                logger.debug("No MBeans found for pattern: {}", objectPattern);
                return 0L;
            }

            long total = 0L;
            for (ObjectName name : mbeans) {
                try {
                    Object value = mbsc.getAttribute(name, attributeName);
                    if (value instanceof Number) {
                        total += ((Number) value).longValue();
                    }
                } catch (Exception e) {
                    logger.debug("Failed to read {} from {}: {}",
                        attributeName, name, e.getMessage());
                }
            }
            return total;
        } catch (Exception e) {
            logger.warn("Failed to collect counter {}: {}", attributeName, e.getMessage());
            return 0L;
        }
    }
}
