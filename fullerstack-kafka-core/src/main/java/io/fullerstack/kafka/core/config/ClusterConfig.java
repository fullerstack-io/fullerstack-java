package io.fullerstack.kafka.core.config;

import static io.humainary.substrates.api.Substrates.Cortex;
import static io.humainary.substrates.api.Substrates.Name;

import java.util.List;
import java.util.Objects;

/**
 * Configuration for an AWS MSK (Managed Streaming for Kafka) cluster to be monitored.
 * <p>
 * Immutable configuration record containing all necessary information
 * to connect to and monitor an MSK cluster within a hierarchical Cell structure.
 * <p>
 * <b>AWS MSK Hierarchical Cell Structure:</b>
 * <pre>
 * msk (root Cell)
 * └── prod-account (AWS account Cell)
 *     └── us-east-1 (AWS region Cell)
 *         └── transactions-cluster (MSK cluster Cell)
 *             ├── broker-1 (broker Cell)
 *             ├── broker-2
 *             └── broker-3
 * </pre>
 * <p>
 * Example usage:
 * <pre>
 * ClusterConfig config = new ClusterConfig(
 *     "prod-account",                              // AWS account identifier
 *     "us-east-1",                                 // AWS region
 *     "transactions-cluster",                      // MSK cluster name
 *     "b-1.trans.xyz.kafka.us-east-1.amazonaws.com:9092,...",  // Bootstrap brokers
 *     "b-1.trans.xyz.kafka.us-east-1.amazonaws.com:11001",     // JMX endpoint
 *     30_000                                       // Collection interval (30 seconds)
 * );
 *
 * // Factory method with defaults
 * ClusterConfig config2 = ClusterConfig.of(
 *     "prod-account", "us-east-1", "transactions-cluster",
 *     "b-1.trans...:9092,...", "b-1.trans...:11001"
 * );
 * // Results in hierarchical names: msk.prod-account.us-east-1.transactions-cluster.broker-1
 * </pre>
 * <p>
 * <b>Note:</b> Health thresholds are now configured via properties files (config_broker-health.properties)
 * and loaded through HierarchicalConfig by the BrokerHealthStructureProvider.
 *
 * @param accountName AWS account identifier (e.g., "prod-account", "dev-account", "staging")
 * @param regionName AWS region (e.g., "us-east-1", "eu-west-1", "ap-south-1")
 * @param clusterName MSK cluster name (e.g., "transactions-cluster", "analytics-cluster")
 * @param bootstrapServers Kafka bootstrap servers (comma-separated MSK broker endpoints)
 * @param jmxUrl JMX connection URL for broker metrics collection (MSK exposes JMX on port 11001)
 * @param collectionIntervalMs Interval between metric collections in milliseconds
 * @param jmxConnectionPoolConfig Optional JMX connection pooling configuration (null to disable pooling)
 */
