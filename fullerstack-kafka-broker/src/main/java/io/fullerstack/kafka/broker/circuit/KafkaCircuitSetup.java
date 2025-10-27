package io.fullerstack.kafka.broker.circuit;

import io.fullerstack.serventis.signals.MonitorSignal;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.kafka.broker.composers.BrokerHealthCellComposer;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.broker.sensors.BrokerMonitoringAgent;
import io.fullerstack.kafka.core.config.ClusterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Sets up Circuit and Cell hierarchy for AWS MSK cluster monitoring.
 * <p>
 * **Circuit Placement Strategy**: One Circuit per MSK Cluster
 * <p>
 * Creates hierarchical structure:
 * <pre>
 * Circuit: "prod-account.us-east-1.transactions-cluster"
 * │
 * └─ clusterCell (Root Cell)
 *    ├── broker-1 (Child Cell)
 *    ├── broker-2 (Child Cell)
 *    └── broker-3 (Child Cell)
 * </pre>
 * <p>
 * Each MSK cluster gets its own isolated Circuit for:
 * - Operational isolation between clusters
 * - Independent lifecycle management
 * - Regional deployment optimization
 * - Simplified debugging
 * <p>
 * Usage:
 * <pre>
 * ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
 * KafkaCircuitSetup setup = new KafkaCircuitSetup(config);
 *
 * // Get cluster Cell - subscribe to receive all broker signals
 * Cell&lt;BrokerMetrics, MonitorSignal&gt; clusterCell = setup.getClusterCell();
 * clusterCell.subscribe(...);
 *
 * // Get specific broker Cell for emitting metrics
 * Cell&lt;BrokerMetrics, MonitorSignal&gt; broker1 = setup.getBrokerCell("b-1");
 * broker1.emit(brokerMetrics);
 *
 * // Cleanup
 * setup.close();
 * </pre>
 *
 * @see io.kafkaobs.core.config.ClusterConfig
 * @see <a href="../../../../../../../docs/architecture/circuit-architecture.md">Circuit Architecture</a>
 */
