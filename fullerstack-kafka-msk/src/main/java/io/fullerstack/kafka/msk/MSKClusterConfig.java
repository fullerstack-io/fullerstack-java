package io.fullerstack.kafka.msk;

import io.fullerstack.kafka.core.baseline.BaselineService;
import org.apache.kafka.clients.admin.AdminClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.kafka.KafkaClient;

/**
 * Configuration for monitoring a single MSK cluster.
 * <p>
 * This record encapsulates all dependencies needed to monitor one MSK cluster:
 * - AWS SDK clients (MSK, CloudWatch)
 * - Kafka AdminClient
 * - BaselineService for threshold management
 * <p>
 * Each cluster gets its own ClusterRuntime with isolated Circuit.
 *
 * @param clusterArn       AWS ARN for the MSK cluster
 * @param mskClient        AWS Kafka client for cluster discovery
 * @param cloudWatchClient AWS CloudWatch client for producer discovery
 * @param adminClient      Kafka AdminClient for consumer discovery
 * @param baselineService  Service for threshold management (shared or per-cluster)
 * @author Fullerstack
 */
public record MSKClusterConfig(
    String clusterArn,
    KafkaClient mskClient,
    CloudWatchClient cloudWatchClient,
    AdminClient adminClient,
    BaselineService baselineService
) {

    /**
     * Builder for MSKClusterConfig.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String clusterArn;
        private KafkaClient mskClient;
        private CloudWatchClient cloudWatchClient;
        private AdminClient adminClient;
        private BaselineService baselineService;

        public Builder clusterArn(String clusterArn) {
            this.clusterArn = clusterArn;
            return this;
        }

        public Builder mskClient(KafkaClient mskClient) {
            this.mskClient = mskClient;
            return this;
        }

        public Builder cloudWatchClient(CloudWatchClient cloudWatchClient) {
            this.cloudWatchClient = cloudWatchClient;
            return this;
        }

        public Builder adminClient(AdminClient adminClient) {
            this.adminClient = adminClient;
            return this;
        }

        public Builder baselineService(BaselineService baselineService) {
            this.baselineService = baselineService;
            return this;
        }

        public MSKClusterConfig build() {
            if (clusterArn == null || clusterArn.isEmpty()) {
                throw new IllegalArgumentException("clusterArn is required");
            }
            if (mskClient == null) {
                throw new IllegalArgumentException("mskClient is required");
            }
            if (cloudWatchClient == null) {
                throw new IllegalArgumentException("cloudWatchClient is required");
            }
            if (adminClient == null) {
                throw new IllegalArgumentException("adminClient is required");
            }
            if (baselineService == null) {
                throw new IllegalArgumentException("baselineService is required");
            }

            return new MSKClusterConfig(
                clusterArn,
                mskClient,
                cloudWatchClient,
                adminClient,
                baselineService
            );
        }
    }
}
