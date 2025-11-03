package io.fullerstack.kafka.msk;

/**
 * MSK broker endpoint information.
 *
 * This is fed to Epic 1 for monitoring using RC1 Serventis APIs.
 */
public record BrokerEndpoint(
    String brokerId,
    String brokerArn,
    String endpoint,  // broker-1.cluster.kafka.us-east-1.amazonaws.com:9092
    int jmxPort
) {}
