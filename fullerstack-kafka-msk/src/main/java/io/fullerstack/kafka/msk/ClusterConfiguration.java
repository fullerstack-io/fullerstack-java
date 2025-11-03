package io.fullerstack.kafka.msk;

/**
 * MSK cluster configuration values.
 *
 * This is fed to Epic 1 for monitoring using RC1 Serventis APIs.
 */
public record ClusterConfiguration(
    long segmentBytes,
    long bufferMemory,
    int numPartitions) {
  public static ClusterConfiguration defaults() {
    return new ClusterConfiguration(1073741824L, 33554432L, 1);
  }
}
