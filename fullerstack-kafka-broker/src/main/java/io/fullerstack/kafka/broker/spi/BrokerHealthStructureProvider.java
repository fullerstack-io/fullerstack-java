package io.fullerstack.kafka.broker.spi;

import io.fullerstack.serventis.signals.MonitorSignal;
import io.fullerstack.substrates.bootstrap.BootstrapContext;
import io.fullerstack.substrates.config.HierarchicalConfig;
import io.fullerstack.substrates.spi.CircuitStructureProvider;
import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.kafka.broker.composers.BrokerHealthCellComposer;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SPI provider that builds the broker health monitoring circuit structure.
 * <p>
 * Creates a hierarchical Cell structure for monitoring Kafka brokers:
 * <pre>
 * Circuit (broker-health)
 *   └── Cell<BrokerMetrics, MonitorSignal> (cluster cell)
 *       ├── Cell (broker-1) - inherits Composer
 *       ├── Cell (broker-2) - inherits Composer
 *       └── Cell (broker-3) - inherits Composer
 * </pre>
 * <p>
 * The structure is built once at bootstrap, broker cells are created dynamically by sensors.
 *
 * @see BrokerHealthCellComposer
 * @see CircuitStructureProvider
 */
public class BrokerHealthStructureProvider implements CircuitStructureProvider {
    private static final Logger logger = LoggerFactory.getLogger(BrokerHealthStructureProvider.class);

    @Override
    public void buildStructure(
            String circuitName,
            Circuit circuit,
            Cortex cortex,
            HierarchicalConfig config,
            BootstrapContext context
    ) {
        if (!"broker-health".equals(circuitName)) {
            return;  // Not our circuit
        }

        logger.info("Building broker-health circuit structure...");

        // Create cluster-level cell with health assessment composer
        // Composer loads its own configuration from HierarchicalConfig
        BrokerHealthCellComposer composer = new BrokerHealthCellComposer();

        // Create cell with composer - M18 API: cell(Composer, Pipe)
        Cell<BrokerMetrics, MonitorSignal> clusterCell = circuit.cell(
                composer,
                Pipe.empty()
        );

        // Register cell in context so SensorProvider can retrieve it
        context.register("cluster-cell", clusterCell);

        logger.info("Created cluster cell with BrokerHealthCellComposer (config-driven thresholds)");
        logger.info("Registered 'cluster-cell' in BootstrapContext for sensor access");

        // Broker cells will be created dynamically by sensors when they discover brokers
        // via clusterCell.get(cortex.name(brokerId)) pattern

        logger.info("Broker-health circuit structure built successfully");
    }
}
