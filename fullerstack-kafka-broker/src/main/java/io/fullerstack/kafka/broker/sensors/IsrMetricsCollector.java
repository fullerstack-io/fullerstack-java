package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.IsrMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.Objects;

/**
 * Collects In-Sync Replica (ISR) replication metrics from Kafka brokers via JMX.
 *
 * <p>Kafka brokers expose ISR metrics via JMX MBeans. This collector queries:
 * <ul>
 *   <li><b>ISR Shrinks</b> - {@code kafka.server:type=ReplicaManager,name=IsrShrinksPerSec} → Count</li>
 *   <li><b>ISR Expands</b> - {@code kafka.server:type=ReplicaManager,name=IsrExpandsPerSec} → Count</li>
 *   <li><b>Replica Max Lag</b> - {@code kafka.server:type=ReplicaFetcherManager,name=MaxLag,clientId=Replica} → Value</li>
 *   <li><b>Replica Min Fetch Rate</b> - {@code kafka.server:type=ReplicaFetcherManager,name=MinFetchRate,clientId=Replica} → Value</li>
 * </ul>
 *
 * <p><b>ISR Shrink</b>: Follower falls behind leader's log end offset by more than
 * {@code replica.lag.time.max.ms} and is removed from ISR. This reduces replication redundancy.
 *
 * <p><b>ISR Expand</b>: Follower catches up and is added back to ISR, restoring replication redundancy.
 *
 * <p><b>Replica Lag</b>: Maximum message lag across all follower replicas. High lag is an
 * early warning that ISR shrink may occur.
 *
 * <p><b>Fetch Rate</b>: Minimum fetch rate across all follower replicas. Low fetch rate
 * indicates replication is slowing down.
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * JmxConnectionPool pool = new JmxConnectionPool();
 * IsrMetricsCollector collector = new IsrMetricsCollector(
 *     "service:jmx:rmi:///jndi/rmi://broker:11001/jmxrmi",
 *     pool
 * );
 *
 * IsrMetrics metrics = collector.collect("broker-1");
 * if (metrics.hasShrinkEvents()) {
 *     alert("ISR shrink detected on broker-1!");
 * }
 * }</pre>
 *
 * @see IsrMetrics
 * @see JmxConnectionPool
 */
public class IsrMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(IsrMetricsCollector.class);

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;

    /**
     * Creates a new ISR metrics collector.
     *
     * @param jmxUrl         JMX service URL for the broker
     * @param connectionPool connection pool for JMX connection reuse
     * @throws NullPointerException if jmxUrl or connectionPool is null
     */
    public IsrMetricsCollector(String jmxUrl, JmxConnectionPool connectionPool) {
        this.jmxUrl = Objects.requireNonNull(jmxUrl, "jmxUrl required");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool required");
    }

    /**
     * Collects ISR replication metrics from a broker.
     * <p>
     * Returns cumulative counts for shrinks/expands since broker start, and current
     * lag/fetch rate values. Caller should compute deltas to detect new events.
     *
     * @param brokerId broker identifier (e.g., "broker-1", "0")
     * @return ISR replication metrics
     * @throws Exception if JMX connection or MBean query fails
     */
    public IsrMetrics collect(String brokerId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();

        try {
            // Collect all ISR metrics
            long shrinkCount = collectIsrShrinkCount(mbsc);
            long expandCount = collectIsrExpandCount(mbsc);
            long maxLag = collectReplicaMaxLag(mbsc);
            double minFetchRate = collectReplicaMinFetchRate(mbsc);

            return new IsrMetrics(
                brokerId,
                shrinkCount,
                expandCount,
                maxLag,
                minFetchRate,
                System.currentTimeMillis()
            );
        } finally {
            connectionPool.releaseConnection(jmxUrl);
        }
    }

    /**
     * Collects ISR shrink count.
     * <p>
     * JMX MBean: {@code kafka.server:type=ReplicaManager,name=IsrShrinksPerSec}
     * <p>
     * Returns cumulative count of ISR shrinks since broker start. Caller should track
     * previous count and compute delta to detect new shrink events.
     *
     * @param mbsc MBean server connection
     * @return cumulative ISR shrink count
     * @throws Exception if MBean query fails
     */
    private long collectIsrShrinkCount(MBeanServerConnection mbsc) throws Exception {
        ObjectName objectName = new ObjectName(
            "kafka.server:type=ReplicaManager,name=IsrShrinksPerSec"
        );

        // Get cumulative count (not rate)
        Number count = (Number) mbsc.getAttribute(objectName, "Count");
        return count.longValue();
    }

    /**
     * Collects ISR expand count.
     * <p>
     * JMX MBean: {@code kafka.server:type=ReplicaManager,name=IsrExpandsPerSec}
     * <p>
     * Returns cumulative count of ISR expands since broker start. Caller should track
     * previous count and compute delta to detect new expand events.
     *
     * @param mbsc MBean server connection
     * @return cumulative ISR expand count
     * @throws Exception if MBean query fails
     */
    private long collectIsrExpandCount(MBeanServerConnection mbsc) throws Exception {
        ObjectName objectName = new ObjectName(
            "kafka.server:type=ReplicaManager,name=IsrExpandsPerSec"
        );

        // Get cumulative count (not rate)
        Number count = (Number) mbsc.getAttribute(objectName, "Count");
        return count.longValue();
    }

    /**
     * Collects maximum replica lag.
     * <p>
     * JMX MBean: {@code kafka.server:type=ReplicaFetcherManager,name=MaxLag,clientId=Replica}
     * <p>
     * Returns the maximum message lag across all follower replicas managed by this broker.
     * High lag (>1000 messages) indicates a follower is falling behind and may be dropped from ISR.
     *
     * @param mbsc MBean server connection
     * @return maximum replica lag in messages
     * @throws Exception if MBean query fails
     */
    private long collectReplicaMaxLag(MBeanServerConnection mbsc) throws Exception {
        try {
            ObjectName objectName = new ObjectName(
                "kafka.server:type=ReplicaFetcherManager,name=MaxLag,clientId=Replica"
            );

            Number lag = (Number) mbsc.getAttribute(objectName, "Value");
            return lag.longValue();
        } catch (Exception e) {
            // MaxLag may not be available if broker has no follower replicas
            logger.debug("MaxLag metric not available (broker may have no follower replicas): {}",
                e.getMessage());
            return 0L;
        }
    }

    /**
     * Collects minimum replica fetch rate.
     * <p>
     * JMX MBean: {@code kafka.server:type=ReplicaFetcherManager,name=MinFetchRate,clientId=Replica}
     * <p>
     * Returns the minimum fetch rate (messages/sec) across all follower replicas.
     * Low fetch rate (<1.0) indicates replication is slowing down, possibly due to
     * network issues or follower performance problems.
     *
     * @param mbsc MBean server connection
     * @return minimum replica fetch rate in messages/sec
     * @throws Exception if MBean query fails
     */
    private double collectReplicaMinFetchRate(MBeanServerConnection mbsc) throws Exception {
        try {
            ObjectName objectName = new ObjectName(
                "kafka.server:type=ReplicaFetcherManager,name=MinFetchRate,clientId=Replica"
            );

            Number rate = (Number) mbsc.getAttribute(objectName, "Value");
            return rate.doubleValue();
        } catch (Exception e) {
            // MinFetchRate may not be available if broker has no follower replicas
            logger.debug("MinFetchRate metric not available (broker may have no follower replicas): {}",
                e.getMessage());
            return 0.0;
        }
    }
}
