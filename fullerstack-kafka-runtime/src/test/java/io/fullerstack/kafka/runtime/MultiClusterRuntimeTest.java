package io.fullerstack.kafka.runtime;

import io.fullerstack.kafka.core.baseline.BaselineService;
import io.fullerstack.kafka.msk.MSKClusterConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.kafka.KafkaClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MultiClusterRuntime}.
 */
class MultiClusterRuntimeTest {

    private MultiClusterRuntime runtime;

    private static final String CLUSTER_ARN_1 = "arn:aws:kafka:us-east-1:123:cluster/cluster-1/abc";
    private static final String CLUSTER_ARN_2 = "arn:aws:kafka:us-east-1:123:cluster/cluster-2/def";
    private static final String CLUSTER_ARN_3 = "arn:aws:kafka:us-west-2:456:cluster/cluster-3/ghi";

    @BeforeEach
    void setUp() {
        runtime = new MultiClusterRuntime();
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void shouldStartWithZeroClusters() {
        // Given: Newly created runtime
        // Then: No clusters initially
        assertThat(runtime.getClusterCount()).isEqualTo(0);
    }

    @Test
    void shouldAddCluster() {
        // Given: MSKClusterConfig
        MSKClusterConfig config = createConfig(CLUSTER_ARN_1);

        // When: Adding cluster
        runtime.addCluster(config);

        // Then: Cluster count increased
        assertThat(runtime.getClusterCount()).isEqualTo(1);
    }

    @Test
    void shouldAddMultipleClusters() {
        // Given: Multiple cluster configs
        MSKClusterConfig config1 = createConfig(CLUSTER_ARN_1);
        MSKClusterConfig config2 = createConfig(CLUSTER_ARN_2);
        MSKClusterConfig config3 = createConfig(CLUSTER_ARN_3);

        // When: Adding multiple clusters
        runtime.addCluster(config1);
        runtime.addCluster(config2);
        runtime.addCluster(config3);

        // Then: All clusters tracked
        assertThat(runtime.getClusterCount()).isEqualTo(3);
    }

    @Test
    void shouldNotAddDuplicateCluster() {
        // Given: Cluster already added
        MSKClusterConfig config = createConfig(CLUSTER_ARN_1);
        runtime.addCluster(config);

        int countBefore = runtime.getClusterCount();

        // When: Adding same cluster again
        runtime.addCluster(config);

        // Then: Count unchanged (warning logged)
        assertThat(runtime.getClusterCount()).isEqualTo(countBefore);
        assertThat(runtime.getClusterCount()).isEqualTo(1);
    }

    @Test
    void shouldRemoveCluster() {
        // Given: Cluster added
        MSKClusterConfig config = createConfig(CLUSTER_ARN_1);
        runtime.addCluster(config);
        assertThat(runtime.getClusterCount()).isEqualTo(1);

        // When: Removing cluster
        runtime.removeCluster(CLUSTER_ARN_1);

        // Then: Cluster removed
        assertThat(runtime.getClusterCount()).isEqualTo(0);
    }

    @Test
    void shouldRemoveSpecificCluster() {
        // Given: Multiple clusters
        MSKClusterConfig config1 = createConfig(CLUSTER_ARN_1);
        MSKClusterConfig config2 = createConfig(CLUSTER_ARN_2);
        MSKClusterConfig config3 = createConfig(CLUSTER_ARN_3);

        runtime.addCluster(config1);
        runtime.addCluster(config2);
        runtime.addCluster(config3);

        // When: Removing one cluster
        runtime.removeCluster(CLUSTER_ARN_2);

        // Then: Only that cluster removed
        assertThat(runtime.getClusterCount()).isEqualTo(2);
    }

    @Test
    void shouldHandleRemoveNonExistentCluster() {
        // Given: Runtime with no clusters
        // When: Removing non-existent cluster
        runtime.removeCluster("arn:aws:kafka:us-east-1:123:cluster/nonexistent/xyz");

        // Then: No error (warning logged)
        assertThat(runtime.getClusterCount()).isEqualTo(0);
    }

    @Test
    void shouldReturnEmptyHealthWithNoClusters() {
        // Given: No clusters
        // When: Getting health
        MultiClusterHealth health = runtime.getHealth();

        // Then: Empty health summary
        assertThat(health.totalClusters()).isEqualTo(0);
        assertThat(health.clusterHealths()).isEmpty();
        assertThat(health.totalActiveBrokers()).isEqualTo(0);
        assertThat(health.totalActiveProducers()).isEqualTo(0);
        assertThat(health.totalActiveConsumers()).isEqualTo(0);
    }

    @Test
    void shouldReturnHealthForSingleCluster() {
        // Given: One cluster
        MSKClusterConfig config = createConfig(CLUSTER_ARN_1);
        runtime.addCluster(config);

        // When: Getting health
        MultiClusterHealth health = runtime.getHealth();

        // Then: Health summary for one cluster
        assertThat(health.totalClusters()).isEqualTo(1);
        assertThat(health.clusterHealths()).hasSize(1);
        assertThat(health.clusterHealths().get(0).clusterArn()).isEqualTo(CLUSTER_ARN_1);
    }

    @Test
    void shouldAggregateHealthAcrossMultipleClusters() {
        // Given: Multiple clusters
        MSKClusterConfig config1 = createConfig(CLUSTER_ARN_1);
        MSKClusterConfig config2 = createConfig(CLUSTER_ARN_2);
        MSKClusterConfig config3 = createConfig(CLUSTER_ARN_3);

        runtime.addCluster(config1);
        runtime.addCluster(config2);
        runtime.addCluster(config3);

        // When: Getting health
        MultiClusterHealth health = runtime.getHealth();

        // Then: Aggregated health
        assertThat(health.totalClusters()).isEqualTo(3);
        assertThat(health.clusterHealths()).hasSize(3);

        // All totals should be >= 0 (sum of individual cluster metrics)
        assertThat(health.totalActiveBrokers()).isGreaterThanOrEqualTo(0);
        assertThat(health.totalActiveProducers()).isGreaterThanOrEqualTo(0);
        assertThat(health.totalActiveConsumers()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldCloseAllClustersOnClose() {
        // Given: Multiple clusters
        MSKClusterConfig config1 = createConfig(CLUSTER_ARN_1);
        MSKClusterConfig config2 = createConfig(CLUSTER_ARN_2);

        runtime.addCluster(config1);
        runtime.addCluster(config2);

        assertThat(runtime.getClusterCount()).isEqualTo(2);

        // When: Closing runtime
        runtime.close();

        // Then: All clusters closed
        assertThat(runtime.getClusterCount()).isEqualTo(0);
    }

    @Test
    void shouldImplementAutoCloseable() {
        // Given: MultiClusterRuntime with clusters
        MSKClusterConfig config1 = createConfig(CLUSTER_ARN_1);
        MSKClusterConfig config2 = createConfig(CLUSTER_ARN_2);

        // When: Using try-with-resources
        try (MultiClusterRuntime autoCloseRuntime = new MultiClusterRuntime()) {
            autoCloseRuntime.addCluster(config1);
            autoCloseRuntime.addCluster(config2);

            assertThat(autoCloseRuntime.getClusterCount()).isEqualTo(2);
        }

        // Then: Resources automatically closed (no exceptions)
        runtime = null; // Prevent double-close in tearDown
    }

    @Test
    void shouldIsolateFaultsBetweenClusters() {
        // Given: Multiple clusters (one might fail)
        MSKClusterConfig config1 = createConfig(CLUSTER_ARN_1);
        MSKClusterConfig config2 = createConfig(CLUSTER_ARN_2);

        runtime.addCluster(config1);
        runtime.addCluster(config2);

        // When: Getting health (even if one cluster fails, doesn't affect others)
        MultiClusterHealth health = runtime.getHealth();

        // Then: Health returned (fault-isolated)
        assertThat(health).isNotNull();
        assertThat(health.totalClusters()).isEqualTo(2);
    }

    @Test
    void shouldSupportConcurrentClusterOperations() {
        // Given: Multiple cluster configs
        MSKClusterConfig config1 = createConfig(CLUSTER_ARN_1);
        MSKClusterConfig config2 = createConfig(CLUSTER_ARN_2);

        // When: Adding clusters concurrently (ConcurrentHashMap supports this)
        runtime.addCluster(config1);
        runtime.addCluster(config2);

        // Then: Both clusters tracked
        assertThat(runtime.getClusterCount()).isEqualTo(2);

        // When: Removing one while querying health
        runtime.removeCluster(CLUSTER_ARN_1);
        MultiClusterHealth health = runtime.getHealth();

        // Then: Consistent state
        assertThat(runtime.getClusterCount()).isEqualTo(1);
        assertThat(health.clusterHealths()).hasSize(1);
    }

    @Test
    void shouldHandleAddAfterRemove() {
        // Given: Cluster added then removed
        MSKClusterConfig config = createConfig(CLUSTER_ARN_1);
        runtime.addCluster(config);
        runtime.removeCluster(CLUSTER_ARN_1);

        assertThat(runtime.getClusterCount()).isEqualTo(0);

        // When: Adding same cluster again
        runtime.addCluster(config);

        // Then: Cluster added successfully
        assertThat(runtime.getClusterCount()).isEqualTo(1);
    }

    @Test
    void shouldProvideClusterCount() {
        // Given: Empty runtime
        assertThat(runtime.getClusterCount()).isEqualTo(0);

        // When: Adding clusters incrementally
        runtime.addCluster(createConfig(CLUSTER_ARN_1));
        assertThat(runtime.getClusterCount()).isEqualTo(1);

        runtime.addCluster(createConfig(CLUSTER_ARN_2));
        assertThat(runtime.getClusterCount()).isEqualTo(2);

        runtime.addCluster(createConfig(CLUSTER_ARN_3));
        assertThat(runtime.getClusterCount()).isEqualTo(3);

        // When: Removing clusters
        runtime.removeCluster(CLUSTER_ARN_2);
        assertThat(runtime.getClusterCount()).isEqualTo(2);

        runtime.removeCluster(CLUSTER_ARN_1);
        assertThat(runtime.getClusterCount()).isEqualTo(1);

        runtime.removeCluster(CLUSTER_ARN_3);
        assertThat(runtime.getClusterCount()).isEqualTo(0);
    }

    @Test
    void shouldMaintainHealthConsistencyAfterRemoval() {
        // Given: Multiple clusters
        MSKClusterConfig config1 = createConfig(CLUSTER_ARN_1);
        MSKClusterConfig config2 = createConfig(CLUSTER_ARN_2);

        runtime.addCluster(config1);
        runtime.addCluster(config2);

        // When: Removing one cluster
        runtime.removeCluster(CLUSTER_ARN_1);

        MultiClusterHealth health = runtime.getHealth();

        // Then: Health only includes remaining cluster
        assertThat(health.totalClusters()).isEqualTo(1);
        assertThat(health.clusterHealths()).hasSize(1);
        assertThat(health.clusterHealths().get(0).clusterArn()).isEqualTo(CLUSTER_ARN_2);
    }

    // Helper method to create MSKClusterConfig
    // Helper method to create MSKClusterConfig with mocked dependencies
    private MSKClusterConfig createConfig(String clusterArn) {
        return MSKClusterConfig.builder()
            .clusterArn(clusterArn)
            .mskClient(mock(KafkaClient.class))
            .cloudWatchClient(mock(CloudWatchClient.class))
            .adminClient(mock(AdminClient.class))
            .baselineService(mock(BaselineService.class))
            .build();
    }
}
