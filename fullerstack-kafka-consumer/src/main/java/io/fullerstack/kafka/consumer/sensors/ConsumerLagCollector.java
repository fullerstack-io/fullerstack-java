package io.fullerstack.kafka.consumer.sensors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Collects consumer lag using Kafka AdminClient API.
 * <p>
 * <b>Important</b>: Consumer lag is NOT available via JMX for external monitoring.
 * Must use AdminClient to query committed offsets vs. end offsets.
 * <p>
 * <b>Calculation</b>: lag = endOffset - committedOffset
 * <p>
 * This collector requires DESCRIBE permission on consumer groups.
 */
public class ConsumerLagCollector implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerLagCollector.class);
    private static final int REQUEST_TIMEOUT_MS = 5000;

    private final AdminClient adminClient;

    /**
     * Create ConsumerLagCollector with AdminClient.
     *
     * @param bootstrapServers Kafka bootstrap servers
     */
    public ConsumerLagCollector(String bootstrapServers) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, REQUEST_TIMEOUT_MS);

        this.adminClient = AdminClient.create(props);
        logger.debug("ConsumerLagCollector initialized with bootstrap: {}", bootstrapServers);
    }

    /**
     * Collect lag for a consumer group.
     * <p>
     * Queries committed offsets and end offsets, then calculates lag per partition.
     *
     * @param consumerGroup Consumer group ID
     * @return Map of partition to lag (messages behind)
     * @throws Exception if AdminClient operations fail or timeout
     */
    public Map<TopicPartition, Long> collectLag(String consumerGroup) throws Exception {
        logger.debug("Collecting lag for consumer group: {}", consumerGroup);

        try {
            // 1. Get consumer group committed offsets
            Map<TopicPartition, OffsetAndMetadata> committedOffsets =
                    adminClient.listConsumerGroupOffsets(consumerGroup)
                            .partitionsToOffsetAndMetadata()
                            .get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (committedOffsets.isEmpty()) {
                logger.debug("Consumer group {} has no committed offsets (new group or no consumption)",
                        consumerGroup);
                return Map.of();
            }

            // 2. Get end offsets (latest available) for all partitions
            Map<TopicPartition, OffsetSpec> partitionsToQuery = new HashMap<>();
            for (TopicPartition tp : committedOffsets.keySet()) {
                partitionsToQuery.put(tp, OffsetSpec.latest());
            }

            ListOffsetsResult listOffsetsResult = adminClient.listOffsets(partitionsToQuery);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsetInfos =
                    listOffsetsResult.all().get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            // 3. Calculate lag: endOffset - committedOffset
            Map<TopicPartition, Long> lag = new HashMap<>();
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committedOffsets.entrySet()) {
                TopicPartition tp = entry.getKey();
                long committed = entry.getValue().offset();

                ListOffsetsResult.ListOffsetsResultInfo endOffsetInfo = endOffsetInfos.get(tp);
                if (endOffsetInfo == null) {
                    logger.warn("No end offset found for partition: {}, skipping", tp);
                    continue;
                }

                long endOffset = endOffsetInfo.offset();
                long partitionLag = Math.max(0, endOffset - committed);

                lag.put(tp, partitionLag);
                logger.trace("Partition {} lag: {} (end: {}, committed: {})",
                        tp, partitionLag, endOffset, committed);
            }

            logger.debug("Collected lag for {} partitions in group {}", lag.size(), consumerGroup);
            return lag;

        } catch (Exception e) {
            logger.error("Failed to collect lag for consumer group: {}", consumerGroup, e);
            throw e;
        }
    }

    @Override
    public void close() {
        logger.debug("Closing ConsumerLagCollector");
        adminClient.close(Duration.ofSeconds(5));
    }
}
