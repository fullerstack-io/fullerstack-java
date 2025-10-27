package io.fullerstack.kafka.broker.spi;

import io.fullerstack.serventis.signals.MonitorSignal;
import io.fullerstack.substrates.config.HierarchicalConfig;
import io.fullerstack.substrates.spi.SensorProvider;
import io.humainary.substrates.api.Substrates.Cell;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Cortex;
import io.fullerstack.kafka.broker.composers.BrokerHealthCellComposer;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.broker.sensors.BrokerMonitoringAgent;
import io.fullerstack.kafka.core.config.ClusterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * SPI provider for broker health monitoring sensors.
 * <p>
 * Creates {@link BrokerMonitoringAgent} instances that:
 * <ul>
 *   <li>Collect JMX metrics from Kafka brokers</li>
 *   <li>Emit {@link BrokerMetrics} to broker cells</li>
 *   <li>Transform metrics to {@link MonitorSignal} via {@link BrokerHealthCellComposer}</li>
 * </ul>
 * <p>
 * <b>Configuration:</b> Reads from {@code config_broker-health.properties}:
 * <pre>
 * kafka.bootstrap.servers=localhost:9092
 * kafka.jmx.url=localhost:11001
 * jmx.collection.interval.ms=30000
 * </pre>
 * <p>
 * <b>Bootstrap Workflow:</b>
 * <ol>
 *   <li>SubstratesBootstrap discovers broker-health circuit</li>
 *   <li>BrokerHealthStructureProvider creates cluster cell with BrokerHealthCellComposer</li>
 *   <li>BrokerHealthSensorProvider creates BrokerMonitoringAgent sensor</li>
 *   <li>SubstratesBootstrap calls sensor.start() to begin monitoring</li>
 *   <li>Agent collects JMX metrics and emits to broker cells</li>
 *   <li>BrokerHealthCellComposer transforms BrokerMetrics → MonitorSignal</li>
 * </ol>
 *
 * @see BrokerMonitoringAgent
 * @see BrokerHealthCellComposer
 * @see io.fullerstack.substrates.bootstrap.SubstratesBootstrap
 */
public class BrokerHealthSensorProvider implements SensorProvider {
    private static final Logger logger = LoggerFactory.getLogger(BrokerHealthSensorProvider.class);

    @Override
    public List<Sensor> getSensors(
            String circuitName,
            Circuit circuit,
            Cortex cortex,
            HierarchicalConfig config
    ) {
        if (!"broker-health".equals(circuitName)) {
            return List.of();
        }

        logger.info("Creating broker health monitoring sensor for circuit '{}'", circuitName);

        // Load Kafka configuration
        String bootstrapServers = config.getString("kafka.bootstrap.servers", "localhost:9092");
        String jmxUrl = config.getString("kafka.jmx.url", "localhost:11001");
        String clusterName = config.getString("kafka.cluster.name", "local-dev");
        int collectionInterval = config.getInt("jmx.collection.interval.ms", 30000);

        // Create ClusterConfig
        ClusterConfig clusterConfig = ClusterConfig.withDefaults(bootstrapServers, jmxUrl);

        // Get cluster cell (created by BrokerHealthStructureProvider)
        // The cell has BrokerHealthCellComposer which transforms BrokerMetrics → MonitorSignal
        Cell<BrokerMetrics, MonitorSignal> clusterCell = circuit.cell(
                new BrokerHealthCellComposer(),
                io.humainary.substrates.api.Substrates.Pipe.empty()
        );

        // Create monitoring agent
        BrokerMonitoringAgent agent = new BrokerMonitoringAgent(
                clusterConfig,
                (brokerName, metrics) -> {
                    // Extract broker ID from hierarchical Name
                    String brokerId = ClusterConfig.extractBrokerId(metrics.brokerId());

                    // Get broker cell (child of cluster cell)
                    Cell<BrokerMetrics, MonitorSignal> brokerCell = clusterCell.get(
                            cortex.name(brokerId)
                    );

                    // Emit metrics - BrokerHealthCellComposer transforms to MonitorSignal
                    brokerCell.emit(metrics);

                    logger.trace("Emitted metrics for broker {} to cell", brokerId);
                }
        );

        // Wrap agent in Sensor interface
        Sensor sensor = new Sensor() {
            @Override
            public void start() {
                logger.info("Starting broker monitoring agent for cluster '{}'", clusterName);
                agent.start();
            }

            @Override
            public String name() {
                return "broker-health-monitor";
            }

            @Override
            public void close() throws Exception {
                logger.info("Stopping broker monitoring agent for cluster '{}'", clusterName);
                agent.shutdown();
            }
        };

        logger.info("Created broker health monitoring sensor (cluster: {}, bootstrap: {}, jmx: {}, interval: {}ms)",
                clusterName, bootstrapServers, jmxUrl, collectionInterval);

        return List.of(sensor);
    }
}
