package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.PartitionMetrics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Collects partition state metrics from Kafka brokers via JMX and AdminClient.
 *
 * <p>This collector combines two data sources:
 * <ul>
 *   <li><b>JMX</b> - Partition size and log offsets from broker MBeans</li>
 *   <li><b>AdminClient</b> - ISR count and leader epoch from cluster metadata</li>
 * </ul>
 *
 * <h3>JMX Metrics</h3>
 * <ul>
 *   <li><b>Partition Size</b> - {@code kafka.log:type=Log,name=Size,topic=*,partition=*}</li>
 *   <li><b>Log End Offset</b> - {@code kafka.log:type=Log,name=LogEndOffset,topic=*,partition=*}</li>
 * </ul>
 *
 * <h3>AdminClient Metadata</h3>
 * <ul>
 *   <li><b>ISR Count</b> - Number of in-sync replicas from {@code describeTopics()}</li>
 *   <li><b>Replication Factor</b> - Configured replica count from {@code describeTopics()}</li>
 *   <li><b>Leader Epoch</b> - Current leader epoch (future enhancement)</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * JmxConnectionPool pool = new JmxConnectionPool();
 * AdminClient admin = AdminClient.create(props);
 * PartitionMetricsCollector collector = new PartitionMetricsCollector(
 *     "service:jmx:rmi:///jndi/rmi://broker:11001/jmxrmi",
 *     pool,
 *     admin
 * );
 *
 * List<PartitionMetrics> metrics = collector.collect("broker-1", Set.of("orders", "payments"));
 * for (PartitionMetrics m : metrics) {
 *     if (m.isUnderReplicated()) {
 *         alert("Under-replicated: " + m.partitionIdentifier());
 *     }
 * }
 * }</pre>
 *
 * @see PartitionMetrics
 * @see JmxConnectionPool
 */
