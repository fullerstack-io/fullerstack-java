package io.fullerstack.kafka.msk;

import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.broker.sensors.BrokerSensor;
import io.fullerstack.kafka.core.config.BrokerEndpoint;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Dynamically discovers MSK broker changes and manages Epic 1 monitoring lifecycle.
 * <p>
 * This is INFRASTRUCTURE management (Epic 5) - Epic 1's BrokerSensor emits RC1 signals.
 * <p>
 * Epic 5 responsibilities:
 * <ul>
 *   <li>Poll MSK API for cluster topology changes</li>
 *   <li>Detect broker additions/removals</li>
 *   <li>Start/stop Epic 1 BrokerSensor instances</li>
 *   <li>Handle MSK API failures gracefully</li>
 * </ul>
 * <p>
 * Epic 1 responsibilities (BrokerSensor):
 * <ul>
 *   <li>Collect JMX metrics from brokers</li>
 *   <li>Emit Monitors.Signal via RC1 API</li>
 *   <li>Transform BrokerMetrics → MonitorSignal</li>
 * </ul>
 *
 * @see MSKClusterDiscovery
 * @see BrokerSensor
 */
public class DynamicBrokerDiscovery implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DynamicBrokerDiscovery.class);

    private final MSKClusterDiscovery discovery;
    private final String clusterArn;
    private final Map<String, BrokerSensor> activeSensors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final DynamicBrokerDiscoveryConfig config;
    private final BiConsumer<String, BrokerMetrics> metricsEmitter;

    /**
     * Creates a new dynamic broker discovery instance.
     *
     * @param discovery MSK cluster discovery service
     * @param clusterArn AWS MSK cluster ARN
     * @param config discovery configuration (poll interval, initial delay)
     * @param metricsEmitter callback for Epic 1 BrokerSensor to emit metrics
     */
    public DynamicBrokerDiscovery(
        MSKClusterDiscovery discovery,
        String clusterArn,
        DynamicBrokerDiscoveryConfig config,
        BiConsumer<String, BrokerMetrics> metricsEmitter
    ) {
        this.discovery = Objects.requireNonNull(discovery, "discovery cannot be null");
        this.clusterArn = Objects.requireNonNull(clusterArn, "clusterArn cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.metricsEmitter = Objects.requireNonNull(metricsEmitter, "metricsEmitter cannot be null");
        this.scheduler = Executors.newScheduledThreadPool(
            1,
            r -> {
                Thread t = new Thread(r, "msk-broker-discovery");
                t.setDaemon(true);
                return t;
            }
        );
    }

    /**
     * Start polling MSK for broker changes.
     * <p>
     * Begins scheduled polling with configured initial delay and poll interval.
     */
    public void start() {
        logger.info("Starting dynamic broker discovery for cluster: {} (poll interval: {}s, initial delay: {}s)",
            clusterArn, config.pollInterval().toSeconds(), config.initialDelay().toSeconds());

        scheduler.scheduleAtFixedRate(
            this::syncBrokers,
            config.initialDelay().toSeconds(),  // Configurable initial delay
            config.pollInterval().toSeconds(),
            TimeUnit.SECONDS
        );
    }

    /**
     * Poll MSK and sync Epic 1 monitoring sensors.
     * <p>
     * Compares current MSK broker set with active sensors and:
     * <ul>
     *   <li>Starts sensors for new brokers</li>
     *   <li>Stops sensors for removed brokers</li>
     *   <li>Skips sync if cluster not ACTIVE</li>
     *   <li>Handles MSK API failures gracefully (logs, continues)</li>
     * </ul>
     * <p>
     * Package-private for testing.
     */
    void syncBrokers() {
        try {
            ClusterMetadata metadata = discovery.discover(clusterArn);

            // Skip sync if cluster not active
            if (!metadata.isActive()) {
                logger.warn("Cluster {} not active (state: {}), skipping sync",
                    clusterArn, metadata.state());
                return;
            }

            Set<String> currentBrokerIds = metadata.brokers().stream()
                .map(io.fullerstack.kafka.msk.BrokerEndpoint::brokerId)
                .collect(Collectors.toSet());

            // Start Epic 1 monitoring for new brokers
            currentBrokerIds.stream()
                .filter(id -> !activeSensors.containsKey(id))
                .forEach(id -> startBrokerSensor(metadata, id));

            // Stop Epic 1 monitoring for removed brokers
            activeSensors.keySet().stream()
                .filter(id -> !currentBrokerIds.contains(id))
                .collect(Collectors.toList())  // Collect to avoid ConcurrentModificationException
                .forEach(this::stopBrokerSensor);

            logger.debug("Broker sync complete: {} active sensors", activeSensors.size());

        } catch (MSKDiscoveryException e) {
            logger.error("Failed to sync brokers, will retry on next poll", e);
        }
    }

    /**
     * Start Epic 1's BrokerSensor for a new broker.
     * <p>
     * Creates Epic 1 BrokerSensor with:
     * <ul>
     *   <li>BrokerSensorConfig from MSK discovery data</li>
     *   <li>metricsEmitter callback for Epic 1 to emit metrics</li>
     * </ul>
     * <p>
     * Epic 1's BrokerSensor emits Monitors.Signal via monitor.status() (RC1 API).
     *
     * @param metadata cluster metadata from MSK discovery
     * @param brokerId broker ID to start monitoring
     */
    private void startBrokerSensor(ClusterMetadata metadata, String brokerId) {
        io.fullerstack.kafka.msk.BrokerEndpoint mskEndpoint = metadata.brokers().stream()
            .filter(b -> b.brokerId().equals(brokerId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Broker not found: " + brokerId));

        try {
            // Parse Kafka endpoint (host:port)
            String[] parts = mskEndpoint.endpoint().split(":");
            if (parts.length != 2) {
                throw new IllegalStateException("Invalid endpoint format: " + mskEndpoint.endpoint());
            }
            String kafkaHost = parts[0];
            int kafkaPort = Integer.parseInt(parts[1]);

            // Build JMX URL (HTTP metrics endpoint for AWS MSK)
            String jmxUrl = String.format("http://%s:%d/metrics", kafkaHost, mskEndpoint.jmxPort());

            // Convert MSK BrokerEndpoint to Epic 1's BrokerEndpoint
            BrokerEndpoint epic1Endpoint = new BrokerEndpoint(
                brokerId,
                kafkaHost,
                kafkaPort,
                jmxUrl
            );

            // Create Epic 1 sensor config (30s collection interval)
            BrokerSensorConfig config = BrokerSensorConfig.withoutPooling(
                java.util.List.of(epic1Endpoint),
                30_000  // 30 seconds in ms
            );

            // Create Epic 1's BrokerSensor with metrics emitter callback
            BrokerSensor sensor = new BrokerSensor(config, metricsEmitter);
            sensor.start();  // Start JMX collection

            activeSensors.put(brokerId, sensor);

            logger.info("Started Epic 1 sensor for broker: {} (endpoint: {})",
                brokerId, mskEndpoint.endpoint());

        } catch (Exception e) {
            logger.error("Failed to start sensor for broker: {}", brokerId, e);
            // Don't rethrow - continue with other brokers, retry on next sync
        }
    }

    /**
     * Stop Epic 1's BrokerSensor for a removed broker.
     * <p>
     * Removes sensor from active map and calls close() for graceful shutdown.
     *
     * @param brokerId broker ID to stop monitoring
     */
    private void stopBrokerSensor(String brokerId) {
        BrokerSensor sensor = activeSensors.remove(brokerId);

        if (sensor != null) {
            try {
                sensor.close();  // Graceful shutdown
                logger.info("Stopped Epic 1 sensor for broker: {}", brokerId);
            } catch (Exception e) {
                logger.error("Error stopping sensor for broker: {}", brokerId, e);
                // Don't rethrow - sensor already removed from map
            }
        }
    }

    /**
     * Get count of actively monitored brokers.
     * <p>
     * Used for testing and observability.
     *
     * @return number of active BrokerSensor instances
     */
    public int getActiveSensorCount() {
        return activeSensors.size();
    }

    /**
     * Shutdown discovery and stop all active sensors.
     * <p>
     * Steps:
     * <ul>
     *   <li>Shutdown scheduler (wait up to 10s for current tasks)</li>
     *   <li>Stop all Epic 1 BrokerSensor instances</li>
     * </ul>
     */
    @Override
    public void close() {
        logger.info("Shutting down dynamic broker discovery");

        // Shutdown scheduler
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Stop all Epic 1 sensors
        activeSensors.keySet().stream()
            .collect(Collectors.toList())  // Collect to avoid ConcurrentModificationException
            .forEach(this::stopBrokerSensor);
    }
}
