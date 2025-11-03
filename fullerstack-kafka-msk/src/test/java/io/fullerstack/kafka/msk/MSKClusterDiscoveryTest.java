package io.fullerstack.kafka.msk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.kafka.KafkaClient;
import software.amazon.awssdk.services.kafka.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MSKClusterDiscovery with mocked AWS SDK.
 */
@ExtendWith(MockitoExtension.class)
class MSKClusterDiscoveryTest {

    @Mock
    private KafkaClient mockClient;

    private MSKClusterDiscovery discovery;

    private static final String TEST_CLUSTER_ARN = "arn:aws:kafka:us-east-1:123456789:cluster/test-cluster/uuid-1234";
    private static final String TEST_CLUSTER_NAME = "test-cluster";
    private static final String TEST_KAFKA_VERSION = "3.5.1";

    @BeforeEach
    void setUp() {
        discovery = new MSKClusterDiscovery(mockClient);
    }

    @Test
    void shouldDiscoverClusterMetadata() {
        // Given: Mock DescribeCluster response
        when(mockClient.describeCluster(any(DescribeClusterRequest.class)))
            .thenReturn(DescribeClusterResponse.builder()
                .clusterInfo(ClusterInfo.builder()
                    .clusterName(TEST_CLUSTER_NAME)
                    .clusterArn(TEST_CLUSTER_ARN)
                    .state(ClusterState.ACTIVE)
                    .currentBrokerSoftwareInfo(BrokerSoftwareInfo.builder()
                        .kafkaVersion(TEST_KAFKA_VERSION)
                        .build())
                    .build())
                .build());

        // Mock ListNodes response with one broker
        when(mockClient.listNodes(any(ListNodesRequest.class)))
            .thenReturn(ListNodesResponse.builder()
                .nodeInfoList(
                    NodeInfo.builder()
                        .nodeARN("arn:aws:kafka:us-east-1:123:broker/test-cluster/uuid/broker-1")
                        .brokerNodeInfo(BrokerNodeInfo.builder()
                            .endpoints(List.of("b-1.test-cluster.kafka.us-east-1.amazonaws.com:9092"))
                            .build())
                        .build()
                )
                .build());

        // When: Discover cluster
        ClusterMetadata metadata = discovery.discover(TEST_CLUSTER_ARN);

        // Then: Metadata is correct
        assertThat(metadata.clusterName()).isEqualTo(TEST_CLUSTER_NAME);
        assertThat(metadata.clusterArn()).isEqualTo(TEST_CLUSTER_ARN);
        assertThat(metadata.kafkaVersion()).isEqualTo(TEST_KAFKA_VERSION);
        assertThat(metadata.state()).isEqualTo("ACTIVE");
        assertThat(metadata.isActive()).isTrue();
        assertThat(metadata.brokers()).hasSize(1);

        verify(mockClient).describeCluster(any(DescribeClusterRequest.class));
        verify(mockClient).listNodes(any(ListNodesRequest.class));
    }

    @Test
    void shouldDiscoverBrokerEndpoints() {
        // Given: Mock DescribeCluster response
        when(mockClient.describeCluster(any(DescribeClusterRequest.class)))
            .thenReturn(DescribeClusterResponse.builder()
                .clusterInfo(ClusterInfo.builder()
                    .clusterName(TEST_CLUSTER_NAME)
                    .clusterArn(TEST_CLUSTER_ARN)
                    .state(ClusterState.ACTIVE)
                    .currentBrokerSoftwareInfo(BrokerSoftwareInfo.builder()
                        .kafkaVersion(TEST_KAFKA_VERSION)
                        .build())
                    .build())
                .build());

        // Mock ListNodes response with 3 brokers
        when(mockClient.listNodes(any(ListNodesRequest.class)))
            .thenReturn(ListNodesResponse.builder()
                .nodeInfoList(
                    NodeInfo.builder()
                        .nodeARN("arn:aws:kafka:us-east-1:123:broker/test-cluster/uuid/broker-1")
                        .brokerNodeInfo(BrokerNodeInfo.builder()
                            .endpoints(List.of("b-1.test-cluster.kafka.us-east-1.amazonaws.com:9092"))
                            .build())
                        .build(),
                    NodeInfo.builder()
                        .nodeARN("arn:aws:kafka:us-east-1:123:broker/test-cluster/uuid/broker-2")
                        .brokerNodeInfo(BrokerNodeInfo.builder()
                            .endpoints(List.of("b-2.test-cluster.kafka.us-east-1.amazonaws.com:9092"))
                            .build())
                        .build(),
                    NodeInfo.builder()
                        .nodeARN("arn:aws:kafka:us-east-1:123:broker/test-cluster/uuid/broker-3")
                        .brokerNodeInfo(BrokerNodeInfo.builder()
                            .endpoints(List.of("b-3.test-cluster.kafka.us-east-1.amazonaws.com:9092"))
                            .build())
                        .build()
                )
                .build());

        // When: Discover cluster
        ClusterMetadata metadata = discovery.discover(TEST_CLUSTER_ARN);

        // Then: 3 brokers discovered
        assertThat(metadata.brokers()).hasSize(3);

        BrokerEndpoint broker1 = metadata.brokers().get(0);
        assertThat(broker1.brokerId()).isEqualTo("broker-1");
        assertThat(broker1.endpoint()).isEqualTo("b-1.test-cluster.kafka.us-east-1.amazonaws.com:9092");
        assertThat(broker1.jmxPort()).isEqualTo(11001);

        BrokerEndpoint broker2 = metadata.brokers().get(1);
        assertThat(broker2.brokerId()).isEqualTo("broker-2");

        BrokerEndpoint broker3 = metadata.brokers().get(2);
        assertThat(broker3.brokerId()).isEqualTo("broker-3");
    }

