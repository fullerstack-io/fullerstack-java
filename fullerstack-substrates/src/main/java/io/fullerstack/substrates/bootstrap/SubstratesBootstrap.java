package io.fullerstack.substrates.bootstrap;

import io.fullerstack.substrates.config.CircuitDiscovery;
import io.fullerstack.substrates.config.HierarchicalConfig;
import io.fullerstack.substrates.spi.CircuitStructureProvider;
import io.fullerstack.substrates.spi.ComposerProvider;
import io.fullerstack.substrates.spi.ComposerProvider.ComponentType;
import io.fullerstack.substrates.spi.SensorProvider;
import io.fullerstack.substrates.spi.SensorProvider.Sensor;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Composer;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Name;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiConsumer;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static io.humainary.substrates.api.Substrates.Composer.pipe;

/**
 * Convention-based bootstrap for Substrates framework.
 * <p>
 * Automatically discovers and creates circuits from configuration files,
 * wiring them with application-provided composers and sensors via SPI.
 * <p>
 * <strong>Bootstrap Flow:</strong>
 * <ol>
 *   <li>Discover circuits from {@code config_{circuit}.properties} files</li>
 *   <li>Read circuit structure from {@code circuit.containers}, {@code circuit.conduits}, {@code circuit.cells}</li>
 *   <li>Load {@link ComposerProvider} implementations via {@link ServiceLoader}</li>
 *   <li>Load {@link SensorProvider} implementations via {@link ServiceLoader}</li>
 *   <li>Create circuits with containers, conduits, cells using provided composers</li>
 *   <li>Start sensors for each circuit</li>
 * </ol>
 * <p>
 * <strong>Usage (Zero Configuration):</strong>
 * <pre>
 * // Single line bootstrap - discovers everything automatically
 * BootstrapResult result = SubstratesBootstrap.bootstrap();
 *
 * // Access circuits
 * Circuit brokerHealth = result.getCircuit("broker-health");
 *
 * // Shutdown
 * result.close();
 * </pre>
 * <p>
 * <strong>Usage (Custom Configuration):</strong>
 * <pre>
 * BootstrapResult result = SubstratesBootstrap.builder()
 *     .onCircuitCreated((name, circuit) -&gt; {
 *         System.out.println("Created: " + name);
 *     })
 *     .onSensorStarted((circuitName, sensor) -&gt; {
 *         System.out.println("Started sensor: " + sensor.name());
 *     })
 *     .bootstrap();
 * </pre>
 *
 * @see ComposerProvider
 * @see SensorProvider
 * @see CircuitDiscovery
 */
