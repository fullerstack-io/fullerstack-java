package io.fullerstack.kafka.runtime;

import io.fullerstack.kafka.msk.MSKClusterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime for monitoring multiple MSK clusters.
 * <p>
 * Each cluster has its own Circuit and Epic 1 monitoring agents.
 * This is INFRASTRUCTURE management - Epic 1's agents emit RC1 signals.
 * <p>
 * <b>Thread Safety</b>:
 * - ConcurrentHashMap for cluster tracking
 * - Safe concurrent addCluster/removeCluster operations
 * - Each ClusterRuntime is isolated with its own Circuit
 * <p>
 * <b>Fault Isolation</b>:
 * - Failure in one cluster doesn't affect others
 * - Graceful degradation (monitor N-1 clusters if 1 fails)
 * <p>
 * <b>Lifecycle</b>:
 * 1. Create MultiClusterRuntime
 * 2. Call addCluster() for each MSK cluster to monitor
 * 3. Call getHealth() for fleet-level monitoring status
 * 4. Call removeCluster() to stop monitoring specific cluster
 * 5. Call close() to shutdown all cluster monitoring
 *
 * @author Fullerstack
 */
public class MultiClusterRuntime implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MultiClusterRuntime.class);

    private final Map<String, ClusterRuntime> clusters = new ConcurrentHashMap<>();

    /**
     * Add a cluster to monitor.
     * <p>
     * Creates isolated ClusterRuntime with its own Circuit and starts monitoring.
     * If cluster already exists, logs warning and skips.
     *
     * @param config MSK cluster configuration
     */
    public void addCluster(MSKClusterConfig config) {
        String clusterArn = config.clusterArn();

        if (clusters.containsKey(clusterArn)) {
            logger.warn("Cluster already being monitored: {}", clusterArn);
            return;
        }

        try {
            logger.info("Adding cluster for monitoring: {}", clusterArn);

            // Create isolated ClusterRuntime (creates its own Circuit)
            ClusterRuntime runtime = ClusterRuntime.create(config);

            // Start monitoring (Epic 5 discovery → Epic 1 monitoring)
            runtime.start();

            // Track cluster
            clusters.put(clusterArn, runtime);

            logger.info("Successfully started monitoring cluster: {}", clusterArn);
        } catch (Exception e) {
            logger.error("Failed to add cluster for monitoring: {}", clusterArn, e);
            // Don't throw - fault isolation (one cluster failure doesn't affect others)
        }
    }

    /**
     * Remove a cluster from monitoring.
     * <p>
     * Gracefully stops Epic 5 discovery (which stops Epic 1 monitoring) and closes Circuit.
     * If cluster not found, logs warning and continues.
     *
     * @param clusterArn AWS ARN of cluster to stop monitoring
     */
    public void removeCluster(String clusterArn) {
        ClusterRuntime runtime = clusters.remove(clusterArn);

        if (runtime == null) {
            logger.warn("Cluster not found for removal: {}", clusterArn);
            return;
        }

        try {
            logger.info("Removing cluster from monitoring: {}", clusterArn);

            // Gracefully stop monitoring and close Circuit
            runtime.close();

            logger.info("Successfully stopped monitoring cluster: {}", clusterArn);
        } catch (Exception e) {
            logger.error("Error while removing cluster: {}", clusterArn, e);
            // Continue - cluster already removed from map
        }
    }

    /**
     * Get aggregated health across all monitored clusters.
     * <p>
     * Aggregates:
     * - Total cluster count
     * - Individual cluster health summaries
     * - Total active brokers/producers/consumers
     * <p>
     * If a cluster fails during health check, logs error and excludes from totals.
     *
     * @return MultiClusterHealth with fleet-level metrics
     */
    public MultiClusterHealth getHealth() {
        List<ClusterHealth> clusterHealths = clusters.values().stream()
            .map(runtime -> {
                try {
                    return runtime.getHealth();
                } catch (Exception e) {
                    logger.error("Failed to get health for cluster", e);
                    return null;
                }
            })
            .filter(health -> health != null)
            .toList();

        // Aggregate totals
        int totalBrokers = clusterHealths.stream()
            .mapToInt(ClusterHealth::activeBrokers)
            .sum();

        int totalProducers = clusterHealths.stream()
            .mapToInt(ClusterHealth::activeProducers)
            .sum();

        int totalConsumers = clusterHealths.stream()
            .mapToInt(ClusterHealth::activeConsumers)
            .sum();

        return new MultiClusterHealth(
            clusters.size(),
            clusterHealths,
            totalBrokers,
            totalProducers,
            totalConsumers
        );
    }

    /**
     * Get number of clusters being monitored.
     *
     * @return cluster count
     */
    public int getClusterCount() {
        return clusters.size();
    }

    /**
     * Shutdown all cluster monitoring.
     * <p>
     * Gracefully stops Epic 5 discovery (which stops Epic 1 monitoring) for all clusters
     * and closes all Circuits.
     */
    @Override
    public void close() {
        logger.info("Shutting down monitoring for {} clusters", clusters.size());

        // Close all cluster runtimes (fault-isolated)
        clusters.forEach((arn, runtime) -> {
            try {
                runtime.close();
            } catch (Exception e) {
                logger.error("Error closing cluster runtime: {}", arn, e);
                // Continue closing other clusters
            }
        });

        clusters.clear();

        logger.info("Successfully shutdown all cluster monitoring");
    }
}