    @Test
    void shouldHandleClusterNotFound() {
        // Given: Mock NotFoundException
        when(mockClient.describeCluster(any(DescribeClusterRequest.class)))
            .thenThrow(NotFoundException.builder()
                .message("Cluster not found")
                .build());

        // When/Then: Should throw MSKDiscoveryException
        assertThatThrownBy(() -> discovery.discover(TEST_CLUSTER_ARN))
            .isInstanceOf(MSKDiscoveryException.class)
            .hasMessageContaining("Cluster not found");

        verify(mockClient).describeCluster(any(DescribeClusterRequest.class));
    }

    @Test
    void shouldHandleThrottling() {
        // Given: Mock TooManyRequestsException
        when(mockClient.describeCluster(any(DescribeClusterRequest.class)))
            .thenThrow(TooManyRequestsException.builder()
                .message("Rate limit exceeded")
                .build());

        // When/Then: Should throw MSKDiscoveryException with throttling message
        assertThatThrownBy(() -> discovery.discover(TEST_CLUSTER_ARN))
            .isInstanceOf(MSKDiscoveryException.class)
            .hasMessageContaining("throttled");

        verify(mockClient).describeCluster(any(DescribeClusterRequest.class));
    }

    @Test
    void shouldHandleInvalidBrokerArn() {
        // Given: Mock DescribeCluster response
        when(mockClient.describeCluster(any(DescribeClusterRequest.class)))
            .thenReturn(DescribeClusterResponse.builder()
                .clusterInfo(ClusterInfo.builder()
                    .clusterName(TEST_CLUSTER_NAME)
                    .clusterArn(TEST_CLUSTER_ARN)
                    .state(ClusterState.ACTIVE)
                    .currentBrokerSoftwareInfo(BrokerSoftwareInfo.builder()
                        .kafkaVersion(TEST_KAFKA_VERSION)
                        .build())
                    .build())
                .build());

        // Mock ListNodes response with invalid ARN (null)
        when(mockClient.listNodes(any(ListNodesRequest.class)))
            .thenReturn(ListNodesResponse.builder()
                .nodeInfoList(
                    NodeInfo.builder()
                        .nodeARN(null)  // Invalid
                        .brokerNodeInfo(BrokerNodeInfo.builder()
                            .endpoints(List.of("b-1.test-cluster.kafka.us-east-1.amazonaws.com:9092"))
                            .build())
                        .build()
                )
                .build());

        // When/Then: Should throw MSKDiscoveryException with root cause about invalid ARN
        assertThatThrownBy(() -> discovery.discover(TEST_CLUSTER_ARN))
            .isInstanceOf(MSKDiscoveryException.class)
            .hasMessageContaining("Failed to discover")
            .hasCauseInstanceOf(MSKDiscoveryException.class);
    }

