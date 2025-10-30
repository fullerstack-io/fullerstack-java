package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.ThreadPoolMetrics;
import io.fullerstack.kafka.broker.monitors.ThreadPoolResourceMonitor;
import io.fullerstack.kafka.core.config.BrokerEndpoint;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.fullerstack.serventis.signals.ResourceSignal;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sensor for collecting thread pool metrics from Kafka brokers via JMX and emitting ResourceSignals.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This sensor collects raw JMX metrics and passes them to {@link ThreadPoolResourceMonitor}
 * which emits ResourceSignals using Serventis vocabulary (GRANT/DENY).
 *
 * <p><b>Note:</b> Per ADR-002, interpretation (baselines, trends, recommendations) removed
 * from sensors. Will be added in Epic 2 via Observers (Layer 4 - Semiosphere).
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Schedule periodic JMX collection from configured brokers</li>
 *   <li>Collect thread pool metrics (network, I/O, log cleaner) via {@link ThreadPoolMetricsCollector}</li>
 *   <li>Pass metrics to {@link ThreadPoolResourceMonitor} for signal emission</li>
 *   <li>Monitor emits ResourceSignals directly to Pipe (no Composer needed)</li>
 *   <li>Handle collection failures gracefully (continue with other pools/brokers)</li>
 * </ul>
 *
 * <p><b>Thread Pools Monitored:</b>
 * <ul>
 *   <li>Network threads ({@code num.network.threads} - default 3)</li>
 *   <li>I/O threads ({@code num.io.threads} - default 8)</li>
 *   <li>Log cleaner threads (optional - only if log compaction enabled)</li>
 * </ul>
 *
 * <h3>Example Usage (Signal-First)</h3>
 * <pre>{@code
 * // Runtime creates Cell with Composer.pipe() (no transformation)
 * Circuit circuit = Cortex.circuit(Cortex.name("kafka.broker.resources"));
 * Cell<ResourceSignal, ResourceSignal> cell = circuit.cell(
 *     Cortex.name("thread-pools"),
 *     Composer.pipe()  // ← No Composer needed!
 * );
 *
 * // Create sensor with signal pipe
 * BrokerSensorConfig config = BrokerSensorConfig.defaults(endpoints);
 * ThreadPoolSensor sensor = new ThreadPoolSensor(
 *     config,
 *     cell,                          // Cell IS-A Pipe<ResourceSignal>
 *     Cortex.name("kafka.broker.resources")
 * );
 *
 * sensor.start();
 *
 * // ... later ...
 * sensor.close();
 * }</pre>
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>{@link #ThreadPoolSensor(BrokerSensorConfig, Pipe, Name)} - Create sensor</li>
 *   <li>{@link #start()} - Start scheduled collection</li>
 *   <li>{@link #close()} - Stop collection and cleanup resources</li>
 * </ol>
 *
 * @see ThreadPoolMetrics
 * @see ThreadPoolMetricsCollector
 * @see ThreadPoolResourceMonitor
 * @see ResourceSignal
 */
public class ThreadPoolSensor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolSensor.class);

    private final BrokerSensorConfig config;
    private final ThreadPoolResourceMonitor monitor;
    private final JmxConnectionPool connectionPool;
    private final ScheduledExecutorService scheduler;
    private volatile boolean started;

    /**
     * Creates a new ThreadPoolSensor.
     * <p>
     * <b>Note:</b> This sensor creates its own JmxConnectionPool for thread pool
     * metrics collection. Connection pooling is always enabled for thread pool
     * monitoring as it's a high-frequency operation.
     *
     * <p><b>ADR-002:</b> BaselineService removed. Contextual assessment deferred to
     * Epic 2 Observers (Layer 4 - Semiosphere).
     *
     * @param config      Sensor configuration with broker endpoints and collection interval
     * @param signalPipe  Pipe to emit ResourceSignals (typically a Cell)
     * @param circuitName Circuit name for Subject creation
     * @throws NullPointerException if any parameter is null
     */
    public ThreadPoolSensor(
        BrokerSensorConfig config,
        Pipe<ResourceSignal> signalPipe,
        Name circuitName
    ) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(signalPipe, "signalPipe cannot be null");
        Objects.requireNonNull(circuitName, "circuitName cannot be null");

        // Create monitor for signal emission (Layer 2)
        this.monitor = new ThreadPoolResourceMonitor(circuitName, signalPipe);

        // Always use connection pooling for thread pool metrics (high frequency)
        this.connectionPool = new JmxConnectionPool();
        logger.info("ThreadPoolSensor initialized (signal-first) with connection pooling for {} brokers",
            config.endpoints().size());

        // Create scheduler with virtual thread (Java 25)
        this.scheduler = Executors.newScheduledThreadPool(
            1,
            Thread.ofVirtual().name("thread-pool-sensor-", 0).factory()
        );

        this.started = false;
    }

    /**
     * Starts scheduled thread pool metrics collection.
     * <p>
     * Collects metrics from all configured brokers at the configured interval
     * ({@code config.collectionIntervalMs()}). Initial collection starts immediately.
     * <p>
     * If already started, logs a warning and returns without action.
     */
    public void start() {
        if (started) {
            logger.warn("ThreadPoolSensor already started");
            return;
        }

        long intervalMs = config.collectionIntervalMs();
        logger.info("Starting thread pool sensor with {}ms collection interval for {} brokers",
            intervalMs, config.endpoints().size());

        scheduler.scheduleAtFixedRate(
            this::collectAndEmitAll,
            0,  // Initial delay (start immediately)
            intervalMs,
            TimeUnit.MILLISECONDS
        );

        started = true;
    }

    /**
     * Collects thread pool metrics from all brokers and emits via callback.
     * <p>
     * Failures for individual brokers are logged but don't stop collection
     * from other brokers (graceful degradation).
     */
    private void collectAndEmitAll() {
        for (BrokerEndpoint endpoint : config.endpoints()) {
            try {
                collectAndEmit(endpoint);
            } catch (Exception e) {
                logger.error("Failed to collect/emit thread pool metrics for broker {}",
                    endpoint.brokerId(), e);
                // Continue with other brokers - don't let one failure stop collection
            }
        }
    }

    /**
     * Collects thread pool metrics from a single broker and interprets them as ResourceSignals.
     * <p>
     * Collects metrics for all available thread pools (network, I/O, log cleaner)
     * and passes each to the monitor for interpretation and signal emission.
     *
     * @param endpoint Broker endpoint to collect from
     * @throws Exception if JMX collection fails
     */
    private void collectAndEmit(BrokerEndpoint endpoint) throws Exception {
        ThreadPoolMetricsCollector collector = new ThreadPoolMetricsCollector(
            endpoint.jmxUrl(),
            connectionPool
        );

        // Collect metrics for all thread pools
        List<ThreadPoolMetrics> metricsList = collector.collect(endpoint.brokerId());

        // Emit ResourceSignal for each pool
        for (ThreadPoolMetrics metrics : metricsList) {
            monitor.emit(metrics);  // Monitor emits ResourceSignal with Serventis vocabulary
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Collected and emitted {} thread pool signals for broker {}",
                metricsList.size(), endpoint.brokerId());
        }
    }

    /**
     * Checks if the sensor has been started.
     *
     * @return true if {@link #start()} has been called
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Stops the sensor and releases resources.
     * <p>
     * <b>Cleanup:</b>
     * <ul>
     *   <li>Stops scheduled collection</li>
     *   <li>Shuts down scheduler executor (5 second timeout)</li>
     *   <li>Closes JMX connection pool</li>
     * </ul>
     * <p>
     * Safe to call multiple times (idempotent).
     */
    @Override
    public void close() {
        if (!started) {
            logger.debug("ThreadPoolSensor not started, nothing to close");
            return;
        }

        logger.info("Stopping thread pool sensor");
        started = false;

        // Shutdown scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate in 5 seconds, forcing shutdown");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for scheduler shutdown");
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close connection pool
        try {
            connectionPool.close();
        } catch (Exception e) {
            logger.error("Failed to close JMX connection pool", e);
        }

        logger.info("ThreadPoolSensor stopped");
    }
}
