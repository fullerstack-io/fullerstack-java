package io.fullerstack.kafka.broker.spi;

import io.fullerstack.substrates.config.HierarchicalConfig;
import io.fullerstack.substrates.spi.SensorProvider;
import io.humainary.substrates.api.Substrates.Cell;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Name;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.broker.sensors.BrokerMonitoringAgent;
import io.fullerstack.kafka.core.config.ClusterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * SPI provider for broker health monitoring sensors.
 * <p>
 * <b>IMPORTANT:</b> This provider returns an empty list because the SensorProvider SPI
 * interface only provides the circuit name, not the Circuit/Cortex instances needed
 * to create sensors that emit to Cells.
 * <p>
 * <b>Sensor Creation Pattern:</b>
 * Instead of using this SPI, broker monitoring sensors should be created manually:
 * <pre>{@code
 * // After bootstrap creates circuit structure
 * SubstratesBootstrap.Result result = SubstratesBootstrap.start();
 * Circuit brokerHealthCircuit = result.getCircuit("broker-health");
 *
 * // Create monitoring agent with Circuit reference
 * ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
 * BrokerMonitoringAgent agent = new BrokerMonitoringAgent(
 *     config,
 *     (name, metrics) -> {
 *         Cell<BrokerMetrics, MonitorSignal> cell = brokerHealthCircuit.cell(...);
 *         cell.emit(metrics);
 *     }
 * );
 * agent.start();
 * }</pre>
 * <p>
 * This manual approach gives full control over sensor lifecycle and circuit wiring.
 *
 * @see BrokerMonitoringAgent
 * @see io.fullerstack.substrates.bootstrap.SubstratesBootstrap
 */
public class BrokerHealthSensorProvider implements SensorProvider {
    private static final Logger logger = LoggerFactory.getLogger(BrokerHealthSensorProvider.class);

    @Override
    public List<Sensor> getSensors(String circuitName) {
        if (!"broker-health".equals(circuitName)) {
            return List.of();
        }

        // SensorProvider SPI doesn't provide Circuit/Cortex, so we can't create sensors here.
        // Sensors must be created manually after bootstrap - see class javadoc for pattern.
        logger.debug("BrokerHealthSensorProvider called for circuit '{}' - sensors created manually (see javadoc)", circuitName);

        return List.of();
    }
}
