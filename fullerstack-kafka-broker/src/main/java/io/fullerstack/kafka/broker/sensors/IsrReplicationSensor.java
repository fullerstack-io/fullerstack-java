package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.IsrMetrics;
import io.fullerstack.kafka.broker.models.ReplicationHealthMetrics;
import io.fullerstack.kafka.broker.monitors.IsrReplicationRouterMonitor;
import io.fullerstack.kafka.broker.monitors.ReplicationHealthMonitor;
import io.fullerstack.kafka.core.config.BrokerEndpoint;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Routers;
import io.humainary.substrates.ext.serventis.ext.Routers.Router;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sensor for collecting ISR replication metrics from Kafka brokers via JMX and emitting
 * Routers.Signal (RC6) and Monitors.Sign.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This sensor collects raw JMX metrics and passes them to:
 * <ul>
 *   <li>{@link IsrReplicationRouterMonitor} - emits Routers.Signal (SEND/RECEIVE/DROP)</li>
 *   <li>{@link ReplicationHealthMonitor} - emits Monitors.Signal (STABLE/DEGRADED/DEFECTIVE/DOWN)</li>
 * </ul>
 *
 * <p><b>RC6 Routers API Pattern</b>:
 * Models Kafka ISR replication as a routing topology where the leader "routes" replication
 * data to follower replicas. ISR shrinks = DROP (follower removed from topology), ISR expands = RECEIVE
 * (follower added back to topology).
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Schedule periodic JMX collection from configured brokers (5 second interval)</li>
 *   <li>Collect ISR metrics (shrinks, expands, lag, fetch rate) via {@link IsrMetricsCollector}</li>
 *   <li>Collect replication health (under-replicated, offline, controller) via {@link ReplicationHealthMetricsCollector}</li>
 *   <li>Compute deltas for ISR shrink/expand events (cumulative counts → event detection)</li>
 *   <li>Pass metrics to monitors for signal emission</li>
 *   <li>Handle collection failures gracefully (continue with other brokers)</li>
 * </ul>
 *
 * <p><b>Metrics Collected (7 total)</b>:
 * <ol>
 *   <li>ISR Shrinks Per Sec (Routers: DROP)</li>
 *   <li>ISR Expands Per Sec (Routers: RECEIVE)</li>
 *   <li>Replica Lag Max (Routers: SEND + lag tracking)</li>
 *   <li>Replica Fetch Rate Min (Routers: RECEIVE rate)</li>
 *   <li>Under-Replicated Partitions (Monitors: DEGRADED)</li>
 *   <li>Offline Partitions (Monitors: DEFECTIVE)</li>
 *   <li>Active Controller Count (Monitors: DOWN if 0)</li>
 * </ol>
 *
 * <h3>Example Usage</h3>
 * <pre>{@code
 * // Runtime creates conduits for Routers.Signal and Monitors.Signal
 * Circuit circuit = cortex().circuit(cortex().name("isr-replication"));
 * Conduit<Router, Routers.Sign> routersConduit = circuit.conduit(
 *     cortex().name("routers"),
 *     Routers::composer
 * );
 * Conduit<Monitor, Monitors.Signal> monitorsConduit = circuit.conduit(
 *     cortex().name("monitors"),
 *     Monitors::composer
 * );
 *
 * // Create sensor with conduits
 * BrokerSensorConfig config = BrokerSensorConfig.defaults(endpoints);
 * IsrReplicationSensor sensor = new IsrReplicationSensor(
 *     config,
 *     routersConduit,
 *     monitorsConduit,
 *     cortex().name("isr-replication"),
 *     "cluster-1"
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
 *   <li>{@link #IsrReplicationSensor(BrokerSensorConfig, Channel, Channel, Name, String)} - Create sensor</li>
 *   <li>{@link #start()} - Start scheduled collection (5 second interval)</li>
 *   <li>{@link #close()} - Stop collection and cleanup resources</li>
 * </ol>
 *
 * @see IsrMetrics
 * @see ReplicationHealthMetrics
 * @see IsrMetricsCollector
 * @see ReplicationHealthMetricsCollector
 * @see IsrReplicationRouterMonitor
 * @see ReplicationHealthMonitor
 */
public class IsrReplicationSensor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(IsrReplicationSensor.class);

    // ISR metrics are P0 (critical) - poll every 5 seconds
    private static final long DEFAULT_COLLECTION_INTERVAL_MS = 5000L;

    private final BrokerSensorConfig config;
    private final String clusterId;
    private final JmxConnectionPool connectionPool;
    private final ScheduledExecutorService scheduler;

    // Monitors (one per broker)
    private final Map<String, IsrReplicationRouterMonitor> routerMonitors;
    private final ReplicationHealthMonitor healthMonitor;

    // Previous metrics for delta computation (ISR shrink/expand event detection)
    private final Map<String, IsrMetrics> previousIsrMetrics;

    private volatile boolean started;

    /**
     * Creates a new IsrReplicationSensor.
     * <p>
     * This sensor creates its own JmxConnectionPool for ISR metrics collection.
     * Connection pooling is always enabled for high-frequency monitoring.
     *
     * @param config          Sensor configuration with broker endpoints
     * @param routersConduit  Conduit to emit Routers.Signal (RC6 ISR topology signals)
     * @param monitorsConduit Conduit to emit Monitors.Signal (replication health assessment)
     * @param circuitName     Circuit name for logging
     * @param clusterId       Cluster identifier (e.g., "cluster-1", "prod-kafka")
     * @throws NullPointerException if any parameter is null
     */
    public IsrReplicationSensor(
        final BrokerSensorConfig config,
        final Conduit<Router, Routers.Sign> routersConduit,
        final Conduit<Monitor, Monitors.Signal> monitorsConduit,
        final Name circuitName,
        final String clusterId
    ) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(routersConduit, "routersConduit cannot be null");
        Objects.requireNonNull(monitorsConduit, "monitorsConduit cannot be null");
        Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.clusterId = Objects.requireNonNull(clusterId, "clusterId cannot be null");

        // Create monitors for each broker
        this.routerMonitors = new HashMap<>();
        for (BrokerEndpoint endpoint : config.endpoints()) {
            IsrReplicationRouterMonitor monitor = new IsrReplicationRouterMonitor(
                circuitName,
                routersConduit,
                endpoint.brokerId()
            );
            routerMonitors.put(endpoint.brokerId(), monitor);
        }

        // Create health monitor (cluster-wide)
        this.healthMonitor = new ReplicationHealthMonitor(
            circuitName,
            monitorsConduit,
            clusterId
        );

        // Always use connection pooling for ISR metrics (high frequency)
        this.connectionPool = new JmxConnectionPool();

        // Previous metrics for delta computation
        this.previousIsrMetrics = new HashMap<>();

        logger.info(
            "IsrReplicationSensor initialized for cluster {} with {} brokers",
            clusterId,
            config.endpoints().size()
        );

        // Create scheduler with virtual thread (Java 25)
        this.scheduler = Executors.newScheduledThreadPool(
            1,
            Thread.ofVirtual().name("isr-replication-sensor-", 0).factory()
        );

        this.started = false;
    }

    /**
     * Starts scheduled ISR replication metrics collection.
     * <p>
     * Collects metrics from all configured brokers every 5 seconds (critical metric).
     * Initial collection starts immediately.
     * <p>
     * If already started, logs a warning and returns without action.
     */
    public void start() {
        if (started) {
            logger.warn("IsrReplicationSensor already started");
            return;
        }

        long intervalMs = DEFAULT_COLLECTION_INTERVAL_MS;
        logger.info("Starting ISR replication sensor with {}ms collection interval for {} brokers",
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
     * Collects ISR replication metrics from all brokers and emits signals.
     * <p>
     * Failures for individual brokers are logged but don't stop collection
     * from other brokers (graceful degradation).
     */
    private void collectAndEmitAll() {
        // Collect ISR metrics from each broker
        for (BrokerEndpoint endpoint : config.endpoints()) {
            try {
                collectAndEmitIsr(endpoint);
            } catch (Exception e) {
                logger.error("Failed to collect/emit ISR metrics for broker {}",
                    endpoint.brokerId(), e);
                // Continue with other brokers - don't let one failure stop collection
            }
        }

        // Collect replication health (cluster-wide, typically from controller)
        try {
            collectAndEmitHealth();
        } catch (Exception e) {
            logger.error("Failed to collect/emit replication health for cluster {}",
                clusterId, e);
        }
    }

    /**
     * Collects ISR metrics from a single broker and emits Router signals.
     * <p>
     * Computes delta from previous metrics to detect new ISR shrink/expand events.
     * First poll emits absolute values (no delta available).
     *
     * @param endpoint Broker endpoint to collect from
     * @throws Exception if JMX collection fails
     */
    private void collectAndEmitIsr(BrokerEndpoint endpoint) throws Exception {
        String brokerId = endpoint.brokerId();
        IsrMetricsCollector collector = new IsrMetricsCollector(
            endpoint.jmxUrl(),
            connectionPool
        );

        // Collect current metrics
        IsrMetrics currentMetrics = collector.collect(brokerId);

        // Get monitor for this broker
        IsrReplicationRouterMonitor monitor = routerMonitors.get(brokerId);

        // Compute delta and emit signals
        IsrMetrics previousMetrics = previousIsrMetrics.get(brokerId);
        if (previousMetrics != null) {
            // Delta available - emit shrink/expand events
            IsrMetrics deltaMetrics = currentMetrics.delta(previousMetrics);
            monitor.emit(deltaMetrics);
        } else {
            // First poll - emit absolute values (no delta)
            monitor.emitAbsolute(currentMetrics);
            logger.debug("First ISR metrics collection for broker {} (no delta available)", brokerId);
        }

        // Store current metrics for next delta computation
        previousIsrMetrics.put(brokerId, currentMetrics);

        if (logger.isDebugEnabled()) {
            logger.debug("Collected and emitted ISR metrics for broker {}", brokerId);
        }
    }

    /**
     * Collects replication health metrics (cluster-wide) and emits Monitor signals.
     * <p>
     * Queries the first broker endpoint (typically the controller) for cluster-wide metrics.
     *
     * @throws Exception if JMX collection fails
     */
    private void collectAndEmitHealth() throws Exception {
        // Use first broker endpoint (typically controller, but metrics work on any broker)
        BrokerEndpoint endpoint = config.endpoints().get(0);

        ReplicationHealthMetricsCollector collector = new ReplicationHealthMetricsCollector(
            endpoint.jmxUrl(),
            connectionPool
        );

        // Collect cluster-wide health metrics
        ReplicationHealthMetrics healthMetrics = collector.collect(clusterId);

        // Emit Monitor signals
        healthMonitor.emit(healthMetrics);

        if (logger.isDebugEnabled()) {
            logger.debug("Collected and emitted replication health for cluster {}", clusterId);
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
            logger.debug("IsrReplicationSensor not started, nothing to close");
            return;
        }

        logger.info("Stopping ISR replication sensor");
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

        logger.info("IsrReplicationSensor stopped");
    }
}
