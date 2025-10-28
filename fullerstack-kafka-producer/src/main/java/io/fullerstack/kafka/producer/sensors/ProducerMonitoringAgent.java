package io.fullerstack.kafka.producer.sensors;

import io.fullerstack.kafka.core.config.ClusterConfig;
import io.fullerstack.kafka.producer.models.ProducerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Monitoring agent for periodic collection of Kafka producer metrics.
 * <p>
 * Manages scheduled collection of JMX metrics for multiple registered producers.
 * Uses a dedicated scheduler thread to periodically collect metrics and emit
 * them via the provided callback.
 * <p>
 * <b>Usage:</b>
 * <pre>{@code
 * ProducerMonitoringAgent agent = new ProducerMonitoringAgent(
 *     config,
 *     30_000,  // 30 second interval
 *     (producerId, metrics) -> producerCell.emit(metrics)
 * );
 *
 * agent.registerProducer("producer-1");
 * agent.registerProducer("producer-2");
 * agent.start();
 *
 * // Later...
 * agent.close();
 * }</pre>
 * <p>
 * <b>Thread Safety:</b> All methods are thread-safe.
 * Producers can be registered at any time, even after start().
 *
 * @see ProducerMetricsCollector
 * @see ProducerMetrics
 */
public class ProducerMonitoringAgent implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ProducerMonitoringAgent.class);
    private static final long DEFAULT_COLLECTION_INTERVAL_MS = 30_000; // 30 seconds
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    private final ClusterConfig config;
    private final long collectionIntervalMs;
    private final ProducerMetricsCollector collector;
    private final BiConsumer<String, ProducerMetrics> emitter;
    private final ScheduledExecutorService scheduler;
    private final Set<String> monitoredProducers;

    private volatile boolean started = false;
    private volatile boolean closed = false;

    /**
     * Create ProducerMonitoringAgent with default collection interval (30 seconds).
     *
     * @param config  Cluster configuration (provides JMX URL)
     * @param emitter Callback to emit collected metrics (producerId, metrics)
     */
    public ProducerMonitoringAgent(ClusterConfig config, BiConsumer<String, ProducerMetrics> emitter) {
        this(config, DEFAULT_COLLECTION_INTERVAL_MS, emitter);
    }

    /**
     * Create ProducerMonitoringAgent with custom collection interval.
     *
     * @param config              Cluster configuration (provides JMX URL)
     * @param collectionIntervalMs Collection interval in milliseconds (must be > 0)
     * @param emitter             Callback to emit collected metrics (producerId, metrics)
     * @throws NullPointerException     if config or emitter is null
     * @throws IllegalArgumentException if collectionIntervalMs <= 0
     */
    public ProducerMonitoringAgent(
            ClusterConfig config,
            long collectionIntervalMs,
            BiConsumer<String, ProducerMetrics> emitter
    ) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.emitter = Objects.requireNonNull(emitter, "emitter cannot be null");

        if (collectionIntervalMs <= 0) {
            throw new IllegalArgumentException("collectionIntervalMs must be > 0");
        }
        this.collectionIntervalMs = collectionIntervalMs;

        this.collector = new ProducerMetricsCollector();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "producer-monitor-" + config.clusterName());
            t.setDaemon(true);
            return t;
        });
        this.monitoredProducers = ConcurrentHashMap.newKeySet();

        logger.info("ProducerMonitoringAgent created for cluster {} (interval: {}ms)",
                config.clusterName(), collectionIntervalMs);
    }

    /**
     * Register a producer for monitoring.
     * <p>
     * Thread-safe. Can be called before or after start().
     * If called after start(), the producer will be included in the next collection cycle.
     *
     * @param producerId Producer client ID to monitor
     * @return true if producer was newly registered, false if already registered
     * @throws NullPointerException     if producerId is null
     * @throws IllegalArgumentException if producerId is blank
     * @throws IllegalStateException    if agent is closed
     */
    public boolean registerProducer(String producerId) {
        Objects.requireNonNull(producerId, "producerId cannot be null");
        if (producerId.isBlank()) {
            throw new IllegalArgumentException("producerId cannot be blank");
        }
        if (closed) {
            throw new IllegalStateException("Cannot register producer after agent is closed");
        }

        boolean added = monitoredProducers.add(producerId);
        if (added) {
            logger.info("Registered producer for monitoring: {}", producerId);
        }
        return added;
    }

    /**
     * Unregister a producer from monitoring.
     * <p>
     * Thread-safe. The producer will no longer be monitored in future collection cycles.
     *
     * @param producerId Producer client ID to stop monitoring
     * @return true if producer was removed, false if it wasn't registered
     */
    public boolean unregisterProducer(String producerId) {
        boolean removed = monitoredProducers.remove(producerId);
        if (removed) {
            logger.info("Unregistered producer from monitoring: {}", producerId);
        }
        return removed;
    }

    /**
     * Start scheduled collection of producer metrics.
     * <p>
     * Starts a background scheduler that collects metrics for all registered
     * producers at the configured interval.
     * <p>
     * The first collection cycle begins immediately after start().
     *
     * @throws IllegalStateException if already started or closed
     */
    public void start() {
        if (closed) {
            throw new IllegalStateException("Cannot start agent after it is closed");
        }
        if (started) {
            throw new IllegalStateException("Agent already started");
        }

        logger.info("Starting ProducerMonitoringAgent for cluster {} ({} producers)",
                config.clusterName(), monitoredProducers.size());

        scheduler.scheduleAtFixedRate(
                this::collectAllProducers,
                0,                        // Initial delay
                collectionIntervalMs,    // Period
                TimeUnit.MILLISECONDS
        );

        started = true;
    }

    /**
     * Get the number of currently monitored producers.
     *
     * @return Number of registered producers
     */
    public int getMonitoredProducerCount() {
        return monitoredProducers.size();
    }

    /**
     * Check if a producer is registered for monitoring.
     *
     * @param producerId Producer client ID to check
     * @return true if producer is registered
     */
    public boolean isMonitoring(String producerId) {
        return monitoredProducers.contains(producerId);
    }

    /**
     * Check if the agent has been started.
     *
     * @return true if start() has been called
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Collect metrics for all registered producers (one collection cycle).
     * <p>
     * Called periodically by the scheduler. Isolated failures for individual
     * producers don't affect collection for other producers.
     */
    private void collectAllProducers() {
        if (closed) {
            logger.warn("Attempted collection after agent closed");
            return;
        }

        if (monitoredProducers.isEmpty()) {
            logger.debug("No producers registered for monitoring");
            return;
        }

        logger.debug("Collecting metrics for {} producers", monitoredProducers.size());

        for (String producerId : monitoredProducers) {
            try {
                ProducerMetrics metrics = collector.collect(config.jmxUrl(), producerId);
                emitter.accept(producerId, metrics);

                logger.debug("Collected metrics for producer {}: sendRate={}, latency={}ms",
                        producerId, metrics.sendRate(), String.format("%.1f", metrics.avgLatencyMs()));

            } catch (Exception e) {
                logger.error("Failed to collect metrics for producer {}, continuing with others",
                        producerId, e);
                // Continue with other producers - isolated failure shouldn't stop collection
            }
        }
    }

    /**
     * Close the monitoring agent and release resources.
     * <p>
     * Stops the scheduler gracefully and waits up to 10 seconds for
     * in-flight collection tasks to complete.
     * <p>
     * After close(), the agent cannot be restarted.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        logger.info("Closing ProducerMonitoringAgent for cluster {} ({} producers)",
                config.clusterName(), monitoredProducers.size());

        closed = true;

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate within {} seconds, forcing shutdown",
                        SHUTDOWN_TIMEOUT_SECONDS);
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for scheduler shutdown");
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }

        monitoredProducers.clear();
    }

    @Override
    public String toString() {
        return String.format("ProducerMonitoringAgent[cluster=%s, interval=%dms, producers=%d, started=%s]",
                config.clusterName(), collectionIntervalMs, monitoredProducers.size(), started);
    }
}
