package io.fullerstack.substrates.spi;

import io.fullerstack.substrates.bootstrap.BootstrapContext;
import io.fullerstack.substrates.config.HierarchicalConfig;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Cortex;

/**
 * Service Provider Interface for applications to build circuit structure.
 * <p>
 * Since the framework doesn't know signal types at compile time, applications
 * must build containers, conduits, and cells themselves with proper types.
 * <p>
 * <strong>Contract:</strong>
 * <ul>
 *   <li>Framework creates empty circuit</li>
 *   <li>Framework calls {@link #buildStructure} with circuit and config</li>
 *   <li>Application creates typed containers, conduits, cells</li>
 *   <li>Application can read structure from {@code circuit.containers}, {@code circuit.conduits}, {@code circuit.cells} in config</li>
 * </ul>
 * <p>
 * <strong>Example Implementation:</strong>
 * <pre>
 * public class MyStructureProvider implements CircuitStructureProvider {
 *     &#64;Override
 *     public void buildStructure(String circuitName, Circuit circuit, Cortex cortex, HierarchicalConfig config) {
 *         if (circuitName.equals("broker-health")) {
 *             // Create typed cell
 *             Composer&lt;Pipe&lt;BrokerMetrics&gt;, MonitorSignal&gt; composer = new BrokerHealthCellComposer();
 *             Cell&lt;BrokerMetrics, MonitorSignal&gt; cell = circuit.cell(
 *                 composer,
 *                 Pipe.empty()
 *             );
 *
 *             // Create typed conduits for signal routing
 *             Conduit&lt;MonitorSignal, MonitorSignal&gt; metrics = circuit.conduit(
 *                 cortex.name("metrics"),
 *                 Composer.pipe()
 *             );
 *         }
 *     }
 * }
 * </pre>
 * <p>
 * <strong>Hierarchical Cells Example:</strong>
 * <pre>
 * // Create parent cell
 * Cell&lt;BrokerMetrics, MonitorSignal&gt; clusterCell = circuit.cell(
 *     new BrokerHealthCellComposer(),
 *     Pipe.empty()
 * );
 *
 * // Create child cells using Container.get() (Cell IS-A Container)
 * Cell&lt;BrokerMetrics, MonitorSignal&gt; broker1 = clusterCell.get(
 *     cortex.name("broker-1")
 * );
 *
 * Cell&lt;BrokerMetrics, MonitorSignal&gt; broker2 = clusterCell.get(
 *     cortex.name("broker-2")
 * );
 *
 * // Now have hierarchy:
 * // clusterCell
 * //   ├── broker-1
 * //   └── broker-2
 * </pre>
 * <p>
 * <strong>Registration:</strong>
 * Create file: {@code META-INF/services/io.fullerstack.substrates.spi.CircuitStructureProvider}
 * <pre>
 * com.example.MyStructureProvider
 * </pre>
 *
 * @see java.util.ServiceLoader
 * @see io.humainary.substrates.api.Substrates.Circuit
 */
public interface CircuitStructureProvider {

    /**
     * Build structure for a circuit.
     * <p>
     * Called by the framework after circuit creation. Application creates
     * containers, conduits, and cells with proper signal types.
     * <p>
     * <strong>Implementation Guidelines:</strong>
     * <ul>
     *   <li>Read structure from config ({@code circuit.containers}, {@code circuit.conduits}, {@code circuit.cells})</li>
     *   <li>Create typed components using the provided circuit and cortex</li>
     *   <li>Build hierarchical cell structures as needed</li>
     *   <li><b>Register components</b> in context for SensorProvider access (e.g., {@code context.register("brokers-cell", cell)})</li>
     *   <li>Handle unknown circuit names gracefully (return without action)</li>
     * </ul>
     *
     * @param circuitName Circuit name (e.g., "broker-health", "partition-flow")
     * @param circuit Empty circuit to build structure in
     * @param cortex Cortex instance for creating names
     * @param config Circuit-specific hierarchical configuration
     * @param context Bootstrap context for registering components (Service Registry pattern)
     */
    void buildStructure(
        String circuitName,
        Circuit circuit,
        Cortex cortex,
        HierarchicalConfig config,
        BootstrapContext context
    );
}
