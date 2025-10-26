package io.fullerstack.substrates.spi;

import java.util.Collections;
import java.util.List;

/**
 * Service Provider Interface for applications to provide sensors (signal emitters).
 * <p>
 * Sensors are components that emit signals into circuits. Applications implement this
 * interface to register sensors that should be started during circuit bootstrap.
 * <p>
 * <strong>Contract:</strong>
 * <ul>
 *   <li>Framework calls {@link #getSensors(String)} for each discovered circuit</li>
 *   <li>Application returns list of sensors to start</li>
 *   <li>Framework calls {@link Sensor#start()} on each sensor</li>
 *   <li>Framework calls {@link Sensor#close()} on shutdown</li>
 * </ul>
 * <p>
 * <strong>Example Implementation:</strong>
 * <pre>
 * public class MySensorProvider implements SensorProvider {
 *     &#64;Override
 *     public List&lt;Sensor&gt; getSensors(String circuitName) {
 *         if (circuitName.equals("broker-health")) {
 *             return List.of(
 *                 new JmxMonitoringSensor("localhost:9092"),
 *                 new MetricsCollectorSensor(5000)
 *             );
 *         }
 *         return List.of();
 *     }
 * }
 * </pre>
 * <p>
 * <strong>Registration:</strong>
 * Create file: {@code META-INF/services/io.fullerstack.substrates.spi.SensorProvider}
 * <pre>
 * com.example.MySensorProvider
 * </pre>
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
     *
     * @param circuitName Circuit name (e.g., "broker-health", "partition-flow")
     * @return List of sensors to start, or empty list if no sensors for this circuit
     */
    List<Sensor> getSensors(String circuitName);

    /**
     * Sensor interface - represents a signal emitter.
     * <p>
     * Sensors emit signals into circuits. They are started during bootstrap
     * and closed during shutdown.
     * <p>
     * <strong>Lifecycle:</strong>
     * <ol>
     *   <li>Framework calls {@link #start()}</li>
     *   <li>Sensor begins emitting signals</li>
     *   <li>Framework calls {@link #close()} on shutdown</li>
     * </ol>
     * <p>
     * <strong>Example Implementation:</strong>
     * <pre>
     * public class JmxMonitoringSensor implements Sensor {
     *     private final BrokerMonitoringAgent agent;
     *     private volatile boolean running;
     *
     *     public JmxMonitoringSensor(String bootstrapServers) {
     *         this.agent = new BrokerMonitoringAgent(bootstrapServers);
     *     }
     *
     *     &#64;Override
     *     public void start() {
     *         running = true;
     *         agent.start();
     *     }
     *
     *     &#64;Override
     *     public String name() {
     *         return "jmx-monitoring";
     *     }
     *
     *     &#64;Override
     *     public void close() throws Exception {
     *         running = false;
     *         agent.close();
     *     }
     * }
     * </pre>
     */
    interface Sensor extends AutoCloseable {

        /**
         * Start emitting signals.
         * <p>
         * Called by the framework after circuit bootstrap. Begin emitting
         * signals into the circuit.
         * <p>
         * <strong>Contract:</strong>
         * <ul>
         *   <li>Should be idempotent (safe to call multiple times)</li>
         *   <li>Should not block (start async processing)</li>
         *   <li>Should handle errors gracefully</li>
         * </ul>
         */
        void start();

        /**
         * Get sensor name.
         * <p>
         * Used for logging and debugging.
         *
         * @return Sensor name (e.g., "jmx-monitoring", "kafka-consumer-lag")
         */
        String name();

        /**
         * Stop emitting signals and release resources.
         * <p>
         * Called by the framework during shutdown. Stop all signal emission
         * and clean up resources.
         * <p>
         * <strong>Contract:</strong>
         * <ul>
         *   <li>Should be idempotent (safe to call multiple times)</li>
         *   <li>Should complete within reasonable time (use timeouts)</li>
         *   <li>Should not throw exceptions (log errors instead)</li>
         * </ul>
         *
         * @throws Exception if cleanup fails
         */
        @Override
        void close() throws Exception;
    }

    /**
     * Empty sensor provider - returns no sensors for any circuit.
     * <p>
     * Useful as a default implementation or for testing.
     */
    SensorProvider EMPTY = circuitName -> Collections.emptyList();
}
