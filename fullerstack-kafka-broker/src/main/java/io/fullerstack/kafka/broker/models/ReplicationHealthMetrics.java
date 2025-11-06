package io.fullerstack.kafka.broker.models;

import java.util.Objects;

/**
 * Immutable record representing partition replication health metrics for a Kafka cluster.
 *
 * <p>Tracks under-replicated partitions, offline partitions, and active controller status
 * to assess cluster-wide replication health.
 *
 * <h3>JMX Sources</h3>
 * <ul>
 *   <li><b>Under-Replicated Partitions</b> - {@code kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions}</li>
 *   <li><b>Offline Partitions</b> - {@code kafka.controller:type=KafkaController,name=OfflinePartitionsCount}</li>
 *   <li><b>Active Controller Count</b> - {@code kafka.controller:type=KafkaController,name=ActiveControllerCount}</li>
 * </ul>
 *
 * <h3>Semantic Helpers</h3>
 * <ul>
 *   <li>{@link #hasUnderReplicatedPartitions()} - Some partitions lack sufficient replicas</li>
 *   <li>{@link #hasOfflinePartitions()} - Some partitions are completely offline</li>
 *   <li>{@link #hasActiveController()} - Cluster has an active controller</li>
 *   <li>{@link #isHealthy()} - All partitions fully replicated and controller active</li>
 *   <li>{@link #isDegraded()} - Some partitions under-replicated but no offline partitions</li>
 *   <li>{@link #isDefective()} - Partitions offline or no active controller</li>
 * </ul>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * ReplicationHealthMetrics metrics = new ReplicationHealthMetrics(
 *     "cluster-1",
 *     5,   // 5 under-replicated partitions
 *     0,   // 0 offline partitions
 *     1,   // 1 active controller
 *     System.currentTimeMillis()
 * );
 *
 * if (metrics.isDegraded()) {
 *     alert("Replication health degraded: " + metrics.underReplicatedPartitions() + " partitions");
 * }
 * }</pre>
 *
 * @param clusterId                 Cluster identifier (e.g., "cluster-1", "prod-kafka")
 * @param underReplicatedPartitions Number of partitions with replicas < min.insync.replicas
 * @param offlinePartitions         Number of partitions with no available replicas
 * @param activeControllerCount     Number of active controllers (should be 0 or 1)
 * @param timestamp                 Collection time (epoch milliseconds)
 *
 * @see IsrMetrics
 */
public record ReplicationHealthMetrics(
    String clusterId,
    int underReplicatedPartitions,
    int offlinePartitions,
    int activeControllerCount,
    long timestamp
) {
    /**
     * Compact constructor with validation rules.
     *
     * @throws NullPointerException     if clusterId is null
     * @throws IllegalArgumentException if partition counts are negative
     */
    public ReplicationHealthMetrics {
        Objects.requireNonNull(clusterId, "clusterId required");
        if (underReplicatedPartitions < 0) {
            throw new IllegalArgumentException(
                "underReplicatedPartitions must be >= 0, got: " + underReplicatedPartitions
            );
        }
        if (offlinePartitions < 0) {
            throw new IllegalArgumentException(
                "offlinePartitions must be >= 0, got: " + offlinePartitions
            );
        }
        if (activeControllerCount < 0) {
            throw new IllegalArgumentException(
                "activeControllerCount must be >= 0, got: " + activeControllerCount
            );
        }
    }

    /**
     * Determines if there are under-replicated partitions.
     * <p>
     * Under-replicated partitions have fewer in-sync replicas than required by min.insync.replicas.
     * This indicates replication lag or replica failures but data is still available.
     *
     * @return true if underReplicatedPartitions > 0
     */
    public boolean hasUnderReplicatedPartitions() {
        return underReplicatedPartitions > 0;
    }

    /**
     * Determines if there are offline partitions.
     * <p>
     * Offline partitions have no leader and no available replicas. This is a critical
     * condition indicating data unavailability.
     *
     * @return true if offlinePartitions > 0
     */
    public boolean hasOfflinePartitions() {
        return offlinePartitions > 0;
    }

    /**
     * Determines if cluster has an active controller.
     * <p>
     * Kafka cluster should have exactly 1 active controller. If count is 0, the cluster
     * cannot perform partition leader elections or replication changes.
     *
     * @return true if activeControllerCount == 1
     */
    public boolean hasActiveController() {
        return activeControllerCount == 1;
    }

    /**
     * Determines if replication health is fully healthy.
     * <p>
     * Healthy state: all partitions fully replicated, no offline partitions, active controller.
     *
     * @return true if all conditions are healthy
     */
    public boolean isHealthy() {
        return !hasUnderReplicatedPartitions()
            && !hasOfflinePartitions()
            && hasActiveController();
    }

    /**
     * Determines if replication health is degraded (warning state).
     * <p>
     * Degraded state: some under-replicated partitions but no offline partitions and controller active.
     * Data is available but replication redundancy is reduced.
     *
     * @return true if under-replicated but not offline
     */
    public boolean isDegraded() {
        return hasUnderReplicatedPartitions()
            && !hasOfflinePartitions()
            && hasActiveController();
    }

    /**
     * Determines if replication health is defective (critical state).
     * <p>
     * Defective state: offline partitions or no active controller. This is a critical
     * condition requiring immediate attention.
     *
     * @return true if offline partitions exist or no active controller
     */
    public boolean isDefective() {
        return hasOfflinePartitions() || !hasActiveController();
    }
}
