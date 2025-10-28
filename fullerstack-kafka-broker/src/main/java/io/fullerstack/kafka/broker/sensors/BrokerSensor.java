package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.core.config.BrokerEndpoint;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Sensor for collecting JMX metrics from Kafka brokers.
 * <p>
 * This sensor is responsible ONLY for metric collection - it has NO knowledge
 * of Substrates concepts (Cortex, Circuit, Cell, Name). The runtime layer is
 * responsible for wiring sensors to the Circuit/Cell hierarchy.
 * <p>
 * Responsibilities:
 * - Schedule periodic JMX collection from configured brokers
 * - Extract metrics via JMX
 * - Emit metrics via callback (String brokerId → BrokerMetrics)
 * - Handle collection failures gracefully
 * <p>
 * Usage:
 * <pre>
 * BrokerSensorConfig config = BrokerSensorConfig.defaults(endpoints);
 * BiConsumer&lt;String, BrokerMetrics&gt; emitter = (brokerId, metrics) -> {...};
 *
 * BrokerSensor sensor = new BrokerSensor(config, emitter);
 * sensor.start();
 *
 * // ... later ...
 * sensor.close();
 * </pre>
 */
public class BrokerSensor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(BrokerSensor.class);

    private final BrokerSensorConfig config;
    private final BiConsumer<String, BrokerMetrics> metricsEmitter;
    private final JmxMetricsCollector collector;
    private final ScheduledExecutorService scheduler;
    private final JmxConnectionPool connectionPool;  // Optional (null if pooling disabled)
    private volatile boolean started;

    /**
     * Create a new BrokerSensor.
     * <p>
     * If {@code config.useConnectionPooling()} is true, creates a JMX connection
     * pool for high-frequency monitoring.
     *
     * @param config Sensor configuration with endpoints and collection settings
     * @param metricsEmitter Callback to emit metrics (receives brokerId String and BrokerMetrics)
     */
    public BrokerSensor(BrokerSensorConfig config, BiConsumer<String, BrokerMetrics> metricsEmitter) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.metricsEmitter = Objects.requireNonNull(metricsEmitter, "metricsEmitter cannot be null");

        // Create connection pool if configured
        if (config.useConnectionPooling()) {
            this.connectionPool = new JmxConnectionPool();
            logger.info("JMX connection pooling ENABLED for high-frequency monitoring");
        } else {
            this.connectionPool = null;
            logger.info("JMX connection pooling DISABLED (standard mode)");
        }

        // Create collector with optional connection pool
        this.collector = new JmxMetricsCollector(connectionPool);

        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "broker-sensor");
            t.setDaemon(true);
            return t;
        });

        this.started = false;
    }

    /**
     * Start scheduled JMX collection.
     * <p>
     * Collects metrics from all configured brokers every {@code collectionIntervalMs}.
     */
    public void start() {
        if (started) {
            logger.warn("BrokerSensor already started");
            return;
        }

        long intervalMs = config.collectionIntervalMs();
        logger.info("Starting broker sensor with {}ms collection interval for {} brokers",
                intervalMs, config.endpoints().size());

        scheduler.scheduleAtFixedRate(
                this::collectAndEmitAll,
                0,  // Initial delay
                intervalMs,
                TimeUnit.MILLISECONDS
        );

        started = true;
    }

    /**
     * Collect metrics from all brokers and emit via callback.
     */
    private void collectAndEmitAll() {
        for (BrokerEndpoint endpoint : config.endpoints()) {
            try {
                collectAndEmit(endpoint);
            } catch (Exception e) {
                logger.error("Failed to collect/emit metrics for broker {}", endpoint.brokerId(), e);
                // Continue with other brokers - don't let one failure stop collection
            }
        }
    }

    /**
     * Collect metrics from a single broker and emit via callback.
     *
     * @param endpoint Broker endpoint to collect from
     */
    private void collectAndEmit(BrokerEndpoint endpoint) {
        // Collect metrics via JMX
        BrokerMetrics metrics = collector.collect(endpoint.jmxUrl());

        // Override broker ID to ensure it matches our configured ID
        // (JmxMetricsCollector may extract from JMX URL, but we want to use configured ID)
        BrokerMetrics normalizedMetrics = new BrokerMetrics(
                endpoint.brokerId(),  // Use configured broker ID
                metrics.heapUsed(),
                metrics.heapMax(),
                metrics.cpuUsage(),
                metrics.requestRate(),
                metrics.byteInRate(),
                metrics.byteOutRate(),
                metrics.activeControllers(),
                metrics.underReplicatedPartitions(),
                metrics.offlinePartitionsCount(),
                metrics.networkProcessorAvgIdlePercent(),
                metrics.requestHandlerAvgIdlePercent(),
                metrics.fetchConsumerTotalTimeMs(),
                metrics.produceTotalTimeMs(),
                metrics.timestamp()
        );

        // Emit metrics via callback (just brokerId string - no Name/Circuit knowledge)
        metricsEmitter.accept(endpoint.brokerId(), normalizedMetrics);

        logger.debug("Emitted metrics for broker {}", endpoint.brokerId());
    }

    /**
     * Gracefully shutdown sensor.
     * <p>
     * Stops scheduled collection, waits for in-flight tasks to complete,
     * and closes the JMX connection pool (if enabled).
     */
    @Override
    public void close() {
        logger.info("Shutting down broker sensor");
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate in 5 seconds, forcing shutdown");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for scheduler shutdown", e);
            scheduler.shutdownNow();
        }

        // Close connection pool if present
        if (connectionPool != null) {
            connectionPool.close();
            logger.info("JMX connection pool closed");
        }

        started = false;
    }

    /**
     * Check if sensor is started.
     *
     * @return true if sensor is collecting metrics
     */
    public boolean isStarted() {
        return started;
    }
}
