package io.fullerstack.kafka.core.command;

import io.humainary.substrates.api.Substrates.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Manages command propagation through the Kafka topology hierarchy.
 *
 * <p>Provides bidirectional flow complementing the Sign hierarchy:
 * <ul>
 *   <li><b>Sign hierarchy (upward)</b>: Partition → Topic → Broker → Cluster (observability)</li>
 *   <li><b>Command hierarchy (downward)</b>: Cluster → Broker → Topic → Partition (control)</li>
 * </ul>
 *
 * <p><b>Architecture:</b>
 * <pre>
 * CommandHierarchy creates a Cell&lt;Command, Command&gt; tree with custom ingress composers
 * that broadcast commands to all children.
 *
 * Cluster Cell (ingress: broadcast to brokers)
 *   ├── Broker Cell (ingress: broadcast to topics)
 *   │    ├── Topic Cell (ingress: broadcast to partitions)
 *   │    │    └── Partition Cell (ingress: execute via handler)
 * </pre>
 *
 * <p><b>Usage - Issue Command at Cluster Level:</b>
 * <pre>{@code
 * // Actor issues THROTTLE command
 * commandHierarchy.broadcast(Command.THROTTLE);
 *
 * // ↓ Flows downward through hierarchy
 * // All partitions receive THROTTLE and execute handler
 * }</pre>
 *
 * <p><b>Usage - Register Handlers:</b>
 * <pre>{@code
 * // Register partition-level handler
 * commandHierarchy.registerHandler(
 *     "broker-1.orders.p0",
 *     command -> {
 *         if (command == Command.THROTTLE) {
 *             applyBackpressure();
 *         }
 *     }
 * );
 * }</pre>
 */
public class CommandHierarchy implements AutoCloseable {

    private final Circuit circuit;
    private final Cell<Command, Command> clusterCell;
    private final Map<String, CommandHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Creates a new CommandHierarchy for the specified cluster.
     *
     * @param clusterName the cluster name
     */
    public CommandHierarchy(String clusterName) {
        this.circuit = cortex().circuit(cortex().name(clusterName + ".commands"));

        // Create cluster cell with identity composers (downward broadcast via subscriptions)
        this.clusterCell = circuit.cell(
            cortex().name("cluster"),
            Composer.pipe(),  // Ingress: identity
            Composer.pipe(),  // Egress: identity
            cortex().pipe(Command.class)  // Outlet: no-op for commands
        );
    }

    /**
     * Broadcasts a command from the cluster level to all children.
     *
     * <p>The command flows downward through the entire hierarchy:
     * cluster → all brokers → all topics → all partitions
     *
     * @param command the command to broadcast
     */
    public void broadcast(Command command) {
        clusterCell.emit(command);
    }

    /**
     * Returns the cluster cell for direct command emission.
     *
     * @return the cluster cell
     */
    public Cell<Command, Command> getClusterCell() {
        return clusterCell;
    }

    /**
     * Returns or creates a broker cell for the specified broker ID.
     *
     * @param brokerId the broker ID (e.g., "broker-1")
     * @return the broker cell
     */
    public Cell<Command, Command> getBrokerCell(String brokerId) {
        return clusterCell.percept(cortex().name(brokerId));
    }

    /**
     * Returns or creates a topic cell for the specified broker and topic.
     *
     * @param brokerId the broker ID (e.g., "broker-1")
     * @param topicName the topic name (e.g., "orders")
     * @return the topic cell
     */
    public Cell<Command, Command> getTopicCell(String brokerId, String topicName) {
        return getBrokerCell(brokerId).percept(cortex().name(brokerId + "." + topicName));
    }

    /**
     * Returns or creates a partition cell for the specified broker, topic, and partition.
     *
     * @param brokerId the broker ID (e.g., "broker-1")
     * @param topicName the topic name (e.g., "orders")
     * @param partitionId the partition ID (e.g., "p0")
     * @return the partition cell
     */
    public Cell<Command, Command> getPartitionCell(
        String brokerId,
        String topicName,
        String partitionId
    ) {
        return getTopicCell(brokerId, topicName).percept(
            cortex().name(brokerId + "." + topicName + "." + partitionId)
        );
    }

    /**
     * Registers a command handler for a specific entity.
     *
     * <p>When a command flows down to this level, the handler is invoked.
     *
     * @param entityName the fully qualified entity name (e.g., "broker-1.orders.p0")
     * @param handler the command handler
     */
    public void registerHandler(String entityName, CommandHandler handler) {
        handlers.put(entityName, handler);

        // Get the cell for this entity and subscribe to commands
        Cell<Command, Command> cell = getCellByName(entityName);
        cell.subscribe(cortex().subscriber(
            cortex().name(entityName + ".handler"),
            (Subject<Channel<Command>> subject, Registrar<Command> registrar) -> {
                registrar.register(command -> {
                    // Execute handler for this entity
                    CommandHandler h = handlers.get(entityName);
                    if (h != null) {
                        h.handle(command);
                    }
                });
            }
        ));
    }

    /**
     * Waits for all pending commands to propagate through the hierarchy.
     */
    public void await() {
        circuit.await();
    }


    /**
     * Helper to get cell by fully qualified name.
     */
    private Cell<Command, Command> getCellByName(String entityName) {
        String[] parts = entityName.split("\\.");

        if (parts.length == 1) {
            // Broker level
            return getBrokerCell(parts[0]);
        } else if (parts.length == 2) {
            // Topic level
            return getTopicCell(parts[0], parts[1]);
        } else if (parts.length == 3) {
            // Partition level
            return getPartitionCell(parts[0], parts[1], parts[2]);
        } else {
            throw new IllegalArgumentException("Invalid entity name: " + entityName);
        }
    }

    @Override
    public void close() {
        circuit.close();
    }
}
