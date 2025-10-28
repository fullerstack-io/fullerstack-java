package io.fullerstack.kafka.consumer.sensors;

import io.fullerstack.kafka.consumer.models.ConsumerMetrics;
import io.fullerstack.kafka.core.config.ConsumerEndpoint;
import io.fullerstack.kafka.core.config.ConsumerSensorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Consumer sensor for periodic metric collection.
 * <p>
 * <b>Important</b>: This sensor has NO Substrates dependencies. It emits metrics
 * via BiConsumer callback. The runtime manages Circuit/Cell hierarchy.
 * <p>
 * <b>Pattern</b>:
 * <pre>
 * ConsumerSensor (NO Substrates)
 *   → Collects metrics via ConsumerMetricsCollector
 *   → Emits via BiConsumer&lt;String, ConsumerMetrics&gt;
 *   → Runtime routes to correct Cell
 * </pre>
 * <p>
 * <b>Usage</b>:
 * <pre>{@code
 * ConsumerSensor sensor = new ConsumerSensor(
 *     config,
 *     (consumerId, metrics) -> {
 *         // Runtime handles Cell emission
 *         consumerCell.emit(metrics);
 *     }
 * );
 * sensor.start();
 * }</pre>
 */
public class ConsumerSensor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerSensor.class);

    private final ConsumerSensorConfig config;
    private final ConsumerMetricsCollector collector;
    private final BiConsumer<String, ConsumerMetrics> emitter;
    private final ScheduledExecutorService scheduler;
    private volatile boolean started = false;

    /**
     * Create ConsumerSensor.
     *
     * @param config  Sensor configuration
     * @param emitter Callback for metric emission (consumerId, metrics)
     */
    public ConsumerSensor(
            ConsumerSensorConfig config,
            BiConsumer<String, ConsumerMetrics> emitter
    ) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.emitter = Objects.requireNonNull(emitter, "emitter cannot be null");

        this.collector = new ConsumerMetricsCollector(
                config.bootstrapServers(),
                config.jmxPoolConfig()
        );

        this.scheduler = Executors.newScheduledThreadPool(
                1,
                Thread.ofVirtual().name("consumer-sensor-", 0).factory()
        );

        logger.info("ConsumerSensor created for {} consumers",
                config.endpoints().size());
    }

    /**
     * Start periodic metric collection.
     *
     * @throws IllegalStateException if already started
     */
    public void start() {
        if (started) {
            throw new IllegalStateException("ConsumerSensor already started");
        }

        scheduler.scheduleAtFixedRate(
                this::collectAll,
                0,
                config.collectionIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        started = true;
        logger.info("ConsumerSensor started, monitoring {} consumers",
                config.endpoints().size());
    }

    /**
     * Collect metrics for all configured consumers.
     */
    private void collectAll() {
        for (ConsumerEndpoint endpoint : config.endpoints()) {
            try {
                ConsumerMetrics metrics = collector.collect(
                        endpoint.jmxUrl(),
                        endpoint.consumerId(),
                        endpoint.consumerGroup()
                );

                emitter.accept(endpoint.consumerId(), metrics);

                logger.trace("Collected metrics for consumer: {} (lag: {})",
                        endpoint.consumerId(), metrics.totalLag());

            } catch (Exception e) {
                logger.error("Failed to collect metrics for consumer: {}",
                        endpoint.consumerId(), e);
            }
        }
    }

    /**
     * Check if sensor has been started.
     *
     * @return true if start() has been called
     */
    public boolean isStarted() {
        return started;
    }

    @Override
    public void close() {
        logger.info("Closing ConsumerSensor");

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        collector.close();
        logger.info("ConsumerSensor closed");
    }
}
