package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.ReplicationHealthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.Objects;

/**
 * Collects partition replication health metrics from Kafka brokers via JMX.
 *
 * <p>Kafka brokers expose cluster-wide replication health via JMX MBeans. This collector queries:
 * <ul>
 *   <li><b>Under-Replicated Partitions</b> - {@code kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions}</li>
 *   <li><b>Offline Partitions</b> - {@code kafka.controller:type=KafkaController,name=OfflinePartitionsCount}</li>
 *   <li><b>Active Controller Count</b> - {@code kafka.controller:type=KafkaController,name=ActiveControllerCount}</li>
 * </ul>
 *
 * <p><b>Under-Replicated Partitions</b>: Number of partitions with in-sync replicas less than
 * the configured minimum. This indicates replication lag or replica failures but data is still available.
 *
 * <p><b>Offline Partitions</b>: Number of partitions with no leader and no available replicas.
 * This is a critical condition indicating data unavailability.
 *
 * <p><b>Active Controller</b>: Kafka cluster should have exactly 1 active controller. If count is 0,
 * the cluster cannot perform leader elections or replication changes (split-brain condition).
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * JmxConnectionPool pool = new JmxConnectionPool();
 * ReplicationHealthMetricsCollector collector = new ReplicationHealthMetricsCollector(
 *     "service:jmx:rmi:///jndi/rmi://controller:11001/jmxrmi",
 *     pool
 * );
 *
 * ReplicationHealthMetrics metrics = collector.collect("cluster-1");
 * if (metrics.isDegraded()) {
 *     alert("Cluster health degraded: " + metrics.underReplicatedPartitions() + " partitions");
 * }
 * }</pre>
 *
 * @see ReplicationHealthMetrics
 * @see JmxConnectionPool
 */
public class ReplicationHealthMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(ReplicationHealthMetricsCollector.class);

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;

    /**
     * Creates a new replication health metrics collector.
     * <p>
     * <b>Note</b>: For cluster-wide metrics (offline partitions, active controller), connect
     * to the controller broker's JMX endpoint. Under-replicated partitions are available on all brokers.
     *
     * @param jmxUrl         JMX service URL for the broker (preferably controller)
     * @param connectionPool connection pool for JMX connection reuse
     * @throws NullPointerException if jmxUrl or connectionPool is null
     */
    public ReplicationHealthMetricsCollector(String jmxUrl, JmxConnectionPool connectionPool) {
        this.jmxUrl = Objects.requireNonNull(jmxUrl, "jmxUrl required");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool required");
    }

    /**
     * Collects replication health metrics from a broker.
     * <p>
     * Returns current partition counts and controller status.
     *
     * @param clusterId cluster identifier (e.g., "cluster-1", "prod-kafka")
     * @return replication health metrics
     * @throws Exception if JMX connection or MBean query fails
     */
    public ReplicationHealthMetrics collect(String clusterId) throws Exception {
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();

        try {
            // Collect all replication health metrics
            int underReplicatedPartitions = collectUnderReplicatedPartitions(mbsc);
            int offlinePartitions = collectOfflinePartitions(mbsc);
            int activeControllerCount = collectActiveControllerCount(mbsc);

            return new ReplicationHealthMetrics(
                clusterId,
                underReplicatedPartitions,
                offlinePartitions,
                activeControllerCount,
                System.currentTimeMillis()
            );
        } finally {
            connectionPool.releaseConnection(jmxUrl);
        }
    }

    /**
     * Collects under-replicated partition count.
     * <p>
     * JMX MBean: {@code kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions}
     * <p>
     * Returns the number of partitions where the number of in-sync replicas is less than
     * the configured replication factor. This indicates replication redundancy is reduced
     * but data is still available.
     *
     * @param mbsc MBean server connection
     * @return number of under-replicated partitions
     * @throws Exception if MBean query fails
     */
    private int collectUnderReplicatedPartitions(MBeanServerConnection mbsc) throws Exception {
        ObjectName objectName = new ObjectName(
            "kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions"
        );

        Number count = (Number) mbsc.getAttribute(objectName, "Value");
        return count.intValue();
    }

    /**
     * Collects offline partition count.
     * <p>
     * JMX MBean: {@code kafka.controller:type=KafkaController,name=OfflinePartitionsCount}
     * <p>
     * Returns the number of partitions that have no leader and no available replicas.
     * This is a critical condition indicating data unavailability. Offline partitions
     * cannot serve produce or fetch requests.
     * <p>
     * <b>Note</b>: This metric is only available on the active controller broker. If querying
     * a non-controller broker, this will return 0 (metric not registered).
     *
     * @param mbsc MBean server connection
     * @return number of offline partitions
     * @throws Exception if MBean query fails
     */
    private int collectOfflinePartitions(MBeanServerConnection mbsc) throws Exception {
        try {
            ObjectName objectName = new ObjectName(
                "kafka.controller:type=KafkaController,name=OfflinePartitionsCount"
            );

            // Check if MBean exists (only on controller broker)
            if (!mbsc.isRegistered(objectName)) {
                logger.debug("OfflinePartitionsCount metric not available (not the controller broker)");
                return 0;
            }

            Number count = (Number) mbsc.getAttribute(objectName, "Value");
            return count.intValue();
        } catch (Exception e) {
            logger.debug("Failed to collect offline partitions (not the controller broker?): {}",
                e.getMessage());
            return 0;
        }
    }

    /**
     * Collects active controller count.
     * <p>
     * JMX MBean: {@code kafka.controller:type=KafkaController,name=ActiveControllerCount}
     * <p>
     * Returns the number of active controllers. This should be exactly 1 in a healthy cluster.
     * <ul>
     *   <li><b>0</b> - No active controller (cluster cannot perform leader elections)</li>
     *   <li><b>1</b> - Healthy (expected)</li>
     *   <li><b>>1</b> - Split-brain condition (critical, should never happen)</li>
     * </ul>
     * <p>
     * <b>Note</b>: This metric returns 1 only on the active controller broker, 0 on all others.
     * Caller should query the controller broker or all brokers and sum the results.
     *
     * @param mbsc MBean server connection
     * @return active controller count (0 or 1)
     * @throws Exception if MBean query fails
     */
    private int collectActiveControllerCount(MBeanServerConnection mbsc) throws Exception {
        try {
            ObjectName objectName = new ObjectName(
                "kafka.controller:type=KafkaController,name=ActiveControllerCount"
            );

            Number count = (Number) mbsc.getAttribute(objectName, "Value");
            return count.intValue();
        } catch (Exception e) {
            logger.debug("Failed to collect active controller count: {}", e.getMessage());
            return 0;
        }
    }
}
