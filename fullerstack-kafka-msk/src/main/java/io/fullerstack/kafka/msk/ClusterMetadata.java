package io.fullerstack.kafka.msk;

import java.util.List;

/**
 * MSK cluster metadata discovered from AWS API.
 *
 * This is fed to Epic 1 for monitoring using RC1 Serventis APIs.
 */
public record ClusterMetadata(
    String clusterArn,
    String clusterName,
    String kafkaVersion,
    String state, // ACTIVE, CREATING, DELETING, etc.
    List<BrokerEndpoint> brokers,
    ClusterConfiguration configuration) {
  public boolean isActive() {
    return "ACTIVE".equals(state);
  }
}