public class KafkaCircuitSetup implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(KafkaCircuitSetup.class);

    private final ClusterConfig config;
    private final Cortex cortex;
    private final Circuit circuit;
    private final Name clusterName;
    private final List<String> brokerIds;
    private final Cell<BrokerMetrics, MonitorSignal> clusterCell;
    private final List<Cell<BrokerMetrics, MonitorSignal>> brokerCells;

    /**
     * Create a new KafkaCircuitSetup with Circuit for the given cluster.
     * <p>
     * Creates:
     * - Cortex runtime instance
     * - Circuit scoped to this cluster
     * - Hierarchical cluster Name
     * - Cluster Cell with BrokerHealthCellComposer
     * - Broker child Cells for each broker in bootstrapServers
     *
     * @param config AWS MSK cluster configuration
     */
    public KafkaCircuitSetup(ClusterConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");

        logger.info("Initializing KafkaCircuitSetup for cluster: {}.{}.{}",
                config.accountName(), config.regionName(), config.clusterName());

        this.cortex = cortex();

        // Create hierarchical cluster Name (account.region.cluster)
        this.clusterName = config.getClusterName(cortex);

        // Create Circuit with cluster Name as identifier
        // One Circuit per MSK cluster for operational isolation
        this.circuit = cortex.circuit(clusterName);

        logger.debug("Created Circuit with name: {}", clusterName);

        // Parse bootstrap servers to extract broker IDs
        this.brokerIds = parseBrokerIds(config.bootstrapServers());

        // Create BrokerHealthCellComposer (M18 pattern - no-arg constructor)
        BrokerHealthCellComposer composer = new BrokerHealthCellComposer();

        // Create cluster-level Cell with Composer
        // circuit.cell() requires: Composer<Pipe<I>, E> and Pipe<E>
        this.clusterCell = circuit.cell(composer, Pipe.empty());

        logger.debug("Created cluster Cell with BrokerHealthCellComposer");

        // Create broker child cells from bootstrap servers
        this.brokerCells = createBrokerCells();

        logger.info("KafkaCircuitSetup initialized with {} broker cells", brokerCells.size());
    }

    /**
     * Parse bootstrap servers to extract broker IDs.
     * <p>
     * Example:
     * <pre>
     * Input: "b-1.kafka.us-east-1.amazonaws.com:9092,b-2.kafka.us-east-1.amazonaws.com:9092"
     * Output: ["b-1", "b-2"]
     * </pre>
     *
     * @param bootstrapServers Comma-separated broker endpoints
     * @return List of broker IDs
     */
    private List<String> parseBrokerIds(String bootstrapServers) {
        List<String> ids = new ArrayList<>();

        String[] endpoints = bootstrapServers.split(",");
        for (String endpoint : endpoints) {
            String hostname = endpoint.split(":")[0].trim();  // Remove port
            String brokerId = ClusterConfig.extractBrokerId(hostname);
            ids.add(brokerId);
        }

        return ids;
    }

    /**
     * Creates child Cells for each broker in the bootstrap servers configuration.
     * <p>
     * Parses the comma-separated bootstrap servers, extracts broker IDs,
     * and creates hierarchical child Cells that inherit the BrokerHealthCellComposer.
     *
     * @return List of broker Cells
     */
    private List<Cell<BrokerMetrics, MonitorSignal>> createBrokerCells() {
        List<Cell<BrokerMetrics, MonitorSignal>> cells = new ArrayList<>();

        for (String brokerId : brokerIds) {
            // Create child Cell with broker ID as name
            // The child inherits the Composer from parent clusterCell
            Name brokerName = cortex.name(brokerId);
            Cell<BrokerMetrics, MonitorSignal> brokerCell = clusterCell.get(brokerName);

            cells.add(brokerCell);

            logger.debug("Created broker Cell: {}", brokerId);
        }

        return cells;
    }

    /**
     * Get the Cortex instance used by this setup.
     *
     * @return Cortex instance
     */
    public Cortex getCortex() {
        return cortex;
    }

    /**
     * Get the cluster configuration.
     *
     * @return ClusterConfig
     */
    public ClusterConfig getConfig() {
        return config;
    }

    /**
     * Get the hierarchical cluster Name.
     * <p>
     * Example: "prod-account.us-east-1.transactions-cluster"
     *
     * @return Cluster Name (3-level hierarchy: account.region.cluster)
     */
    public Name getClusterName() {
        return clusterName;
    }

    /**
     * Get list of broker IDs parsed from bootstrap servers.
     * <p>
     * Example: ["b-1", "b-2", "b-3"]
     *
     * @return List of broker IDs
     */
    public List<String> getBrokerIds() {
        return new ArrayList<>(brokerIds);  // Defensive copy
    }

    /**
     * Get hierarchical Name for a specific broker.
     * <p>
     * Example: "prod-account.us-east-1.transactions-cluster.b-1"
     *
     * @param brokerId Broker identifier (e.g., "b-1", "b-2")
     * @return Broker Name (4-level hierarchy: account.region.cluster.broker)
     */
    public Name getBrokerName(String brokerId) {
        Objects.requireNonNull(brokerId, "brokerId cannot be null");

        // Normalize broker ID (in case full hostname passed)
        String normalizedBrokerId = ClusterConfig.extractBrokerId(brokerId);

        // Use config helper to create full 4-level hierarchy
        return config.getBrokerName(cortex, normalizedBrokerId);
    }

    /**
     * Check if a broker ID exists in this cluster.
     *
     * @param brokerId Broker identifier
     * @return true if broker exists in cluster
     */
    public boolean hasBroker(String brokerId) {
        String normalizedBrokerId = ClusterConfig.extractBrokerId(brokerId);
        return brokerIds.contains(normalizedBrokerId);
    }

    /**
     * Get count of brokers in this cluster.
     *
     * @return Number of brokers
     */
    public int getBrokerCount() {
        return brokerIds.size();
    }

    /**
     * Get the Circuit instance for this cluster.
     * <p>
     * Each MSK cluster gets its own isolated Circuit for creating Cells.
     * The Circuit is ready to use immediately - no explicit start() needed.
     *
     * @return Circuit scoped to this MSK cluster
     */
    public Circuit getCircuit() {
        return circuit;
    }

    /**
     * Returns the cluster-level Cell.
     * <p>
     * Subscribe to this Cell to receive MonitorSignals from ALL brokers.
     *
     * @return Cluster Cell that aggregates all broker signals
     */
    public Cell<BrokerMetrics, MonitorSignal> getClusterCell() {
        return clusterCell;
    }

    /**
     * Returns a specific broker Cell by broker ID.
     * <p>
     * Use this to emit metrics for a specific broker.
     *
     * @param brokerId Broker identifier (e.g., "b-1", "b-2")
     * @return Broker Cell, or creates new one if not found
     */
    public Cell<BrokerMetrics, MonitorSignal> getBrokerCell(String brokerId) {
        Objects.requireNonNull(brokerId, "brokerId cannot be null");

        String normalizedBrokerId = ClusterConfig.extractBrokerId(brokerId);

        // Get or create broker cell (clusterCell.get() creates if not exists)
        Name brokerName = cortex.name(normalizedBrokerId);
        Cell<BrokerMetrics, MonitorSignal> brokerCell = clusterCell.get(brokerName);

        // Add to list if newly created
        if (!brokerCells.contains(brokerCell)) {
            brokerCells.add(brokerCell);
            logger.debug("Created new broker Cell: {}", normalizedBrokerId);
        }

        return brokerCell;
    }

    /**
     * Returns all broker Cells.
     *
     * @return Immutable list of broker Cells
     */
    public List<Cell<BrokerMetrics, MonitorSignal>> getBrokerCells() {
        return List.copyOf(brokerCells);
    }

    /**
     * Create a BrokerMonitoringAgent wired to emit metrics to broker Cells.
     * <p>
     * The agent will:
     * - Collect JMX metrics from all brokers in the cluster
     * - Emit BrokerMetrics to the appropriate broker Cell
     * - Maintain VectorClock for causal ordering
     * <p>
     * Usage:
     * <pre>
     * BrokerMonitoringAgent agent = setup.createMonitoringAgent();
     * agent.start();
     * // ... monitoring runs ...
     * agent.shutdown();
     * </pre>
     *
     * @return BrokerMonitoringAgent configured to emit to broker Cells
     */
    public BrokerMonitoringAgent createMonitoringAgent() {
        // Create emitter that emits BrokerMetrics to the appropriate broker Cell
        // The emitter receives (brokerName, metrics) and routes to correct Cell
        return new BrokerMonitoringAgent(config, (brokerName, metrics) -> {
            // Extract broker ID from hierarchical Name
            // brokerName format: "account.region.cluster.brokerId"
            String brokerId = ClusterConfig.extractBrokerId(metrics.brokerId());

            // Get or create broker Cell
            Cell<BrokerMetrics, MonitorSignal> brokerCell = getBrokerCell(brokerId);

            // Emit metrics to Cell - BrokerHealthCellComposer will transform to MonitorSignal
            brokerCell.emit(metrics);

            logger.trace("Emitted metrics for broker {} to Cell", brokerId);
        });
    }

    /**
     * Closes the Circuit and releases resources.
     * <p>
     * Should be called when monitoring is complete.
     */
    @Override
    public void close() {
        logger.info("Closing KafkaCircuitSetup for cluster: {}.{}.{}",
                config.accountName(), config.regionName(), config.clusterName());

        if (circuit != null) {
            circuit.close();
        }

        logger.debug("KafkaCircuitSetup closed");
    }
}
