package io.fullerstack.substrates.spi;

import io.fullerstack.substrates.bootstrap.BootstrapContext;
import io.fullerstack.substrates.config.HierarchicalConfig;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Cortex;

import java.util.Collections;
import java.util.List;

/**
 * Service Provider Interface for applications to provide sensors (signal emitters).
 * <p>
 * Sensors are components that emit signals into circuits. Applications implement this
 * interface to register sensors that should be started during circuit bootstrap.
 * <p>
 * < strong >Contract:</strong >
 * < ul >
 * < li >Framework calls {@link #getSensors(String, Circuit, Cortex, HierarchicalConfig)} for each discovered circuit</li >
 * < li >Application creates sensors with access to Circuit for signal emission</li >
 * < li >Framework calls {@link Sensor#start()} on each sensor</li >
 * < li >Framework calls {@link Sensor#close()} on shutdown</li >
 * </ul >
 * <p>
 * < strong >Example Implementation:</strong >
 * < pre >
 * public class MySensorProvider implements SensorProvider {
 * &#64;Override
 * public List&lt;Sensor&gt; getSensors(
 * String circuitName,
 * Circuit circuit,
 * Cortex cortex,
 * HierarchicalConfig config
 * ) {
 * if (circuitName.equals("broker-health")) {
 * // Load config
 * String bootstrapServers = config.getString("kafka.bootstrap.servers");
 * String jmxEndpoint = config.getString("kafka.jmx.endpoint");
 * <p>
 * // Create sensor that emits to circuit
 * return List.of(
 * new BrokerMonitoringSensor(circuit, bootstrapServers, jmxEndpoint)
 * );
 * }
 * return List.of();
 * }
 * }
 * </pre >
 * <p>
 * < strong >Registration:</strong >
 * Create file: {@code META-INF/services/io.fullerstack.substrates.spi.SensorProvider}
 * < pre >
 * com.example.MySensorProvider
 * </pre >
 *
 * @see java.util.ServiceLoader
 * @see Sensor
 */
public interface SensorProvider {

  /**
   * Get sensors for a specific circuit.
   * <p>
   * Called by the framework during circuit bootstrap. Return all sensors
   * that should emit signals into this circuit.
   * <p>
   * The Circuit, Cortex, HierarchicalConfig, and BootstrapContext are provided so sensors can:
   * < ul >
   * < li >< b >Retrieve components</b > registered by CircuitStructureProvider (e.g., {@code context.get("brokers-cell", Cell.class)})</li >
   * < li >Access cells/conduits for signal emission (circuit.cell(), circuit.conduit())</li >
   * < li >Create names (cortex.name())</li >
   * < li >Load configuration (config.getString(), config.getInt())</li >
   * </ul >
   *
   * @param circuitName Circuit name (e.g., "broker-health", "partition-flow")
   * @param circuit     Circuit instance for signal emission
   * @param cortex      Cortex instance for creating Names
   * @param config      Configuration for this circuit
   * @param context     Bootstrap context for retrieving registered components (Service Registry pattern)
   * @return List of sensors to start, or empty list if no sensors for this circuit
   */
  List < Sensor > getSensors (
    String circuitName,
    Circuit circuit,
    Cortex cortex,
    HierarchicalConfig config,
    BootstrapContext context
  );


  /**
   * Sensor interface - represents a signal emitter.
   * <p>
   * Sensors emit signals into circuits. They are started during bootstrap
   * and closed during shutdown.
   * <p>
   * < strong >Lifecycle:</strong >
   * < ol >
   * < li >Framework calls {@link #start()}</li >
   * < li >Sensor begins emitting signals</li >
   * < li >Framework calls {@link #close()} on shutdown</li >
   * </ol >
   * <p>
   * < strong >Example Implementation:</strong >
   * < pre >
   * public class JmxMonitoringSensor implements Sensor {
   * private final BrokerMonitoringAgent agent;
   * private volatile boolean running;
   * <p>
   * public JmxMonitoringSensor(String bootstrapServers) {
   * this.agent = new BrokerMonitoringAgent(bootstrapServers);
   * }
   * <p>
   * &#64;Override
   * public void start() {
   * running = true;
   * agent.start();
   * }
   * <p>
   * &#64;Override
   * public String name() {
   * return "jmx-monitoring";
   * }
   * <p>
   * &#64;Override
   * public void close() throws Exception {
   * running = false;
   * agent.close();
   * }
   * }
   * </pre >
   */
  interface Sensor extends AutoCloseable {

    /**
     * Start emitting signals.
     * <p>
     * Called by the framework after circuit bootstrap. Begin emitting
     * signals into the circuit.
     * <p>
     * < strong >Contract:</strong >
     * < ul >
     * < li >Should be idempotent (safe to call multiple times)</li >
     * < li >Should not block (start async processing)</li >
     * < li >Should handle errors gracefully</li >
     * </ul >
     */
    void start ();


    /**
     * Get sensor name.
     * <p>
     * Used for logging and debugging.
     *
     * @return Sensor name (e.g., "jmx-monitoring", "kafka-consumer-lag")
     */
    String name ();


    /**
     * Stop emitting signals and release resources.
     * <p>
     * Called by the framework during shutdown. Stop all signal emission
     * and clean up resources.
     * <p>
     * < strong >Contract:</strong >
     * < ul >
     * < li >Should be idempotent (safe to call multiple times)</li >
     * < li >Should complete within reasonable time (use timeouts)</li >
     * < li >Should not throw exceptions (log errors instead)</li >
     * </ul >
     *
     * @throws Exception if cleanup fails
     */
    @Override
    void close () throws Exception;
  }

  /**
   * Empty sensor provider - returns no sensors for any circuit.
   * <p>
   * Useful as a default implementation or for testing.
   */
  SensorProvider EMPTY = ( circuitName, circuit, cortex, config, context ) -> Collections.emptyList ();
}
