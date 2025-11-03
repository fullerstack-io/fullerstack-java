package io.fullerstack.kafka.msk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ProducerDiscovery.
 */
@ExtendWith(MockitoExtension.class)
class ProducerDiscoveryTest {

    @Mock
    private CloudWatchClient cloudWatch;

    private ProducerDiscovery discovery;

    @BeforeEach
    void setUp() {
        discovery = new ProducerDiscovery(cloudWatch);
    }

    @Test
    void shouldDiscoverProducersFromCloudWatch() {
        // Given: CloudWatch returns metrics for 2 producers
        ListMetricsResponse response = ListMetricsResponse.builder()
            .metrics(
                Metric.builder()
                    .dimensions(
                        Dimension.builder().name("Cluster Name").value("test-cluster").build(),
                        Dimension.builder().name("Client ID").value("producer-1").build()
                    )
                    .build(),
                Metric.builder()
                    .dimensions(
                        Dimension.builder().name("Cluster Name").value("test-cluster").build(),
                        Dimension.builder().name("Client ID").value("producer-2").build()
                    )
                    .build()
            )
            .build();

        when(cloudWatch.listMetrics((ListMetricsRequest) any())).thenReturn(response);

        // When: Discovering producers
        List<String> producers = discovery.discoverProducers("test-cluster");

        // Then: Both producers discovered
        assertThat(producers).containsExactlyInAnyOrder("producer-1", "producer-2");
    }

    @Test
    void shouldHandleEmptyProducerList() {
        // Given: No producers found in CloudWatch
        ListMetricsResponse response = ListMetricsResponse.builder()
            .metrics(List.of())
            .build();

        when(cloudWatch.listMetrics((ListMetricsRequest) any())).thenReturn(response);

        // When: Discovering producers
        List<String> producers = discovery.discoverProducers("test-cluster");

        // Then: Empty list returned
        assertThat(producers).isEmpty();
    }

    @Test
    void shouldDetectActiveProducer() {
        // Given: CloudWatch returns metrics showing recent traffic
        GetMetricStatisticsResponse response = GetMetricStatisticsResponse.builder()
            .datapoints(
                Datapoint.builder()
                    .sum(1024.0)  // Has traffic
                    .build()
            )
            .build();

        when(cloudWatch.getMetricStatistics((GetMetricStatisticsRequest) any())).thenReturn(response);

        // When: Checking if producer is active
        boolean active = discovery.isProducerActive("producer-1", "test-cluster");

        // Then: Producer is active
        assertThat(active).isTrue();
    }

    @Test
    void shouldDetectInactiveProducer() {
        // Given: CloudWatch returns no recent traffic
        GetMetricStatisticsResponse response = GetMetricStatisticsResponse.builder()
            .datapoints(List.of())  // No traffic
            .build();

        when(cloudWatch.getMetricStatistics((GetMetricStatisticsRequest) any())).thenReturn(response);

        // When: Checking if producer is active
        boolean active = discovery.isProducerActive("producer-1", "test-cluster");

        // Then: Producer is inactive
        assertThat(active).isFalse();
    }
}
