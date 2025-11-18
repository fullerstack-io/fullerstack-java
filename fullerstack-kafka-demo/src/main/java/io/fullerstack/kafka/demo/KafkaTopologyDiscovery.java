package io.fullerstack.kafka.demo;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.common.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Discovers Kafka cluster topology using AdminClient API.
 * <p>
 * Auto-discovers:
 * - Brokers (IDs and endpoints)
 * - Topics (names)
 * - Controller broker
 * <p>
 * This eliminates the need to hardcode broker/topic names in the demo.
 */
public class KafkaTopologyDiscovery implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(KafkaTopologyDiscovery.class);

    private final AdminClient adminClient;

    public KafkaTopologyDiscovery(String bootstrapServers) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "15000");

        this.adminClient = AdminClient.create(props);
        logger.info("Created AdminClient for topology discovery: {}", bootstrapServers);
    }

    /**
     * Discovers all brokers in the cluster.
     *
     * @return collection of broker nodes
     * @throws Exception if discovery fails
     */
    public Collection<Node> discoverBrokers() throws Exception {
        logger.info("Discovering brokers...");
        DescribeClusterResult result = adminClient.describeCluster();
        Collection<Node> nodes = result.nodes().get(10, TimeUnit.SECONDS);

        logger.info("Discovered {} brokers:", nodes.size());
        for (Node node : nodes) {
            logger.info("  - Broker {} at {}:{}", node.id(), node.host(), node.port());
        }

        return nodes;
    }

    /**
     * Discovers all topics in the cluster.
     *
     * @return set of topic names
     * @throws Exception if discovery fails
     */
    public Set<String> discoverTopics() throws Exception {
        logger.info("Discovering topics...");
        ListTopicsResult result = adminClient.listTopics();
        Set<String> topics = result.names().get(10, TimeUnit.SECONDS);

        logger.info("Discovered {} topics:", topics.size());
        for (String topic : topics) {
            // Skip internal topics
            if (!topic.startsWith("__")) {
                logger.info("  - {}", topic);
            }
        }

        return topics;
    }

    /**
     * Discovers the controller broker.
     *
     * @return controller broker node
     * @throws Exception if discovery fails
     */
    public Node discoverController() throws Exception {
        logger.info("Discovering controller broker...");
        DescribeClusterResult result = adminClient.describeCluster();
        Node controller = result.controller().get(10, TimeUnit.SECONDS);

        logger.info("Controller: Broker {} at {}:{}",
            controller.id(), controller.host(), controller.port());

        return controller;
    }

    /**
     * Discovers cluster ID.
     *
     * @return cluster ID string
     * @throws Exception if discovery fails
     */
    public String discoverClusterId() throws Exception {
        DescribeClusterResult result = adminClient.describeCluster();
        return result.clusterId().get(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        if (adminClient != null) {
            adminClient.close();
            logger.info("Closed AdminClient");
        }
    }
}