public record ClusterConfig(
        String accountName,
        String regionName,
        String clusterName,
        String bootstrapServers,
        String jmxUrl,
        long collectionIntervalMs,
        JmxConnectionPoolConfig jmxConnectionPoolConfig
) {
    /**
     * Default collection interval: 30 seconds.
     */
    public static final long DEFAULT_COLLECTION_INTERVAL_MS = 30_000;

    /**
     * Default AWS account name if none provided.
     */
    public static final String DEFAULT_ACCOUNT_NAME = "default-account";

    /**
     * Default AWS region if none provided.
     */
    public static final String DEFAULT_REGION_NAME = "us-east-1";

    /**
     * Default MSK cluster name if none provided.
     */
    public static final String DEFAULT_CLUSTER_NAME = "default-cluster";

    /**
     * Compact constructor with validation.
     */
    public ClusterConfig {
        Objects.requireNonNull(accountName, "accountName cannot be null");
        Objects.requireNonNull(regionName, "regionName cannot be null");
        Objects.requireNonNull(clusterName, "clusterName cannot be null");
        Objects.requireNonNull(bootstrapServers, "bootstrapServers cannot be null");
        Objects.requireNonNull(jmxUrl, "jmxUrl cannot be null");

        if (accountName.isBlank()) {
            throw new IllegalArgumentException("accountName cannot be blank");
        }
        if (regionName.isBlank()) {
            throw new IllegalArgumentException("regionName cannot be blank");
        }
        if (clusterName.isBlank()) {
            throw new IllegalArgumentException("clusterName cannot be blank");
        }
        if (bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("bootstrapServers cannot be blank");
        }
        if (jmxUrl.isBlank()) {
            throw new IllegalArgumentException("jmxUrl cannot be blank");
        }
        if (collectionIntervalMs <= 0) {
            throw new IllegalArgumentException("collectionIntervalMs must be positive");
        }
    }

    /**
     * Create hierarchical Name for this cluster using Cortex.name(Iterable).
     * <p>
     * Uses {@link Cortex#name(Iterable)} to build the hierarchy in one call.
     * <p>
     * Example:
     * <pre>
     * Cortex cortex = new CortexRuntime();
     * ClusterConfig config = new ClusterConfig("prod-account", "us-east-1", "transactions-cluster", ...);
     * Name clusterName = config.getClusterName(cortex);
     * // → Name hierarchy: "prod-account" → "us-east-1" → "transactions-cluster"
     * // → toString(): "prod-account.us-east-1.transactions-cluster"
     * </pre>
     *
     * @param cortex Cortex instance for creating Names
     * @return Hierarchical Name for this cluster (account.region.cluster)
     */
    public Name getClusterName(Cortex cortex) {
        Objects.requireNonNull(cortex, "cortex cannot be null");

        // Use cortex.name(Iterable<String>) to build the entire hierarchy in one call
        return cortex.name(List.of(accountName, regionName, clusterName));
    }

    /**
     * Extract broker identifier from AWS MSK broker DNS name.
     * <p>
     * MSK broker naming: b-1.cluster-id.kafka.region.amazonaws.com
     * <p>
     * Examples:
     * <ul>
     *   <li>"b-1.transactions-xyz.kafka.us-east-1.amazonaws.com" → "b-1"</li>
     *   <li>"b-2.transactions-xyz.kafka.us-east-1.amazonaws.com" → "b-2"</li>
     *   <li>"b-3.transactions-xyz.kafka.us-east-1.amazonaws.com" → "b-3"</li>
     * </ul>
     *
     * @param brokerHostname AWS MSK broker hostname
     * @return Broker identifier (e.g., "b-1", "b-2", "b-3")
     */
    public static String extractBrokerId(String brokerHostname) {
        if (brokerHostname == null || brokerHostname.isBlank()) {
            throw new IllegalArgumentException("Broker hostname cannot be null or blank");
        }

        // AWS MSK format: b-N.cluster-id.kafka.region.amazonaws.com
        // Extract "b-N" part
        int dotIndex = brokerHostname.indexOf('.');
        if (dotIndex > 0) {
            return brokerHostname.substring(0, dotIndex);
        }

        // Fallback: return as-is (for localhost or non-MSK brokers)
        return brokerHostname;
    }

    /**
     * Create hierarchical Name for a specific broker using Cortex.name(Iterable).
     * <p>
     * Builds the complete hierarchy: account → region → cluster → broker
     * <p>
     * Example:
     * <pre>
     * Cortex cortex = new CortexRuntime();
     * ClusterConfig config = new ClusterConfig("prod-account", "us-east-1", "transactions-cluster", ...);
     *
     * // For MSK broker hostname
     * Name broker1Name = config.getBrokerName(cortex, "b-1.trans.kafka.us-east-1.amazonaws.com");
     * // → "prod-account.us-east-1.transactions-cluster.b-1"
     *
     * // Or with simple broker ID
     * Name broker2Name = config.getBrokerName(cortex, "b-2");
     * // → "prod-account.us-east-1.transactions-cluster.b-2"
     * </pre>
     *
     * @param cortex Cortex instance for creating Names
     * @param brokerId Broker identifier (e.g., "b-1", "b-2", or full AWS MSK hostname)
     * @return Hierarchical Name for the broker (account.region.cluster.broker)
     */
    public Name getBrokerName(Cortex cortex, String brokerId) {
        Objects.requireNonNull(cortex, "cortex cannot be null");

        String normalizedBrokerId = extractBrokerId(brokerId);

        // Use cortex.name(Iterable<String>) to build the entire 4-level hierarchy in one call
        return cortex.name(List.of(accountName, regionName, clusterName, normalizedBrokerId));
    }

    /**
     * Create a ClusterConfig with default collection interval.
     *
     * @param accountName AWS account identifier
     * @param regionName AWS region
     * @param clusterName MSK cluster name
     * @param bootstrapServers MSK bootstrap broker endpoints
     * @param jmxUrl JMX connection URL (typically port 11001 for MSK)
     * @return ClusterConfig with 30-second collection interval (no pooling)
     */
    public static ClusterConfig of(String accountName, String regionName, String clusterName,
                                    String bootstrapServers, String jmxUrl) {
        return new ClusterConfig(accountName, regionName, clusterName, bootstrapServers, jmxUrl,
                                DEFAULT_COLLECTION_INTERVAL_MS, null);
    }

    /**
     * Create a ClusterConfig with default account, region, cluster name, and collection interval.
     * <p>
     * Useful for single-cluster monitoring scenarios or local development.
     *
     * @param bootstrapServers Kafka bootstrap servers
     * @param jmxUrl JMX connection URL
     * @return ClusterConfig with default account/region/cluster and 30-second collection interval (no pooling)
     */
    public static ClusterConfig withDefaults(String bootstrapServers, String jmxUrl) {
        return new ClusterConfig(DEFAULT_ACCOUNT_NAME, DEFAULT_REGION_NAME, DEFAULT_CLUSTER_NAME,
                                bootstrapServers, jmxUrl, DEFAULT_COLLECTION_INTERVAL_MS, null);
    }

    /**
     * Create a ClusterConfig for high-frequency monitoring with connection pooling.
     * <p>
     * Recommended for collection intervals < 10 seconds. Enables JMX connection pooling
     * to reduce overhead from 50-200ms per cycle to <5ms per cycle (90-95% reduction).
     * <p>
     * Example:
     * <pre>
     * ClusterConfig config = ClusterConfig.withHighFrequencyMonitoring(
     *     "prod-account", "us-east-1", "transactions-cluster",
     *     "b-1.trans.kafka...:9092,...", "b-1.trans.kafka...:11001",
     *     5_000  // 5-second collection interval
     * );
     * // Pooling enabled automatically
     * </pre>
     *
     * @param accountName AWS account identifier
     * @param regionName AWS region
     * @param clusterName MSK cluster name
     * @param bootstrapServers MSK bootstrap broker endpoints
     * @param jmxUrl JMX connection URL
     * @param collectionIntervalMs Collection interval in milliseconds (< 10000 recommended for pooling)
     * @return ClusterConfig with connection pooling enabled
     */
    public static ClusterConfig withHighFrequencyMonitoring(String accountName, String regionName,
                                                             String clusterName, String bootstrapServers,
                                                             String jmxUrl, long collectionIntervalMs) {
        return new ClusterConfig(accountName, regionName, clusterName, bootstrapServers, jmxUrl,
                                collectionIntervalMs, JmxConnectionPoolConfig.withPoolingEnabled());
    }
}
