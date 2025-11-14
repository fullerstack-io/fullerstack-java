package io.fullerstack.kafka.core.bridge;

import io.fullerstack.kafka.core.hierarchy.HierarchyManager;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Bridges Monitor conduit emissions to Cell hierarchy.
 *
 * <p>This bridge subscribes to Monitor conduit signals and forwards the {@link Monitors.Sign}
 * to the appropriate cell in the hierarchy based on the entity name parsing using Name API.
 *
 * <p><b>Signal Flow:</b>
 * <pre>
 * Monitor conduit
 *   ↓ (Monitor.Signal with Sign)
 * MonitorCellBridge (parses entity name using Name.depth() and Name.iterator())
 *   ↓ (forwards Sign)
 * Cell hierarchy (Partition → Topic → Broker → Cluster)
 * </pre>
 *
 * <p><b>Entity Name Formats (using Name API):</b>
 * <ul>
 *   <li>Partition: "broker-1.orders.p0" (depth=3) → routes to partition cell</li>
 *   <li>Topic: "broker-1.orders" (depth=2) → routes to topic cell</li>
 *   <li>Broker: "broker-1" (depth=1) → routes to broker cell</li>
 *   <li>Cluster: "cluster" (depth=1, special name) → routes to cluster cell</li>
 * </ul>
 *
 * <p><b>Name Iterator Behavior:</b>
 * Name.iterator() returns components in REVERSE order (deepest → root).
 * For "broker-1.orders.p0":
 * <ul>
 *   <li>iterator.next() → "p0" (partition)</li>
 *   <li>iterator.next() → "orders" (topic)</li>
 *   <li>iterator.next() → "broker-1" (broker)</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * Circuit monitorCircuit = cortex().circuit(cortex().name("monitors"));
 * Conduit<Monitors.Monitor, Monitors.Signal> monitors =
 *     monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);
 *
 * HierarchyManager hierarchy = new HierarchyManager("prod-cluster");
 * MonitorCellBridge bridge = new MonitorCellBridge(monitors, hierarchy);
 * bridge.start();
 *
 * // Now Monitor signals automatically flow to Cell hierarchy
 * Monitors.Monitor monitor = monitors.percept(cortex().name("broker-1.orders.p0"));
 * monitor.degraded(Monitors.Dimension.CONFIRMED);
 * // → Sign flows to partition cell → topic cell → broker cell → cluster cell
 * }</pre>
 *
 * @since 1.0.0
 * @see HierarchyManager
 */
public class MonitorCellBridge implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(MonitorCellBridge.class);

    private final Conduit<Monitors.Monitor, Monitors.Signal> monitors;
    private final HierarchyManager hierarchy;
    private Subscription subscription;

    /**
     * Creates a new MonitorCellBridge.
     *
     * @param monitors the Monitor conduit to subscribe to
     * @param hierarchy the Cell hierarchy manager to forward Signs to
     * @throws NullPointerException if monitors or hierarchy is null
     */
    public MonitorCellBridge(
        Conduit<Monitors.Monitor, Monitors.Signal> monitors,
        HierarchyManager hierarchy
    ) {
        this.monitors = Objects.requireNonNull(monitors, "monitors cannot be null");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy cannot be null");
    }

    /**
     * Starts the bridge by subscribing to the Monitor conduit.
     *
     * <p>This method must be called to activate signal forwarding.
     * Call {@link #close()} to stop forwarding.
     */
    public void start() {
        if (subscription != null) {
            logger.warn("Bridge already started, ignoring duplicate start() call");
            return;
        }

        subscription = monitors.subscribe(
            cortex().subscriber(
                cortex().name("monitor-cell-bridge"),
                this::handleMonitorSignal
            )
        );

        logger.info("MonitorCellBridge started, subscribed to Monitor conduit");
    }

    /**
     * Handles incoming Monitor signals and forwards Signs to appropriate cells.
     *
     * @param subject the signal subject (contains entity name)
     * @param registrar the registrar for signal callbacks
     */
    private void handleMonitorSignal(
        Subject<Channel<Monitors.Signal>> subject,
        Registrar<Monitors.Signal> registrar
    ) {
        registrar.register(signal -> {
            try {
                // Extract Name from subject
                Name entityName = subject.name();

                // Parse hierarchy level using Name.depth()
                int depth = entityName.depth();

                // Get appropriate cell based on depth and name components
                Cell<Monitors.Sign, Monitors.Sign> cell = getCellForName(entityName, depth);

                // Forward Sign to cell (Cell extends Pipe<I>)
                cell.emit(signal.sign());

                logger.debug(
                    "Forwarded {} Sign from '{}' (depth={}) to cell",
                    signal.sign(),
                    entityName,
                    depth
                );
            } catch (java.lang.Exception e) {
                logger.error(
                    "Failed to forward Monitor signal from subject: {}",
                    subject.name(),
                    e
                );
            }
        });
    }

    /**
     * Gets the appropriate cell for the given entity name.
     *
     * <p>Uses Name.depth() to determine hierarchy level, then Name.iterator()
     * to extract components in REVERSE order (deepest → root).
     *
     * @param entityName the entity name
     * @param depth the name depth (1=broker/cluster, 2=topic, 3=partition)
     * @return the cell at the specified hierarchy level
     */
    private Cell<Monitors.Sign, Monitors.Sign> getCellForName(Name entityName, int depth) {
        // Special case: "cluster" at depth 1
        if (depth == 1 && "cluster".equals(entityName.value())) {
            return hierarchy.getClusterCell();
        }

        // Extract components using iterator (returns REVERSE order: deepest → root)
        List<String> components = extractComponents(entityName);

        // Route based on depth
        return switch (depth) {
            case 1 -> {
                // Broker: "broker-1"
                String brokerId = components.get(0);
                yield hierarchy.getBrokerCell(brokerId);
            }
            case 2 -> {
                // Topic: "broker-1.orders"
                // components = [orders, broker-1] (reverse order)
                String topicName = components.get(0);
                String brokerId = components.get(1);
                yield hierarchy.getTopicCell(brokerId, topicName);
            }
            case 3 -> {
                // Partition: "broker-1.orders.p0"
                // components = [p0, orders, broker-1] (reverse order)
                String partitionId = components.get(0);
                String topicName = components.get(1);
                String brokerId = components.get(2);
                yield hierarchy.getPartitionCell(brokerId, topicName, partitionId);
            }
            default -> throw new IllegalArgumentException(
                "Invalid name depth: " + depth + " for name: " + entityName +
                ". Expected depth 1 (broker/cluster), 2 (topic), or 3 (partition)."
            );
        };
    }

    /**
     * Extracts name components using Name.iterator().
     *
     * <p>Note: Name.iterator() returns components in REVERSE order (deepest → root).
     * For "broker-1.orders.p0", returns ["p0", "orders", "broker-1"].
     *
     * @param name the name to extract components from
     * @return list of components in reverse order (deepest first)
     */
    private List<String> extractComponents(Name name) {
        List<String> components = new ArrayList<>();
        for (Name component : name) {
            components.add(component.value());
        }
        return components;
    }

    /**
     * Stops the bridge by unsubscribing from the Monitor conduit.
     *
     * <p>After calling close(), the bridge stops forwarding signals.
     * Call {@link #start()} to resume.
     */
    @Override
    public void close() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
            logger.info("MonitorCellBridge closed, unsubscribed from Monitor conduit");
        }
    }
}