public class SubstratesBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(SubstratesBootstrap.class);

    /**
     * Bootstrap all circuits with default configuration.
     * <p>
     * Discovers circuits, creates infrastructure, and starts sensors.
     *
     * @return Bootstrap result with circuits and sensors
     */
    public static BootstrapResult bootstrap() {
        return builder().bootstrap();
    }

    /**
     * Create a builder for custom bootstrap configuration.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for customizing bootstrap behavior.
     */
    public static class Builder {

        private BiConsumer<String, Circuit> onCircuitCreated = (name, circuit) -> {};
        private BiConsumer<String, Sensor> onSensorStarted = (circuit, sensor) -> {};
        private BiConsumer<String, Exception> onError = (name, error) -> {
            logger.error("Bootstrap error for circuit: {}", name, error);
        };

        /**
         * Set callback invoked when a circuit is created.
         *
         * @param callback Callback (circuitName, circuit) → void
         * @return This builder
         */
        public Builder onCircuitCreated(BiConsumer<String, Circuit> callback) {
            this.onCircuitCreated = Objects.requireNonNull(callback);
            return this;
        }

        /**
         * Set callback invoked when a sensor is started.
         *
         * @param callback Callback (circuitName, sensor) → void
         * @return This builder
         */
        public Builder onSensorStarted(BiConsumer<String, Sensor> callback) {
            this.onSensorStarted = Objects.requireNonNull(callback);
            return this;
        }

        /**
         * Set callback invoked when an error occurs.
         *
         * @param callback Callback (circuitName, exception) → void
         * @return This builder
         */
        public Builder onError(BiConsumer<String, Exception> callback) {
            this.onError = Objects.requireNonNull(callback);
            return this;
        }

        /**
         * Execute bootstrap process.
         *
         * @return Bootstrap result
         */
        public BootstrapResult bootstrap() {
            logger.info("Starting Substrates bootstrap...");

            // Phase 1: Discover circuits from configuration
            Set<String> circuitNames = CircuitDiscovery.discoverCircuits();
            logger.info("Discovered {} circuits: {}", circuitNames.size(), circuitNames);

            // Phase 2: Load application providers via SPI
            ServiceLoader<CircuitStructureProvider> structureProviders = ServiceLoader.load(CircuitStructureProvider.class);
            ServiceLoader<SensorProvider> sensorProviders = ServiceLoader.load(SensorProvider.class);

            logger.debug("Loaded structure providers: {}", countProviders(structureProviders));
            logger.debug("Loaded sensor providers: {}", countProviders(sensorProviders));

            // Phase 3: Create circuits
            Map<String, Circuit> circuits = new LinkedHashMap<>();
            List<Sensor> allSensors = new ArrayList<>();

            for (String circuitName : circuitNames) {
                try {
                    // Create circuit and build structure
                    Circuit circuit = createCircuit(circuitName, structureProviders);
                    circuits.put(circuitName, circuit);
                    onCircuitCreated.accept(circuitName, circuit);

                    // Get sensors for this circuit
                    List<Sensor> sensors = getSensorsForCircuit(circuitName, sensorProviders);
                    allSensors.addAll(sensors);

                    // Start sensors
                    for (Sensor sensor : sensors) {
                        sensor.start();
                        onSensorStarted.accept(circuitName, sensor);
                        logger.info("Started sensor '{}' for circuit '{}'", sensor.name(), circuitName);
                    }

                } catch (Exception e) {
                    onError.accept(circuitName, e);
                }
            }

            logger.info("Bootstrap complete: {} circuits, {} sensors", circuits.size(), allSensors.size());

            return new BootstrapResult(circuits, allSensors);
        }

        private Circuit createCircuit(String circuitName, ServiceLoader<CircuitStructureProvider> providers) {
            logger.debug("Creating circuit: {}", circuitName);

            HierarchicalConfig config = HierarchicalConfig.forCircuit(circuitName);

            // Get singleton Cortex instance
            Cortex cortex = cortex();

            // Create empty circuit
            Name name = cortex.name(circuitName);
            Circuit circuit = cortex.circuit(name);

            // Let application providers build structure (containers, conduits, cells)
            // Application knows the signal types, we don't!
            for (CircuitStructureProvider provider : providers) {
                provider.buildStructure(circuitName, circuit, cortex, config);
                logger.debug("Structure provider {} built structure for circuit '{}'",
                    provider.getClass().getSimpleName(), circuitName);
            }

            return circuit;
        }

        private List<Sensor> getSensorsForCircuit(String circuitName, ServiceLoader<SensorProvider> providers) {
            List<Sensor> sensors = new ArrayList<>();

            for (SensorProvider provider : providers) {
                List<Sensor> providerSensors = provider.getSensors(circuitName);
                sensors.addAll(providerSensors);
                logger.debug("Provider {} provided {} sensors for circuit '{}'",
                    provider.getClass().getSimpleName(), providerSensors.size(), circuitName);
            }

            return sensors;
        }

        private List<String> parseComponentList(String value) {
            if (value == null || value.isBlank()) {
                return Collections.emptyList();
            }

            return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }

        private int countProviders(ServiceLoader<?> loader) {
            int count = 0;
            for (var provider : loader) {
                count++;
            }
            return count;
        }
    }

    /**
     * Result of bootstrap process.
     * <p>
     * Contains all created circuits and started sensors.
     * Implements {@link AutoCloseable} to shut down all resources.
     */
    public static class BootstrapResult implements AutoCloseable {

        private final Map<String, Circuit> circuits;
        private final List<Sensor> sensors;

        public BootstrapResult(Map<String, Circuit> circuits, List<Sensor> sensors) {
            this.circuits = Map.copyOf(circuits);
            this.sensors = List.copyOf(sensors);
        }

        /**
         * Get all circuits by name.
         *
         * @return Unmodifiable map of circuit name → circuit
         */
        public Map<String, Circuit> getCircuits() {
            return circuits;
        }

        /**
         * Get a specific circuit by name.
         *
         * @param circuitName Circuit name
         * @return Circuit, or null if not found
         */
        public Circuit getCircuit(String circuitName) {
            return circuits.get(circuitName);
        }

        /**
         * Get all started sensors.
         *
         * @return Unmodifiable list of sensors
         */
        public List<Sensor> getSensors() {
            return sensors;
        }

        /**
         * Get circuit names.
         *
         * @return Set of circuit names
         */
        public Set<String> getCircuitNames() {
            return circuits.keySet();
        }

        /**
         * Close all circuits and sensors.
         * <p>
         * Stops all sensors and closes all circuits.
         */
        @Override
        public void close() throws Exception {
            logger.info("Shutting down {} circuits and {} sensors", circuits.size(), sensors.size());

            // Close sensors first
            for (Sensor sensor : sensors) {
                try {
                    sensor.close();
                    logger.debug("Closed sensor: {}", sensor.name());
                } catch (Exception e) {
                    logger.error("Error closing sensor: {}", sensor.name(), e);
                }
            }

            // Close circuits
            for (Map.Entry<String, Circuit> entry : circuits.entrySet()) {
                try {
                    entry.getValue().close();
                    logger.debug("Closed circuit: {}", entry.getKey());
                } catch (Exception e) {
                    logger.error("Error closing circuit: {}", entry.getKey(), e);
                }
            }

            logger.info("Shutdown complete");
        }

        @Override
        public String toString() {
            return "BootstrapResult[circuits=" + circuits.keySet() + ", sensors=" + sensors.size() + "]";
        }
    }
}
