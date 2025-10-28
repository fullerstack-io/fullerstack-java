package io.fullerstack.kafka.runtime;

import io.fullerstack.kafka.broker.composers.BrokerHealthCellComposer;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.broker.sensors.BrokerSensor;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.fullerstack.kafka.core.config.ProducerSensorConfig;
import io.fullerstack.kafka.producer.composers.ProducerHealthCellComposer;
import io.fullerstack.kafka.producer.models.ProducerMetrics;
import io.fullerstack.kafka.producer.sensors.ProducerSensor;
import io.fullerstack.serventis.signals.MonitorSignal;
import io.humainary.substrates.api.Substrates.Cell;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Unified runtime for Kafka observability.
 * <p>
 * This runtime owns ALL Circuit and Cell hierarchy management, and manages
 * the lifecycle of all sensors (BrokerSensor, ProducerSensor).
 * <p>
 * <b>Architecture:</b>
 * <pre>
 * KafkaObservabilityRuntime
 *   ├── ONE Cortex (per JVM)
 *   ├── ONE Circuit per cluster
 *   ├── Cell hierarchy:
 *   │   ├── brokers/ (Container&lt;MonitorSignal&gt;)
 *   │   │   ├── broker-1 (Cell&lt;BrokerMetrics, MonitorSignal&gt;)
 *   │   │   ├── broker-2
 *   │   │   └── broker-3
 *   │   └── producers/ (Container&lt;MonitorSignal&gt;)
 *   │       ├── producer-1 (Cell&lt;ProducerMetrics, MonitorSignal&gt;)
 *   │       └── producer-2
 *   └── Sensors (just collect, no Circuit knowledge):
 *       ├── BrokerSensor → emits to brokers/
 *       └── ProducerSensor → emits to producers/
 * </pre>
 * <p>
 * <b>Responsibilities:</b>
 * - Create and manage Cortex + Circuit
 * - Build Cell hierarchy (brokers/, producers/)
 * - Instantiate and wire sensors with emission callbacks
 * - Route sensor emissions to correct Cells
 * - Expose root Containers for Epic 2 subscribers
 * <p>
 * <b>Usage:</b>
 * <pre>
 * KafkaObsConfig config = ...;
 * KafkaObservabilityRuntime runtime = new KafkaObservabilityRuntime(config);
 * runtime.start();
 *
 * // Epic 2: Subscribe to broker health signals
 * runtime.getBrokersContainer().subscribe(signal -> {...});
 *
 * // ... later ...
 * runtime.close();
 * </pre>
 */
