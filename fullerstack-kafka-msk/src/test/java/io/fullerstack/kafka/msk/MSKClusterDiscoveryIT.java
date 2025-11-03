package io.fullerstack.kafka.msk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kafka.KafkaClient;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for MSKClusterDiscovery with real MSK cluster.
 *
 * These tests only run when AWS_MSK_CLUSTER_ARN environment variable is set.
 * Intended for CI/CD environments with real MSK access.
 */
class MSKClusterDiscoveryIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "AWS_MSK_CLUSTER_ARN", matches = "arn:.*")
    void shouldDiscoverRealMSKCluster() {
        // Given: Real MSK cluster ARN from environment
        String clusterArn = System.getenv("AWS_MSK_CLUSTER_ARN");
        String region = extractRegionFromArn(clusterArn);

        // Create real Kafka client
        KafkaClient realClient = KafkaClient.builder()
            .region(Region.of(region))
            .build();

        MSKClusterDiscovery discovery = new MSKClusterDiscovery(realClient);

        try {
            // When: Discover real cluster
            ClusterMetadata metadata = discovery.discover(clusterArn);

            // Then: Metadata should be populated
            assertThat(metadata).isNotNull();
            assertThat(metadata.clusterArn()).isEqualTo(clusterArn);
            assertThat(metadata.clusterName()).isNotBlank();
            assertThat(metadata.kafkaVersion()).isNotBlank();
            assertThat(metadata.state()).isNotBlank();

            // Brokers should be discovered
            assertThat(metadata.brokers()).isNotEmpty();

            // Each broker should have valid endpoint
            metadata.brokers().forEach(broker -> {
                assertThat(broker.brokerId()).isNotBlank();
                assertThat(broker.brokerArn()).isNotBlank();
                assertThat(broker.endpoint()).isNotBlank();
                assertThat(broker.endpoint()).contains(".amazonaws.com");
                assertThat(broker.jmxPort()).isEqualTo(11001);
            });

            // Configuration should be present (defaults for now, Story 5.4 will enhance)
            assertThat(metadata.configuration()).isNotNull();

            System.out.println("Successfully discovered MSK cluster:");
            System.out.println("  Name: " + metadata.clusterName());
            System.out.println("  Version: " + metadata.kafkaVersion());
            System.out.println("  State: " + metadata.state());
            System.out.println("  Brokers: " + metadata.brokers().size());

        } finally {
            discovery.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AWS_MSK_CLUSTER_ARN", matches = "arn:.*")
    void shouldDiscoverActiveBrokers() {
        // Given: Real MSK cluster
        String clusterArn = System.getenv("AWS_MSK_CLUSTER_ARN");
        String region = extractRegionFromArn(clusterArn);

        KafkaClient realClient = KafkaClient.builder()
            .region(Region.of(region))
            .build();

        MSKClusterDiscovery discovery = new MSKClusterDiscovery(realClient);

        try {
            // When: Discover cluster
            ClusterMetadata metadata = discovery.discover(clusterArn);

            // Then: If cluster is ACTIVE, brokers should be present
            if (metadata.isActive()) {
                assertThat(metadata.brokers())
                    .isNotEmpty()
                    .allMatch(broker -> broker.endpoint() != null && !broker.endpoint().isEmpty());
            }

        } finally {
            discovery.close();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "AWS_MSK_CLUSTER_ARN", matches = "arn:.*")
    void shouldHandleInvalidClusterArn() {
        // Given: Invalid cluster ARN
        String invalidArn = "arn:aws:kafka:us-east-1:123456789:cluster/nonexistent/uuid-invalid";
        String region = "us-east-1";

        KafkaClient realClient = KafkaClient.builder()
            .region(Region.of(region))
            .build();

        MSKClusterDiscovery discovery = new MSKClusterDiscovery(realClient);

        try {
            // When/Then: Should throw MSKDiscoveryException for nonexistent cluster
            assertThatThrownBy(() -> discovery.discover(invalidArn))
                .isInstanceOf(MSKDiscoveryException.class);

        } finally {
            discovery.close();
        }
    }

    /**
     * Extract AWS region from MSK cluster ARN.
     *
     * ARN format: arn:aws:kafka:REGION:account:cluster/name/uuid
     */
    private String extractRegionFromArn(String arn) {
        String[] parts = arn.split(":");
        if (parts.length >= 4) {
            return parts[3];  // Region is 4th component
        }
        return "us-east-1";  // Default fallback
    }
}
