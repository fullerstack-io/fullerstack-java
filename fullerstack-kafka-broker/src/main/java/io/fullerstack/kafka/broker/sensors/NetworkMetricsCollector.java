package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.NetworkMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.Objects;

/**
 * Collects network I/O metrics from Kafka brokers via JMX.
 *
 * <p>Kafka brokers expose network metrics via JMX MBeans. This collector
 * queries the following metrics:
 * <ul>
 *   <li><b>Request Rate</b> - {@code kafka.network:type=RequestMetrics,name=RequestsPerSec}</li>
 *   <li><b>Response Rate</b> - {@code kafka.network:type=RequestMetrics,name=ResponsesPerSec}</li>
 *   <li><b>Bytes In Rate</b> - {@code kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec}</li>
 *   <li><b>Bytes Out Rate</b> - {@code kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec}</li>
 *   <li><b>Messages In Rate</b> - {@code kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec}</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * JmxConnectionPool pool = new JmxConnectionPool();
 * NetworkMetricsCollector collector = new NetworkMetricsCollector(
 *     "service:jmx:rmi:///jndi/rmi://broker:11001/jmxrmi",
 *     pool
 * );
 *
 * NetworkMetrics metrics = collector.collect("broker-1");
 * if (metrics.isSaturated()) {
 *     alert("Network saturation detected: " + metrics.bytesInRate());
 * }
 * }</pre>
 *
 * @see NetworkMetrics
 * @see JmxConnectionPool
 */
public class NetworkMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(NetworkMetricsCollector.class);

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;

    /**
     * Creates a new network metrics collector.
     *
     * @param jmxUrl         JMX service URL for the broker
     * @param connectionPool connection pool for JMX connection reuse
     * @throws NullPointerException if jmxUrl or connectionPool is null
     */
    public NetworkMetricsCollector(String jmxUrl, JmxConnectionPool connectionPool) {
        this.jmxUrl = Objects.requireNonNull(jmxUrl, "jmxUrl required");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool required");
    }

    /**
     * Collects network I/O metrics from a broker.
     * <p>
     * Queries JMX for request/response rates, bytes in/out, and messages in.
     * All rates are per-second values exposed by Kafka's MeterMBean attributes.
     *
     * @param brokerId broker identifier (e.g., "broker-1", "0")
     * @return network metrics snapshot
     * @throws Exception if JMX connection or MBean query fails
     */
    public NetworkMetrics collect(String brokerId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();

        try {
            // Request rate (all request types)
            double requestRate = collectMeterRate(mbsc,
                "kafka.network:type=RequestMetrics,name=RequestsPerSec");

            // Response rate (all response types)
            double responseRate = collectMeterRate(mbsc,
                "kafka.network:type=RequestMetrics,name=ResponsesPerSec");

            // Bytes in rate (producer writes)
            double bytesInRate = collectMeterRate(mbsc,
                "kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec");

            // Bytes out rate (consumer reads)
            double bytesOutRate = collectMeterRate(mbsc,
                "kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec");

            // Messages in rate (producer messages)
            double messagesInRate = collectMeterRate(mbsc,
                "kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec");

            return new NetworkMetrics(
                brokerId,
                requestRate,
                responseRate,
                bytesInRate,
                bytesOutRate,
                messagesInRate,
                System.currentTimeMillis()
            );
        } finally {
            connectionPool.releaseConnection(jmxUrl);
        }
    }

    /**
     * Collects rate from a Kafka MeterMBean.
     * <p>
     * Kafka exposes rate metrics via MeterMBean with attributes:
     * <ul>
     *   <li>Count - Cumulative count</li>
     *   <li>MeanRate - Average rate since startup</li>
     *   <li>OneMinuteRate - 1-minute exponentially-weighted moving average</li>
     *   <li>FiveMinuteRate - 5-minute EWMA</li>
     *   <li>FifteenMinuteRate - 15-minute EWMA</li>
     * </ul>
     * We use OneMinuteRate for near real-time rate tracking.
     *
     * @param mbsc       MBean server connection
     * @param objectName MBean object name
     * @return rate per second (OneMinuteRate)
     * @throws Exception if MBean query fails
     */
    private double collectMeterRate(MBeanServerConnection mbsc, String objectName) throws Exception {
        try {
            ObjectName name = new ObjectName(objectName);
            if (!mbsc.isRegistered(name)) {
                logger.warn("MBean not registered: {}", objectName);
                return 0.0;
            }
            Object rate = mbsc.getAttribute(name, "OneMinuteRate");
            return rate != null ? ((Number) rate).doubleValue() : 0.0;
        } catch (Exception e) {
            logger.warn("Failed to collect meter rate for {}: {}", objectName, e.getMessage());
            return 0.0;
        }
    }
}