public class KafkaObservabilityRuntime implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(KafkaObservabilityRuntime.class);

    private final KafkaObsConfig config;
    private final Cortex cortex;
    private final Circuit circuit;

    // Cell hierarchy roots
    private final Cell<BrokerMetrics, MonitorSignal> brokersRootCell;
    private final Cell<ProducerMetrics, MonitorSignal> producersRootCell;

    // Sensors (just collect)
    private final BrokerSensor brokerSensor;
    private final ProducerSensor producerSensor;

    private volatile boolean started = false;
    private volatile boolean closed = false;

    /**
     * Create KafkaObservabilityRuntime.
     * <p>
     * Initializes Circuit/Cell hierarchy and sensors, but does NOT start collection.
     * Call {@link #start()} to begin metric collection.
     *
     * @param config Runtime configuration with cluster and sensor configs
     */
    public KafkaObservabilityRuntime(KafkaObsConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");

        // Create ONE Cortex (singleton per JVM)
        this.cortex = cortex();
        logger.info("Cortex created for runtime");

        // Create ONE Circuit per cluster
        Name circuitName = cortex.name("kafka.cluster." + config.clusterName());
        this.circuit = cortex.circuit(circuitName);
        logger.info("Circuit created: {}", circuitName);

        // Create Cell hierarchy
        this.brokersRootCell = createBrokersRootCell();
        this.producersRootCell = createProducersRootCell();

        // Create sensors with emission callbacks
        this.brokerSensor = createBrokerSensor();
        this.producerSensor = createProducerSensor();

        logger.info("KafkaObservabilityRuntime initialized for cluster: {}", config.clusterName());
    }

    /**
     * Create brokers/ root Cell with BrokerHealthCellComposer.
     *
     * @return Root Cell for broker health monitoring
     */
    private Cell<BrokerMetrics, MonitorSignal> createBrokersRootCell() {
        BrokerHealthCellComposer composer = new BrokerHealthCellComposer(config.healthThresholds());
        Cell<BrokerMetrics, MonitorSignal> rootCell = circuit.cell(composer, Pipe.empty());
        logger.info("Created brokers/ root Cell in circuit");

        // Pre-create broker child Cells from config
        for (var endpoint : config.brokerSensorConfig().endpoints()) {
            String brokerId = endpoint.brokerId();
            Name brokerName = cortex.name(brokerId);
            Cell<BrokerMetrics, MonitorSignal> brokerCell = rootCell.get(brokerName);
            logger.debug("Pre-created broker Cell: {}", brokerId);
        }

        return rootCell;
    }

    /**
     * Create producers/ root Cell with ProducerHealthCellComposer.
     *
     * @return Root Cell for producer health monitoring
     */
    private Cell<ProducerMetrics, MonitorSignal> createProducersRootCell() {
        ProducerHealthCellComposer composer = new ProducerHealthCellComposer();
        Cell<ProducerMetrics, MonitorSignal> rootCell = circuit.cell(composer, Pipe.empty());
        logger.info("Created producers/ root Cell in circuit");

        // Pre-create producer child Cells from config
        for (var endpoint : config.producerSensorConfig().endpoints()) {
            String producerId = endpoint.producerId();
            Name producerName = cortex.name(producerId);
            Cell<ProducerMetrics, MonitorSignal> producerCell = rootCell.get(producerName);
            logger.debug("Pre-created producer Cell: {}", producerId);
        }

        return rootCell;
    }

    /**
     * Create BrokerSensor wired to emit to brokers/ root Cell.
     *
     * @return Configured BrokerSensor
     */
    private BrokerSensor createBrokerSensor() {
        BrokerSensorConfig sensorConfig = config.brokerSensorConfig();

        // Callback: route emissions to correct broker Cell
        return new BrokerSensor(sensorConfig, (brokerId, metrics) -> {
            Name brokerName = cortex.name(brokerId);
            Cell<BrokerMetrics, MonitorSignal> brokerCell = brokersRootCell.get(brokerName);

            if (brokerCell != null) {
                brokerCell.emit(metrics);
                logger.trace("Emitted BrokerMetrics to Cell: {}", brokerId);
            } else {
                logger.warn("No Cell found for broker: {}", brokerId);
            }
        });
    }

    /**
     * Create ProducerSensor wired to emit to producers/ root Cell.
     *
     * @return Configured ProducerSensor
     */
    private ProducerSensor createProducerSensor() {
        ProducerSensorConfig sensorConfig = config.producerSensorConfig();

        // Callback: route emissions to correct producer Cell
        return new ProducerSensor(sensorConfig, (producerId, metrics) -> {
            Name producerName = cortex.name(producerId);
            Cell<ProducerMetrics, MonitorSignal> producerCell = producersRootCell.get(producerName);

            if (producerCell != null) {
                producerCell.emit(metrics);
                logger.trace("Emitted ProducerMetrics to Cell: {}", producerId);
            } else {
                logger.warn("No Cell found for producer: {}", producerId);
            }
        });
    }

    /**
     * Start metric collection from all sensors.
     * <p>
     * Sensors begin collecting metrics at configured intervals and emitting
     * to their respective Cells.
     *
     * @throws IllegalStateException if already started or closed
     */
    public void start() {
        if (closed) {
            throw new IllegalStateException("Cannot start runtime after it is closed");
        }
        if (started) {
            throw new IllegalStateException("Runtime already started");
        }

        logger.info("Starting KafkaObservabilityRuntime");

        brokerSensor.start();
        producerSensor.start();

        started = true;
        logger.info("KafkaObservabilityRuntime started - sensors collecting metrics");
    }

    /**
     * Get brokers/ root Cell for Epic 2 subscriptions.
     * <p>
     * Subscribers will receive MonitorSignal emissions from ALL broker Cells.
     *
     * @return Brokers root Cell
     */
    public Cell<BrokerMetrics, MonitorSignal> getBrokersRootCell() {
        return brokersRootCell;
    }

    /**
     * Get producers/ root Cell for Epic 2 subscriptions.
     * <p>
     * Subscribers will receive MonitorSignal emissions from ALL producer Cells.
     *
     * @return Producers root Cell
     */
    public Cell<ProducerMetrics, MonitorSignal> getProducersRootCell() {
        return producersRootCell;
    }

    /**
     * Get Circuit for advanced usage.
     *
     * @return Circuit instance
     */
    public Circuit getCircuit() {
        return circuit;
    }

    /**
     * Check if runtime has been started.
     *
     * @return true if start() has been called
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Close runtime and release all resources.
     * <p>
     * Stops all sensors, closes Circuit, and cleans up.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        logger.info("Closing KafkaObservabilityRuntime");

        closed = true;

        // Close sensors
        brokerSensor.close();
        producerSensor.close();

        // Close Circuit (releases all Cells)
        circuit.close();

        logger.info("KafkaObservabilityRuntime closed");
    }

    @Override
    public String toString() {
        return String.format("KafkaObservabilityRuntime[cluster=%s, started=%s, brokers=%d, producers=%d]",
                config.clusterName(), started,
                config.brokerSensorConfig().endpoints().size(),
                config.producerSensorConfig().endpoints().size());
    }
}
