package io.fullerstack.kafka.msk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.kafka.KafkaClient;
import software.amazon.awssdk.services.kafka.model.*;

import java.util.List;

/**
 * Discovers AWS MSK cluster metadata and broker endpoints.
 *
 * This is INFRASTRUCTURE discovery, NOT observability.
 * Discovered entities are monitored by Epic 1 using RC1 Serventis APIs.
 */
public class MSKClusterDiscovery implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(MSKClusterDiscovery.class);
  private static final int DEFAULT_JMX_PORT = 11001; // MSK default JMX port

  private final KafkaClient mskClient;
  private final MSKConfigurationDiscovery configDiscovery;

  public MSKClusterDiscovery(KafkaClient mskClient) {
    this.mskClient = mskClient;
    this.configDiscovery = new MSKConfigurationDiscovery(mskClient);
  }

  
  /**
   * Discover cluster metadata from MSK API.
   *
   * @param clusterArn MSK cluster ARN
   * @return Cluster metadata including brokers
   * @throws NotFoundException        if cluster doesn't exist
   * @throws TooManyRequestsException if API throttled
   */
  public ClusterMetadata discover(String clusterArn) {
    try {
      DescribeClusterResponse response = mskClient.describeCluster(
          DescribeClusterRequest.builder()
              .clusterArn(clusterArn)
              .build());

      ClusterInfo info = response.clusterInfo();

      String kafkaVersion = info.currentBrokerSoftwareInfo() != null
          ? info.currentBrokerSoftwareInfo().kafkaVersion()
          : "unknown";

      String configArn = info.currentBrokerSoftwareInfo() != null
          ? info.currentBrokerSoftwareInfo().configurationArn()
          : null;

      return new ClusterMetadata(
          clusterArn,
          info.clusterName(),
          kafkaVersion,
          info.stateAsString(),
          discoverBrokers(clusterArn),
          discoverConfiguration(configArn));

    } catch (NotFoundException e) {
      logger.error("Cluster not found: {}", clusterArn, e);
      throw new MSKDiscoveryException("Cluster not found: " + clusterArn, e);
    } catch (TooManyRequestsException e) {
      logger.warn("AWS API throttled for cluster: {}", clusterArn, e);
      throw new MSKDiscoveryException("AWS API throttled, retry with backoff", e);
    } catch (Exception e) {
      logger.error("Failed to discover cluster: {}", clusterArn, e);
      throw new MSKDiscoveryException("Failed to discover cluster: " + clusterArn, e);
    }
  }

  /**
   * Discover broker endpoints from MSK ListNodes API.
   *
   * @param clusterArn MSK cluster ARN
   * @return List of broker endpoints with JMX ports
   */
  private List<BrokerEndpoint> discoverBrokers(String clusterArn) {
    try {
      ListNodesResponse nodes = mskClient.listNodes(
          ListNodesRequest.builder()
              .clusterArn(clusterArn)
              .build());

      return nodes.nodeInfoList().stream()
          .map(node -> new BrokerEndpoint(
              extractBrokerId(node.nodeARN()),
              node.nodeARN(),
              extractEndpoint(node.brokerNodeInfo()),
              DEFAULT_JMX_PORT))
          .toList();

    } catch (Exception e) {
      logger.error("Failed to discover brokers for cluster: {}", clusterArn, e);
      throw new MSKDiscoveryException("Failed to discover brokers: " + clusterArn, e);
    }
  }

  private String extractBrokerId(String nodeArn) {
    // Extract broker ID from ARN:
    // arn:aws:kafka:region:account:broker/cluster-uuid/broker-id
    if (nodeArn == null || nodeArn.isEmpty()) {
      throw new MSKDiscoveryException("Invalid node ARN: null or empty");
    }

    String[] parts = nodeArn.split("/");
    if (parts.length < 1) {
      throw new MSKDiscoveryException("Invalid node ARN format: " + nodeArn);
    }

    return parts[parts.length - 1];
  }

  private String extractEndpoint(BrokerNodeInfo brokerInfo) {
    // Get broker endpoint from MSK (prefer TLS endpoint)
    if (brokerInfo == null) {
      throw new MSKDiscoveryException("BrokerNodeInfo is null");
    }

    if (brokerInfo.endpoints() != null && !brokerInfo.endpoints().isEmpty()) {
      return brokerInfo.endpoints().percept(0); // TLS endpoint
    }

    throw new MSKDiscoveryException("No broker endpoint found");
  }

  private ClusterConfiguration discoverConfiguration(String configArn) {
    // Implemented in Story 5.4 - Configuration-Aware Thresholds
    return configDiscovery.discoverConfiguration(configArn);
  }

  @Override
  public void close() {
    if (mskClient != null) {
      mskClient.close();
      logger.info("MSKClusterDiscovery closed");
    }
  }
}
