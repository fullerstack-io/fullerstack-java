package io.fullerstack.kafka.runtime;

import io.fullerstack.kafka.broker.composers.BrokerHealthCellComposer;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
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
import io.humainary.substrates.ext.serventis.Monitors;
import io.humainary.substrates.ext.serventis.Resources;
import io.humainary.substrates.api.Substrates.Cell;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Composer;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Flow;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.api.Substrates.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

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

    // Cell hierarchy roots (RC1: using Monitors.Status and Resources.Signal)
    private final Cell<BrokerMetrics, Monitors.Status> brokersRootCell;
    private final Cell<Resources.Signal, Resources.Signal> brokerThreadPoolsRootCell;  // Signal-first: Resources.Signal → Resources.Signal
    private final Cell<ProducerMetrics, Monitors.Status> producersRootCell;
    private final Cell<ConsumerMetrics, Monitors.Status> consumersRootCell;

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
        Name circuitName = cortex.name ( "kafka.cluster." + config.clusterName () );
        this.circuit = cortex.circuit ( circuitName );
        logger.info ( "Circuit created: {}", circuitName );

        // Create Cell hierarchy (RC1: Composers have hardcoded thresholds)
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
    private Cell<BrokerMetrics, Monitors.Status> createBrokersRootCell() {
        // Use BrokerHealthCellComposer as transformer
        Composer<Monitors.Status, Pipe<BrokerMetrics>> transformerComposer = new BrokerHealthCellComposer();

        // Identity aggregator composer
        Composer<Monitors.Status, Pipe<Monitors.Status>> aggregatorComposer = channel -> channel.pipe();

        // No-op downstream pipe
        Pipe<Monitors.Status> noopPipe = new Pipe<>() {
            @Override
            public void emit(Monitors.Status status) {
                // No-op
            }

            @Override
            public void flush() {
                // No-op
            }
        };

        Cell<BrokerMetrics, Monitors.Status> rootCell = circuit.cell(
            transformerComposer,
            aggregatorComposer,
            noopPipe
        );

        logger.info("Created brokers/ root Cell in circuit");

        // Pre-create broker child Cells from config
        for (var endpoint : config.brokerSensorConfig().endpoints()) {
            String brokerId = endpoint.brokerId();
            Name brokerName = cortex.name(brokerId);
            Cell<BrokerMetrics, Monitors.Status> brokerCell = rootCell.get(brokerName);
            logger.debug("Pre-created broker Cell: {}", brokerId);
        }

        return rootCell;
    }

    /**
     * Create broker-thread-pools/ root Cell with signal-first pattern (Composer.pipe()).
     * <p>
     * Signal-first: ThreadPoolSensor → ThreadPoolResourceMonitor → ResourceSignal → Pipe (no Composer!)
     * <p>
     * Thread pool types: network, io, log-cleaner
     *
     * @return Root Cell for thread pool resource monitoring
     */
    private Cell < Resources.Signal, Resources.Signal > createBrokerThreadPoolsRootCell () {
      // Signal-first: No Composer needed - signals already interpreted by ThreadPoolResourceMonitor
      // RC3 Cell API: cell(transformer, aggregator, downstream)
      // Identity Composers since input = output = Resources.Signal
      Composer < Resources.Signal, Pipe < Resources.Signal >> identityComposer = channel -> channel.pipe();

      Pipe < Resources.Signal > noopPipe = new Pipe<>() {
          @Override
          public void emit(Resources.Signal signal) {
              // No-op
          }

          @Override
          public void flush() {
              // No-op
          }
      };

      Cell < Resources.Signal, Resources.Signal > rootCell = circuit.cell (
        identityComposer,    // transformer: identity
        identityComposer,    // aggregator: identity
        noopPipe             // downstream: no-op
      );

      logger.info ( "Created broker-thread-pools/ root Cell in circuit (signal-first with identity)" );

      // Note: We don't pre-create Cells in signal-first architecture
      // Cells are created dynamically when signals are emitted
      // This reduces complexity and memory footprint

      return rootCell;
    }

    /**
     * Create producers/ root Cell with ProducerHealthCellComposer.
     *
     * @return Root Cell for producer health monitoring
     */
    private Cell < ProducerMetrics, Monitors.Status > createProducersRootCell () {
      // Use ProducerHealthCellComposer as transformer
      Composer<Monitors.Status, Pipe<ProducerMetrics>> transformerComposer = new ProducerHealthCellComposer();

      // Identity aggregator composer
      Composer<Monitors.Status, Pipe<Monitors.Status>> aggregatorComposer = channel -> channel.pipe();

      // No-op downstream pipe
      Pipe<Monitors.Status> noopPipe = new Pipe<>() {
          @Override
          public void emit(Monitors.Status status) {
              // No-op
          }

          @Override
          public void flush() {
              // No-op
          }
      };

      Cell<ProducerMetrics, Monitors.Status> rootCell = circuit.cell(
          transformerComposer,
          aggregatorComposer,
          noopPipe
      );

      logger.info ( "Created producers/ root Cell in circuit" );

        // Pre-create producer child Cells from config
        for (var endpoint : config.producerSensorConfig().endpoints()) {
            String producerId = endpoint.producerId();
            Name producerName = cortex.name(producerId);
            Cell<ProducerMetrics, Monitors.Status> producerCell = rootCell.get(producerName);
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
    private Cell < ConsumerMetrics, Monitors.Status > createConsumersRootCell () {
      // Use ConsumerHealthCellComposer as transformer
      Composer<Monitors.Status, Pipe<ConsumerMetrics>> transformerComposer = new ConsumerHealthCellComposer();

      // Identity aggregator composer
      Composer<Monitors.Status, Pipe<Monitors.Status>> aggregatorComposer = channel -> channel.pipe();

      // No-op downstream pipe
      Pipe<Monitors.Status> noopPipe = new Pipe<>() {
          @Override
          public void emit(Monitors.Status status) {
              // No-op
          }

          @Override
          public void flush() {
              // No-op
          }
      };

      Cell<ConsumerMetrics, Monitors.Status> rootCell = circuit.cell(
          transformerComposer,
          aggregatorComposer,
          noopPipe
      );

      logger.info ( "Created consumers/ root Cell in circuit" );

        // Pre-create consumer child Cells from config (group → member hierarchy)
        for (var endpoint : config.consumerSensorConfig().endpoints()) {
            String groupName = endpoint.consumerGroup();
            String consumerId = endpoint.consumerId();

            // Create group Cell if not exists
            Name groupCellName = cortex.name(groupName);
            Cell<ConsumerMetrics, Monitors.Status> groupCell = rootCell.get(groupCellName);

            // Create member Cell under group
            Name memberCellName = cortex.name(consumerId);
            Cell<ConsumerMetrics, Monitors.Status> memberCell = groupCell.get(memberCellName);

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
            Cell<BrokerMetrics, Monitors.Status> brokerCell = brokersRootCell.get(brokerName);

            if (brokerCell != null) {
                brokerCell.pipe().emit(metrics);
                logger.trace("Emitted BrokerMetrics to Cell: {}", brokerId);
            } else {
                logger.warn("No Cell found for broker: {}", brokerId);
            }
        });
    }

    /**
     * Create ThreadPoolSensor with signal-first pattern.
     * <p>
     * Signal-first: Sensor → Monitor interprets → ResourceSignal → brokerThreadPoolsRootCell (Pipe)
     * <p>
     * No manual routing needed - ThreadPoolResourceMonitor emits signals directly to Pipe!
     *
     * @return Configured ThreadPoolSensor
     */
    private ThreadPoolSensor createThreadPoolSensor() {
        BrokerSensorConfig sensorConfig = config.brokerSensorConfig();
        Name circuitName = cortex.name("kafka.cluster." + config.clusterName());

        // TODO: RC1 refactoring needed - ThreadPoolSensor now requires Channel<Resources.Signal>
        // instead of Cell. This requires creating a Conduit with Resources composer.
        // For now, creating a minimal ThreadPoolSensor to unblock compilation.
        // Full refactoring: Replace Cell API with Conduit/Channel pattern.

        // Create a channel for thread pool signals
        // NOTE: This is a temporary workaround - proper RC1 pattern would be:
        // Conduit<Resources.Signal> conduit = circuit.conduit(Resources::composer);
        // Channel<Resources.Signal> channel = conduit.channel(cortex.name("thread-pools"));

        throw new UnsupportedOperationException(
            "ThreadPoolSensor requires RC1 Conduit/Channel refactoring - Cell API no longer compatible"
        );
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
            Cell<ProducerMetrics, Monitors.Status> producerCell = producersRootCell.get(producerName);

            if (producerCell != null) {
                producerCell.pipe().emit(metrics);
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

            Cell<ConsumerMetrics, Monitors.Status> groupCell = consumersRootCell.get(groupName);
            if (groupCell != null) {
                Cell<ConsumerMetrics, Monitors.Status> memberCell = groupCell.get(memberName);
                if (memberCell != null) {
                    memberCell.pipe().emit(metrics);
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
     * Subscribers will receive Monitors.Status emissions from ALL broker Cells.
     *
     * @return Brokers root Cell
     */
    public Cell<BrokerMetrics, Monitors.Status> getBrokersRootCell() {
        return brokersRootCell;
    }

    /**
     * Get producers/ root Cell for Epic 2 subscriptions.
     * <p>
     * Subscribers will receive Monitors.Status emissions from ALL producer Cells.
     *
     * @return Producers root Cell
     */
    public Cell<ProducerMetrics, Monitors.Status> getProducersRootCell() {
        return producersRootCell;
    }

    /**
     * Get consumers/ root Cell for Epic 2 subscriptions.
     * <p>
     * Subscribers will receive Monitors.Status emissions from ALL consumer Cells
     * across all groups.
     *
     * @return Consumers root Cell
     */
    public Cell<ConsumerMetrics, Monitors.Status> getConsumersRootCell() {
        return consumersRootCell;
    }

    /**
     * Get broker-thread-pools/ root Cell for Epic 2 subscriptions.
     * <p>
     * Subscribers will receive Resources.Signal emissions from ALL thread pool Cells
     * across all brokers and pool types (network, io, log-cleaner).
     * <p>
     * Signal-first: Returns Cell&lt;Resources.Signal, Resources.Signal&gt; (signals already interpreted)
     *
     * @return Broker thread pools root Cell
     */
    public Cell<Resources.Signal, Resources.Signal> getBrokerThreadPoolsRootCell() {
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