public class PartitionMetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(PartitionMetricsCollector.class);

    private final String jmxUrl;
    private final JmxConnectionPool connectionPool;
    private final AdminClient adminClient;

    /**
     * Creates a new partition metrics collector.
     *
     * @param jmxUrl         JMX service URL for the broker
     * @param connectionPool connection pool for JMX connection reuse
     * @param adminClient    Kafka AdminClient for cluster metadata
     * @throws NullPointerException if any parameter is null
     */
    public PartitionMetricsCollector(
        String jmxUrl,
        JmxConnectionPool connectionPool,
        AdminClient adminClient
    ) {
        this.jmxUrl = Objects.requireNonNull(jmxUrl, "jmxUrl required");
        this.connectionPool = Objects.requireNonNull(connectionPool, "connectionPool required");
        this.adminClient = Objects.requireNonNull(adminClient, "adminClient required");
    }

    /**
     * Collects partition metrics for specified topics.
     * <p>
     * Queries both JMX (for size/offset) and AdminClient (for ISR/replication factor).
     * Returns one PartitionMetrics per partition across all specified topics.
     *
     * @param brokerId   broker identifier (e.g., "broker-1", "0")
     * @param topicNames set of topic names to collect metrics for
     * @return list of partition metrics (one per partition)
     * @throws Exception if JMX connection, AdminClient query, or MBean query fails
     */
    public List<PartitionMetrics> collect(String brokerId, Set<String> topicNames) throws Exception {
        if (topicNames.isEmpty()) {
            return Collections.emptyList();
        }

        // Step 1: Query AdminClient for ISR/replication metadata
        Map<String, TopicDescription> topicDescriptions = fetchTopicDescriptions(topicNames);

        // Step 2: Query JMX for partition sizes and offsets
        JMXConnector connector = connectionPool.getConnection(jmxUrl);
        MBeanServerConnection mbsc = connector.getMBeanServerConnection();

        try {
            List<PartitionMetrics> metrics = new ArrayList<>();

            for (Map.Entry<String, TopicDescription> entry : topicDescriptions.entrySet()) {
                String topic = entry.getKey();
                TopicDescription desc = entry.getValue();

                for (TopicPartitionInfo partition : desc.partitions()) {
                    try {
                        PartitionMetrics pm = collectPartitionMetrics(
                            brokerId,
                            topic,
                            partition,
                            mbsc
                        );
                        if (pm != null) {
                            metrics.add(pm);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to collect metrics for {}-{}: {}",
                            topic, partition.partition(), e.getMessage());
                    }
                }
            }

            return metrics;
        } finally {
            connectionPool.releaseConnection(jmxUrl);
        }
    }

    /**
     * Fetches topic descriptions from AdminClient.
     *
     * @param topicNames topic names to describe
     * @return map of topic name to TopicDescription
     * @throws Exception if AdminClient query fails
     */
    private Map<String, TopicDescription> fetchTopicDescriptions(Set<String> topicNames) throws Exception {
        try {
            DescribeTopicsResult result = adminClient.describeTopics(topicNames);
            return result.allTopicNames().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Failed to fetch topic descriptions: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Collects metrics for a single partition.
     *
     * @param brokerId  broker identifier
     * @param topic     topic name
     * @param partition partition info from AdminClient
     * @param mbsc      MBean server connection
     * @return partition metrics, or null if JMX data unavailable
     */
    private PartitionMetrics collectPartitionMetrics(
        String brokerId,
        String topic,
        TopicPartitionInfo partition,
        MBeanServerConnection mbsc
    ) {
        try {
            int partitionId = partition.partition();

            // Collect JMX metrics
            long sizeBytes = collectPartitionSize(mbsc, topic, partitionId);
            long logEndOffset = collectLogEndOffset(mbsc, topic, partitionId);

            // Extract AdminClient metadata
            int isrCount = partition.isr().size();
            int replicationFactor = partition.replicas().size();
            int leaderEpoch = 0; // TODO: Kafka AdminClient doesn't expose leader epoch directly
                                  // Would need to use KafkaConsumer.partitionsFor() or custom logic

            return new PartitionMetrics(
                brokerId,
                topic,
                partitionId,
                sizeBytes,
                logEndOffset,
                isrCount,
                replicationFactor,
                leaderEpoch,
                System.currentTimeMillis()
            );
        } catch (Exception e) {
            logger.warn("Failed to collect metrics for {}-{}: {}",
                topic, partition.partition(), e.getMessage());
            return null;
        }
    }

    /**
     * Collects partition size from JMX.
     *
     * @param mbsc        MBean server connection
     * @param topic       topic name
     * @param partitionId partition ID
     * @return partition size in bytes, or 0 if unavailable
     */
    private long collectPartitionSize(MBeanServerConnection mbsc, String topic, int partitionId) {
        try {
            ObjectName name = new ObjectName(String.format(
                "kafka.log:type=Log,name=Size,topic=%s,partition=%d",
                topic, partitionId
            ));
            if (!mbsc.isRegistered(name)) {
                logger.debug("Partition size MBean not found: {}-{}", topic, partitionId);
                return 0L;
            }
            Object value = mbsc.getAttribute(name, "Value");
            return value != null ? ((Number) value).longValue() : 0L;
        } catch (Exception e) {
            logger.debug("Failed to collect size for {}-{}: {}", topic, partitionId, e.getMessage());
            return 0L;
        }
    }

    /**
     * Collects log end offset from JMX.
     *
     * @param mbsc        MBean server connection
     * @param topic       topic name
     * @param partitionId partition ID
     * @return log end offset, or 0 if unavailable
     */
    private long collectLogEndOffset(MBeanServerConnection mbsc, String topic, int partitionId) {
        try {
            ObjectName name = new ObjectName(String.format(
                "kafka.log:type=Log,name=LogEndOffset,topic=%s,partition=%d",
                topic, partitionId
            ));
            if (!mbsc.isRegistered(name)) {
                logger.debug("LogEndOffset MBean not found: {}-{}", topic, partitionId);
                return 0L;
            }
            Object value = mbsc.getAttribute(name, "Value");
            return value != null ? ((Number) value).longValue() : 0L;
        } catch (Exception e) {
            logger.debug("Failed to collect offset for {}-{}: {}", topic, partitionId, e.getMessage());
            return 0L;
        }
    }
}
