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
     * @param brokerId the broker ID
     * @return the broker cell
     */
    public Cell<Monitors.Sign, Monitors.Sign> getBrokerCell(String brokerId) {
        return clusterCell.get(cortex().name(brokerId));
    }

    /**
     * Returns the topic cell for the specified broker and topic.
     *
     * @param brokerId the broker ID
     * @param topicName the topic name
     * @return the topic cell
     */
    public Cell<Monitors.Sign, Monitors.Sign> getTopicCell(String brokerId, String topicName) {
        return getBrokerCell(brokerId).get(cortex().name(topicName));
    }

    /**
     * Returns the partition cell for the specified broker, topic, and partition.
     *
     * @param brokerId the broker ID
     * @param topicName the topic name
     * @param partitionId the partition ID
     * @return the partition cell
     */
    public Cell<Monitors.Sign, Monitors.Sign> getPartitionCell(
        String brokerId,
        String topicName,
        String partitionId
    ) {
        return getTopicCell(brokerId, topicName).get(cortex().name(partitionId));
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
