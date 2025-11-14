package io.fullerstack.kafka.core.hierarchy;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Manages the 4-level Cell hierarchy for Kafka topology.
 *
 * <p>Hierarchy: Cluster → Broker → Topic → Partition
 * <p>All cells use Cell&lt;Monitors.Sign, Monitors.Sign&gt; with identity composers.
 * <p>Child emissions automatically flow upward to parent outlets.
 */
public class HierarchyManager implements AutoCloseable {

    private final Circuit circuit;
    private final Cell<Monitors.Sign, Monitors.Sign> clusterCell;

    /**
     * Creates a new HierarchyManager for the specified cluster.
     *
     * @param clusterName the cluster name
     */
    public HierarchyManager(String clusterName) {
        this.circuit = cortex().circuit(cortex().name(clusterName));

        // Create root cell with identity composers
        this.clusterCell = circuit.cell(
            cortex().name("cluster"),
            Composer.pipe(),  // Identity ingress
            Composer.pipe(),  // Identity egress
            cortex().pipe((Monitors.Sign sign) -> {
                // Outlet receives all child emissions
                // Can add logging/aggregation here
            })
        );
    }

    /**
     * Returns the root cluster cell.
     *
     * @return the cluster cell
     */
    public Cell<Monitors.Sign, Monitors.Sign> getClusterCell() {
        return clusterCell;
    }

    /**
     * Returns the broker cell for the specified broker ID.
     *
     * <p>Uses hierarchical Name: "{broker-id}" (child of cluster)
     *
     * @param brokerId the broker ID (e.g., "broker-1")
     * @return the broker cell
     */
    public Cell<Monitors.Sign, Monitors.Sign> getBrokerCell(String brokerId) {
        // Hierarchical name: broker-1 (child of cluster)
        return clusterCell.percept(cortex().name(brokerId));
    }

    /**
     * Returns the topic cell for the specified broker and topic.
     *
     * <p>Uses hierarchical Name: "{broker-id}.{topic-name}"
     *
     * @param brokerId the broker ID (e.g., "broker-1")
     * @param topicName the topic name (e.g., "orders")
     * @return the topic cell
     */
    public Cell<Monitors.Sign, Monitors.Sign> getTopicCell(String brokerId, String topicName) {
        // Hierarchical name: broker-1.orders
        return getBrokerCell(brokerId).percept(cortex().name(brokerId + "." + topicName));
    }

    /**
     * Returns the partition cell for the specified broker, topic, and partition.
     *
     * <p>Uses hierarchical Name: "{broker-id}.{topic-name}.{partition-id}"
     *
     * @param brokerId the broker ID (e.g., "broker-1")
     * @param topicName the topic name (e.g., "orders")
     * @param partitionId the partition ID (e.g., "p0")
     * @return the partition cell
     */
    public Cell<Monitors.Sign, Monitors.Sign> getPartitionCell(
        String brokerId,
        String topicName,
        String partitionId
    ) {
        // Hierarchical name: broker-1.orders.p0
        return getTopicCell(brokerId, topicName).percept(
            cortex().name(brokerId + "." + topicName + "." + partitionId)
        );
    }

    /**
     * Returns the underlying circuit.
     *
     * @return the circuit
     */
    public Circuit getCircuit() {
        return circuit;
    }

    @Override
    public void close() {
        circuit.close();
    }
}
