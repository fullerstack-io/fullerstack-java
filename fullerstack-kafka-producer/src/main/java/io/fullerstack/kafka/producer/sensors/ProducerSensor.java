package io.fullerstack.kafka.producer.sensors;

import io.fullerstack.kafka.core.config.ProducerEndpoint;
import io.fullerstack.kafka.core.config.ProducerSensorConfig;
import io.fullerstack.kafka.producer.models.ProducerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Sensor for collecting JMX metrics from Kafka producers.
 * <p>
 * This sensor is responsible ONLY for metric collection - it has NO knowledge
 * of Substrates concepts (Cortex, Circuit, Cell, Name). The runtime layer is
 * responsible for wiring sensors to the Circuit/Cell hierarchy.
 * <p>
 * Responsibilities:
 * - Schedule periodic JMX collection from configured producers
 * - Extract metrics via JMX
 * - Emit metrics via callback (String producerId → ProducerMetrics)
 * - Handle collection failures gracefully
 * <p>
 * Usage:
 * <pre>
 * ProducerSensorConfig config = ProducerSensorConfig.defaults(endpoints);
 * BiConsumer&lt;String, ProducerMetrics&gt; emitter = (producerId, metrics) -> {...};
 *
 * ProducerSensor sensor = new ProducerSensor(config, emitter);
 * sensor.start();
 *
 * // ... later ...
 * sensor.close();
 * </pre>
 */
public class ProducerSensor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ProducerSensor.class);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    private final ProducerSensorConfig config;
    private final BiConsumer<String, ProducerMetrics> metricsEmitter;
    private final ProducerMetricsCollector collector;
    private final ScheduledExecutorService scheduler;
    private volatile boolean started;
    private volatile boolean closed;

    /**
     * Create a new ProducerSensor.
     *
     * @param config Sensor configuration with endpoints and collection settings
     * @param metricsEmitter Callback to emit metrics (receives producerId String and ProducerMetrics)
     */
    public ProducerSensor(ProducerSensorConfig config, BiConsumer<String, ProducerMetrics> metricsEmitter) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.metricsEmitter = Objects.requireNonNull(metricsEmitter, "metricsEmitter cannot be null");

        this.collector = new ProducerMetricsCollector();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "producer-sensor");
            t.setDaemon(true);
            return t;
        });

        this.started = false;
        this.closed = false;

        logger.info("ProducerSensor created (interval: {}ms, {} producers)",
                config.collectionIntervalMs(), config.endpoints().size());
    }

    /**
     * Start scheduled JMX collection.
     * <p>
     * Collects metrics from all configured producers every {@code collectionIntervalMs}.
     *
     * @throws IllegalStateException if already started or closed
     */
    public void start() {
        if (closed) {
            throw new IllegalStateException("Cannot start sensor after it is closed");
        }
        if (started) {
            throw new IllegalStateException("Sensor already started");
        }

        long intervalMs = config.collectionIntervalMs();
        logger.info("Starting producer sensor with {}ms collection interval for {} producers",
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
     * Collect metrics from all producers and emit via callback.
     */
    private void collectAndEmitAll() {
        if (closed) {
            logger.warn("Attempted collection after sensor closed");
            return;
        }

        if (config.endpoints().isEmpty()) {
            logger.debug("No producers configured for monitoring");
            return;
        }

        logger.debug("Collecting metrics for {} producers", config.endpoints().size());

        for (ProducerEndpoint endpoint : config.endpoints()) {
            try {
                collectAndEmit(endpoint);
            } catch (Exception e) {
                logger.error("Failed to collect/emit metrics for producer {}", endpoint.producerId(), e);
                // Continue with other producers - don't let one failure stop collection
            }
        }
    }

    /**
     * Collect metrics from a single producer and emit via callback.
     *
     * @param endpoint Producer endpoint to collect from
     */
    private void collectAndEmit(ProducerEndpoint endpoint) {
        // Collect metrics via JMX
        ProducerMetrics metrics = collector.collect(endpoint.jmxUrl(), endpoint.producerId());

        // Emit metrics via callback (just producerId string - no Name/Circuit knowledge)
        metricsEmitter.accept(endpoint.producerId(), metrics);

        logger.debug("Collected metrics for producer {}: sendRate={}, latency={}ms",
                endpoint.producerId(), metrics.sendRate(), String.format("%.1f", metrics.avgLatencyMs()));
    }

    /**
     * Get the number of currently monitored producers.
     *
     * @return Number of configured producers
     */
    public int getMonitoredProducerCount() {
        return config.endpoints().size();
    }

    /**
     * Check if the sensor has been started.
     *
     * @return true if start() has been called
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Close the sensor and release resources.
     * <p>
     * Stops the scheduler gracefully and waits up to 10 seconds for
     * in-flight collection tasks to complete.
     * <p>
     * After close(), the sensor cannot be restarted.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }

        logger.info("Closing ProducerSensor ({} producers)", config.endpoints().size());

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
    }

    @Override
    public String toString() {
        return String.format("ProducerSensor[interval=%dms, producers=%d, started=%s]",
                config.collectionIntervalMs(), config.endpoints().size(), started);
    }
}