    @Test
    void shouldHandleMissingBrokerEndpoint() {
        // Given: Mock DescribeCluster response
        when(mockClient.describeCluster(any(DescribeClusterRequest.class)))
            .thenReturn(DescribeClusterResponse.builder()
                .clusterInfo(ClusterInfo.builder()
                    .clusterName(TEST_CLUSTER_NAME)
                    .clusterArn(TEST_CLUSTER_ARN)
                    .state(ClusterState.ACTIVE)
                    .currentBrokerSoftwareInfo(BrokerSoftwareInfo.builder()
                        .kafkaVersion(TEST_KAFKA_VERSION)
                        .build())
                    .build())
                .build());

        // Mock ListNodes response with no endpoints
        when(mockClient.listNodes(any(ListNodesRequest.class)))
            .thenReturn(ListNodesResponse.builder()
                .nodeInfoList(
                    NodeInfo.builder()
                        .nodeARN("arn:aws:kafka:us-east-1:123:broker/test-cluster/uuid/broker-1")
                        .brokerNodeInfo(BrokerNodeInfo.builder()
                            .endpoints(List.of())  // Empty endpoints
                            .build())
                        .build()
                )
                .build());

        // When/Then: Should throw MSKDiscoveryException with root cause
        assertThatThrownBy(() -> discovery.discover(TEST_CLUSTER_ARN))
            .isInstanceOf(MSKDiscoveryException.class)
            .hasMessageContaining("Failed to discover")
            .hasCauseInstanceOf(MSKDiscoveryException.class);
    }

    @Test
    void shouldHandleNullBrokerSoftwareInfo() {
        // Given: Mock DescribeCluster response with null software info
        when(mockClient.describeCluster(any(DescribeClusterRequest.class)))
            .thenReturn(DescribeClusterResponse.builder()
                .clusterInfo(ClusterInfo.builder()
                    .clusterName(TEST_CLUSTER_NAME)
                    .clusterArn(TEST_CLUSTER_ARN)
                    .state(ClusterState.ACTIVE)
                    // Don't set currentBrokerSoftwareInfo - leave it null
                    .build())
                .build());

        // Mock ListNodes response
        when(mockClient.listNodes(any(ListNodesRequest.class)))
            .thenReturn(ListNodesResponse.builder()
                .nodeInfoList(List.of())
                .build());

        // When: Discover cluster
        ClusterMetadata metadata = discovery.discover(TEST_CLUSTER_ARN);

        // Then: Should handle gracefully with "unknown" version
        assertThat(metadata.kafkaVersion()).isEqualTo("unknown");
        assertThat(metadata.configuration()).isEqualTo(ClusterConfiguration.defaults());
    }

    @Test
    void shouldCloseClientOnClose() {
        // When: Close discovery
        discovery.close();

        // Then: KafkaClient should be closed
        verify(mockClient).close();
    }

    @Test
    void shouldReturnDefaultConfiguration() {
        // Given: Mock DescribeCluster response
        when(mockClient.describeCluster(any(DescribeClusterRequest.class)))
            .thenReturn(DescribeClusterResponse.builder()
                .clusterInfo(ClusterInfo.builder()
                    .clusterName(TEST_CLUSTER_NAME)
                    .clusterArn(TEST_CLUSTER_ARN)
                    .state(ClusterState.ACTIVE)
                    .currentBrokerSoftwareInfo(BrokerSoftwareInfo.builder()
                        .kafkaVersion(TEST_KAFKA_VERSION)
                        .build())
                    .build())
                .build());

        // Mock ListNodes response with one broker
        when(mockClient.listNodes(any(ListNodesRequest.class)))
            .thenReturn(ListNodesResponse.builder()
                .nodeInfoList(
                    NodeInfo.builder()
                        .nodeARN("arn:aws:kafka:us-east-1:123:broker/test-cluster/uuid/broker-1")
                        .brokerNodeInfo(BrokerNodeInfo.builder()
                            .endpoints(List.of("b-1.test-cluster.kafka.us-east-1.amazonaws.com:9092"))
                            .build())
                        .build()
                )
                .build());

        // When: Discover cluster
        ClusterMetadata metadata = discovery.discover(TEST_CLUSTER_ARN);

        // Then: Should return default configuration (Story 5.4 will implement actual config discovery)
        assertThat(metadata.configuration()).isEqualTo(ClusterConfiguration.defaults());
        assertThat(metadata.configuration().segmentBytes()).isEqualTo(1073741824L);
        assertThat(metadata.configuration().bufferMemory()).isEqualTo(33554432L);
        assertThat(metadata.configuration().numPartitions()).isEqualTo(1);
    }
}
