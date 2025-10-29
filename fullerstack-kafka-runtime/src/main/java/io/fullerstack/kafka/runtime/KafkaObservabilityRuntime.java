package io.fullerstack.kafka.runtime;

import io.fullerstack.kafka.broker.composers.BrokerHealthCellComposer;
import io.fullerstack.kafka.broker.composers.ThreadPoolResourceComposer;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.sensors.BrokerSensor;
import io.fullerstack.kafka.broker.sensors.ThreadPoolSensor;
import io.fullerstack.kafka.consumer.composers.ConsumerHealthCellComposer;
import io.fullerstack.kafka.consumer.models.ConsumerMetrics;
import io.fullerstack.kafka.consumer.sensors.ConsumerSensor;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.fullerstack.kafka.core.config.ConsumerEndpoint;
import io.fullerstack.kafka.core.config.ProducerSensorConfig;
import io.fullerstack.kafka.producer.composers.ProducerHealthCellComposer;
import io.fullerstack.kafka.producer.models.ProducerMetrics;
import io.fullerstack.kafka.producer.sensors.ProducerSensor;
import io.fullerstack.serventis.signals.MonitorSignal;
import io.fullerstack.serventis.signals.ResourceSignal;
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
 * the lifecycle of all sensors (BrokerSensor, ProducerSensor, ConsumerSensor).
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
 *   │   ├── broker-thread-pools/ (Container&lt;ResourceSignal&gt;)
 *   │   │   ├── broker-1/ (Cell&lt;ThreadPoolMetrics, ResourceSignal&gt;)
 *   │   │   │   ├── network (Cell&lt;ThreadPoolMetrics, ResourceSignal&gt;)
 *   │   │   │   ├── io
 *   │   │   │   └── log-cleaner
 *   │   │   └── broker-2/
 *   │   │       └── ...
 *   │   ├── producers/ (Container&lt;MonitorSignal&gt;)
 *   │   │   ├── producer-1 (Cell&lt;ProducerMetrics, MonitorSignal&gt;)
 *   │   │   └── producer-2
 *   │   └── consumers/ (Container&lt;MonitorSignal&gt;)
 *   │       ├── group-1/ (Cell&lt;ConsumerMetrics, MonitorSignal&gt;)
 *   │       │   ├── consumer-a (Cell&lt;ConsumerMetrics, MonitorSignal&gt;)
 *   │       │   └── consumer-b
 *   │       └── group-2/
 *   │           └── consumer-c
 *   └── Sensors (just collect, no Circuit knowledge):
 *       ├── BrokerSensor → emits to brokers/
 *       ├── ThreadPoolSensor → emits to broker-thread-pools/broker/pool
 *       ├── ProducerSensor → emits to producers/
 *       └── ConsumerSensor → emits to consumers/group/member
 * </pre>
 * <p>
 * <b>Responsibilities:</b>
 * - Create and manage Cortex + Circuit
 * - Build Cell hierarchy (brokers/, producers/, consumers/)
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
 * runtime.getBrokersRootCell().subscribe(signal -> {...});
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
    private final Cell<ThreadPoolMetrics, ResourceSignal> brokerThreadPoolsRootCell;
    private final Cell<ProducerMetrics, MonitorSignal> producersRootCell;
    private final Cell<ConsumerMetrics, MonitorSignal> consumersRootCell;

    // Sensors (just collect)
    private final BrokerSensor brokerSensor;
    private final ThreadPoolSensor threadPoolSensor;
    private final ProducerSensor producerSensor;
    private final ConsumerSensor consumerSensor;

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
        this.brokerThreadPoolsRootCell = createBrokerThreadPoolsRootCell();
        this.producersRootCell = createProducersRootCell();
        this.consumersRootCell = createConsumersRootCell();

        // Create sensors with emission callbacks
        this.brokerSensor = createBrokerSensor();
        this.threadPoolSensor = createThreadPoolSensor();
        this.producerSensor = createProducerSensor();
        this.consumerSensor = createConsumerSensor();

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
     * Create broker-thread-pools/ root Cell with ThreadPoolResourceComposer.
     * <p>
     * Creates a 3-level hierarchy: broker-thread-pools/ → broker-N → pool-type
     * <p>
     * Thread pool types: network, io, log-cleaner
     *
     * @return Root Cell for thread pool resource monitoring
     */
    private Cell<ThreadPoolMetrics, ResourceSignal> createBrokerThreadPoolsRootCell() {
        ThreadPoolResourceComposer composer = new ThreadPoolResourceComposer();
        Cell<ThreadPoolMetrics, ResourceSignal> rootCell = circuit.cell(composer, Pipe.empty());
        logger.info("Created broker-thread-pools/ root Cell in circuit");

        // Pre-create broker thread pool Cells from config
        for (var endpoint : config.brokerSensorConfig().endpoints()) {
            String brokerId = endpoint.brokerId();
            Name brokerName = cortex.name(brokerId);
            Cell<ThreadPoolMetrics, ResourceSignal> brokerCell = rootCell.get(brokerName);

            // Pre-create child Cells for each pool type (network, io, log-cleaner)
            // Note: log-cleaner is optional and may not emit if compaction disabled
            Name networkPoolName = cortex.name("network");
            Name ioPoolName = cortex.name("io");
            Name logCleanerPoolName = cortex.name("log-cleaner");

            brokerCell.get(networkPoolName);
            brokerCell.get(ioPoolName);
            brokerCell.get(logCleanerPoolName);

            logger.debug("Pre-created thread pool Cells for broker: {}", brokerId);
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
     * Create consumers/ root Cell with ConsumerHealthCellComposer.
     * <p>
     * Creates 3-level hierarchy: consumers/ → group → member
     *
     * @return Root Cell for consumer health monitoring
     */
    private Cell<ConsumerMetrics, MonitorSignal> createConsumersRootCell() {
        ConsumerHealthCellComposer composer = new ConsumerHealthCellComposer();
        Cell<ConsumerMetrics, MonitorSignal> rootCell = circuit.cell(composer, Pipe.empty());
        logger.info("Created consumers/ root Cell in circuit");

        // Pre-create consumer child Cells from config (group → member hierarchy)
        for (var endpoint : config.consumerSensorConfig().endpoints()) {
            String groupName = endpoint.consumerGroup();
            String consumerId = endpoint.consumerId();

            // Create group Cell if not exists
            Name groupCellName = cortex.name(groupName);
            Cell<ConsumerMetrics, MonitorSignal> groupCell = rootCell.get(groupCellName);

            // Create member Cell under group
            Name memberCellName = cortex.name(consumerId);
            Cell<ConsumerMetrics, MonitorSignal> memberCell = groupCell.get(memberCellName);

            logger.debug("Pre-created consumer Cell: {}/{}", groupName, consumerId);
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
     * Create ThreadPoolSensor wired to emit to broker-thread-pools/ root Cell.
     * <p>
     * Routes emissions through broker + pool-type hierarchy:
     * broker-thread-pools/ → broker-N → pool-type (network/io/log-cleaner)
     *
     * @return Configured ThreadPoolSensor
     */
    private ThreadPoolSensor createThreadPoolSensor() {
        BrokerSensorConfig sensorConfig = config.brokerSensorConfig();

        // Callback: route emissions to correct broker → pool type Cell
        return new ThreadPoolSensor(sensorConfig, (brokerId, metrics) -> {
            Name brokerName = cortex.name(brokerId);
            Name poolName = cortex.name(metrics.poolType().displayName());

            Cell<ThreadPoolMetrics, ResourceSignal> brokerCell = brokerThreadPoolsRootCell.get(brokerName);
            if (brokerCell != null) {
                Cell<ThreadPoolMetrics, ResourceSignal> poolCell = brokerCell.get(poolName);
                if (poolCell != null) {
                    poolCell.emit(metrics);
                    logger.trace("Emitted ThreadPoolMetrics to Cell: {}/{}", brokerId, metrics.poolType().displayName());
                } else {
                    logger.warn("No pool Cell found for thread pool: {}/{}", brokerId, metrics.poolType());
                }
            } else {
                logger.warn("No broker Cell found for thread pool metrics: {}", brokerId);
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
     * Create ConsumerSensor wired to emit to consumers/ root Cell.
     * <p>
     * Routes emissions through group hierarchy: consumers/ → group → member
     *
     * @return Configured ConsumerSensor
     */
    private ConsumerSensor createConsumerSensor() {
        // Callback: route emissions to correct consumer Cell through group hierarchy
        return new ConsumerSensor(config.consumerSensorConfig(), (consumerId, metrics) -> {
            // Find consumer's group from config
            String consumerGroup = findConsumerGroup(consumerId);
            if (consumerGroup == null) {
                logger.warn("No group found for consumer: {}", consumerId);
                return;
            }

            Name groupName = cortex.name(consumerGroup);
            Name memberName = cortex.name(consumerId);

            Cell<ConsumerMetrics, MonitorSignal> groupCell = consumersRootCell.get(groupName);
            if (groupCell != null) {
                Cell<ConsumerMetrics, MonitorSignal> memberCell = groupCell.get(memberName);
                if (memberCell != null) {
                    memberCell.emit(metrics);
                    logger.trace("Emitted ConsumerMetrics to Cell: {}/{}", consumerGroup, consumerId);
                } else {
                    logger.warn("No member Cell found for consumer: {}/{}", consumerGroup, consumerId);
                }
            } else {
                logger.warn("No group Cell found for consumer: {}/{}", consumerGroup, consumerId);
            }
        });
    }

    /**
     * Find consumer group for given consumer ID from config.
     *
     * @param consumerId Consumer ID
     * @return Consumer group name, or null if not found
     */
    private String findConsumerGroup(String consumerId) {
        return config.consumerSensorConfig().endpoints().stream()
                .filter(endpoint -> endpoint.consumerId().equals(consumerId))
                .map(ConsumerEndpoint::consumerGroup)
                .findFirst()
                .orElse(null);
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
        threadPoolSensor.start();
        producerSensor.start();
        consumerSensor.start();

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
     * Get consumers/ root Cell for Epic 2 subscriptions.
     * <p>
     * Subscribers will receive MonitorSignal emissions from ALL consumer Cells
     * across all groups.
     *
     * @return Consumers root Cell
     */
    public Cell<ConsumerMetrics, MonitorSignal> getConsumersRootCell() {
        return consumersRootCell;
    }

    /**
     * Get broker-thread-pools/ root Cell for Epic 2 subscriptions.
     * <p>
     * Subscribers will receive ResourceSignal emissions from ALL thread pool Cells
     * across all brokers and pool types (network, io, log-cleaner).
     *
     * @return Broker thread pools root Cell
     */
    public Cell<ThreadPoolMetrics, ResourceSignal> getBrokerThreadPoolsRootCell() {
        return brokerThreadPoolsRootCell;
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
        threadPoolSensor.close();
        producerSensor.close();
        consumerSensor.close();

        // Close Circuit (releases all Cells)
        circuit.close();

        logger.info("KafkaObservabilityRuntime closed");
    }

    @Override
    public String toString() {
        return String.format("KafkaObservabilityRuntime[cluster=%s, started=%s, brokers=%d, producers=%d, consumers=%d]",
                config.clusterName(), started,
                config.brokerSensorConfig().endpoints().size(),
                config.producerSensorConfig().endpoints().size(),
                config.consumerSensorConfig().endpoints().size());
    }
}
