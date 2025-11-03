package io.fullerstack.kafka.runtime;

import io.fullerstack.kafka.core.baseline.BaselineService;
import io.fullerstack.kafka.msk.MSKClusterConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.kafka.KafkaClient;

import static io.humainary.substrates.api.Substrates.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ClusterRuntime}.
 * <p>
 * Note: These are basic structural tests. Full integration tests require real AWS MSK clusters.
 */
class ClusterRuntimeTest {

    private ClusterRuntime runtime;

    private static final String CLUSTER_ARN = "arn:aws:kafka:us-east-1:123456789012:cluster/test-cluster/abc-123";

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void shouldCreateClusterRuntimeWithConfig() {
        // Given: MSKClusterConfig with mocked dependencies
        MSKClusterConfig config = MSKClusterConfig.builder()
            .clusterArn(CLUSTER_ARN)
            .mskClient(mock(KafkaClient.class))
            .cloudWatchClient(mock(CloudWatchClient.class))
            .adminClient(mock(AdminClient.class))
            .baselineService(mock(BaselineService.class))
            .build();

        // When: Creating ClusterRuntime
        runtime = ClusterRuntime.create(config);

        // Then: Runtime created successfully
        assertThat(runtime).isNotNull();
        assertThat(runtime.getCircuit()).isNotNull();
    }

    @Test
    void shouldCreateIsolatedCircuitPerCluster() {
        // Given: Two different cluster configs
        MSKClusterConfig config1 = MSKClusterConfig.builder()
            .clusterArn("arn:aws:kafka:us-east-1:123:cluster/cluster-1/abc")
            .mskClient(mock(KafkaClient.class))
            .cloudWatchClient(mock(CloudWatchClient.class))
            .adminClient(mock(AdminClient.class))
            .baselineService(mock(BaselineService.class))
            .build();

        MSKClusterConfig config2 = MSKClusterConfig.builder()
            .clusterArn("arn:aws:kafka:us-east-1:123:cluster/cluster-2/def")
            .mskClient(mock(KafkaClient.class))
            .cloudWatchClient(mock(CloudWatchClient.class))
            .adminClient(mock(AdminClient.class))
            .baselineService(mock(BaselineService.class))
            .build();

        // When: Creating two cluster runtimes
        ClusterRuntime runtime1 = ClusterRuntime.create(config1);
        ClusterRuntime runtime2 = ClusterRuntime.create(config2);

        try {
            // Then: Each has its own isolated Circuit
            assertThat(runtime1.getCircuit()).isNotNull();
            assertThat(runtime2.getCircuit()).isNotNull();
            assertThat(runtime1.getCircuit()).isNotSameAs(runtime2.getCircuit());
        } finally {
            runtime1.close();
            runtime2.close();
        }
    }

    @Test
    void shouldReturnClusterHealth() {
        // Given: Running cluster runtime
        MSKClusterConfig config = MSKClusterConfig.builder()
            .clusterArn(CLUSTER_ARN)
            .mskClient(mock(KafkaClient.class))
            .cloudWatchClient(mock(CloudWatchClient.class))
            .adminClient(mock(AdminClient.class))
            .baselineService(mock(BaselineService.class))
            .build();

        runtime = ClusterRuntime.create(config);

        // When: Getting health
        ClusterHealth health = runtime.getHealth();

        // Then: Health summary returned with zero counts (no discovery running)
        assertThat(health).isNotNull();
        assertThat(health.clusterArn()).isEqualTo(CLUSTER_ARN);
        assertThat(health.activeBrokers()).isEqualTo(0);
        assertThat(health.activeProducers()).isEqualTo(0);
        assertThat(health.activeConsumers()).isEqualTo(0);
    }

    @Test
    void shouldImplementAutoCloseable() {
        // Given: ClusterRuntime
        MSKClusterConfig config = MSKClusterConfig.builder()
            .clusterArn(CLUSTER_ARN)
            .mskClient(mock(KafkaClient.class))
            .cloudWatchClient(mock(CloudWatchClient.class))
            .adminClient(mock(AdminClient.class))
            .baselineService(mock(BaselineService.class))
            .build();

        // When: Using try-with-resources
        try (ClusterRuntime autoCloseRuntime = ClusterRuntime.create(config)) {
            assertThat(autoCloseRuntime.getHealth()).isNotNull();
        }

        // Then: Resources automatically closed (no exceptions)
        runtime = null; // Prevent double-close in tearDown
    }

    @Test
    void shouldProvideCircuitAccess() {
        // Given: ClusterRuntime
        MSKClusterConfig config = MSKClusterConfig.builder()
            .clusterArn(CLUSTER_ARN)
            .mskClient(mock(KafkaClient.class))
            .cloudWatchClient(mock(CloudWatchClient.class))
            .adminClient(mock(AdminClient.class))
            .baselineService(mock(BaselineService.class))
            .build();

        runtime = ClusterRuntime.create(config);

        // When: Getting Circuit
        Circuit circuit = runtime.getCircuit();

        // Then: Circuit accessible for Epic 1 monitoring agents
        assertThat(circuit).isNotNull();
    }
}
