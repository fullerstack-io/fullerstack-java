package io.fullerstack.kafka.runtime;

import java.util.List;

/**
 * Aggregated health summary across multiple MSK clusters.
 * <p>
 * Provides fleet-level view of monitoring status:
 * - Total cluster count
 * - Individual cluster health
 * - Aggregated broker/producer/consumer counts
 * <p>
 * Useful for multi-region/multi-cluster dashboards.
 *
 * @param totalClusters         Number of clusters being monitored
 * @param clusterHealths        Health summary for each cluster
 * @param totalActiveBrokers    Sum of active brokers across all clusters
 * @param totalActiveProducers  Sum of active producers across all clusters
 * @param totalActiveConsumers  Sum of active consumer groups across all clusters
 * @author Fullerstack
 */
public record MultiClusterHealth(
    int totalClusters,
    List<ClusterHealth> clusterHealths,
    int totalActiveBrokers,
    int totalActiveProducers,
    int totalActiveConsumers
) {

    /**
     * Checks if all clusters are healthy.
     *
     * @return true if all clusters have at least one active broker
     */
    public boolean isHealthy() {
        return clusterHealths.stream().allMatch(ClusterHealth::isHealthy);
    }

    /**
     * Gets count of healthy clusters (at least one broker monitored).
     *
     * @return number of healthy clusters
     */
    public int getHealthyClusterCount() {
        return (int) clusterHealths.stream()
            .filter(ClusterHealth::isHealthy)
            .count();
    }
}
