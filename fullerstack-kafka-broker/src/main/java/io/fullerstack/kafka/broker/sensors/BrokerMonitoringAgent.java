package io.fullerstack.kafka.broker.sensors;

import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Name;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.core.config.ClusterConfig;
import io.fullerstack.serventis.signals.VectorClockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Monitors multiple Kafka brokers and emits metrics to appropriate broker Cells.
 * <p>
 * Responsibilities:
 * - Schedule periodic JMX collection from all brokers in cluster
 * - Extract broker IDs from bootstrap servers
 * - Emit BrokerMetrics to correct broker Cell in hierarchy
 * - Maintain VectorClock for causal ordering
 * - Handle collection failures gracefully
 * <p>
 * Usage:
 * <pre>
 * ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
 * BiConsumer&lt;Name, BrokerMetrics&gt; emitter = (name, metrics) -> ...;
 *
 * BrokerMonitoringAgent agent = new BrokerMonitoringAgent(config, emitter);
 * agent.start();
 *
 * // ... later ...
 * agent.shutdown();
 * </pre>
 */
public class BrokerMonitoringAgent {
    private static final Logger logger = LoggerFactory.getLogger(BrokerMonitoringAgent.class);

    private final ClusterConfig config;
    private final BiConsumer<Name, BrokerMetrics> metricsEmitter;
    private final Cortex cortex;
    private final JmxMetricsCollector collector;
    private final VectorClockManager vectorClock;
    private final ScheduledExecutorService scheduler;
    private final List<BrokerEndpoint> brokerEndpoints;

    /**
     * Create a new BrokerMonitoringAgent.
     *
     * @param config Cluster configuration with bootstrap servers
     * @param metricsEmitter Callback to emit metrics (receives broker Name and BrokerMetrics)
     */
    public BrokerMonitoringAgent(ClusterConfig config, BiConsumer<Name, BrokerMetrics> metricsEmitter) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.metricsEmitter = Objects.requireNonNull(metricsEmitter, "metricsEmitter cannot be null");
        this.cortex = cortex();
        this.collector = new JmxMetricsCollector();
        this.vectorClock = new VectorClockManager();
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "broker-monitoring-agent");
            t.setDaemon(true);
            return t;
        });
        this.brokerEndpoints = parseBrokerEndpoints();
    }

    /**
     * Parse bootstrap servers and JMX hosts to create broker endpoint list.
     *
     * @return List of broker endpoints with Kafka and JMX addresses
     */
    private List<BrokerEndpoint> parseBrokerEndpoints() {
        List<BrokerEndpoint> endpoints = new ArrayList<>();

        String[] kafkaEndpoints = config.bootstrapServers().split(",");
        String[] jmxEndpoints = config.jmxUrl().split(",");

        // Assume 1:1 mapping between Kafka and JMX endpoints
        if (kafkaEndpoints.length != jmxEndpoints.length) {
            logger.warn("Mismatch between Kafka endpoints ({}) and JMX endpoints ({}). Using minimum count.",
                    kafkaEndpoints.length, jmxEndpoints.length);
        }

        int count = Math.min(kafkaEndpoints.length, jmxEndpoints.length);
        for (int i = 0; i < count; i++) {
            String kafkaHost = kafkaEndpoints[i].split(":")[0].trim();
            String jmxHost = jmxEndpoints[i].trim();

            // Extract broker ID from Kafka hostname (e.g., "b-1.kafka.us-east-1.amazonaws.com" -> "b-1")
            String brokerId = ClusterConfig.extractBrokerId(kafkaHost);

            // Build JMX URL
            String jmxUrl = "service:jmx:rmi:///jndi/rmi://" + jmxHost + "/jmxrmi";

            endpoints.add(new BrokerEndpoint(brokerId, jmxUrl));
        }

        logger.info("Parsed {} broker endpoints from config", endpoints.size());
        return endpoints;
    }

    /**
     * Start scheduled JMX collection.
     * <p>
     * Collects metrics from all brokers every {@code collectionIntervalMs}.
     */
    public void start() {
        long intervalMs = config.collectionIntervalMs();
        logger.info("Starting broker monitoring with {}ms collection interval for {} brokers",
                intervalMs, brokerEndpoints.size());

        scheduler.scheduleAtFixedRate(
                this::collectAndEmitAllBrokers,
                0,  // Initial delay
                intervalMs,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Collect metrics from all brokers and emit to appropriate Cells.
     */
    private void collectAndEmitAllBrokers() {
        for (BrokerEndpoint endpoint : brokerEndpoints) {
            try {
                collectAndEmit(endpoint);
            } catch (Exception e) {
                logger.error("Failed to collect/emit metrics for broker {}", endpoint.brokerId, e);
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
        BrokerMetrics metrics = collector.collect(endpoint.jmxUrl);

        // Override broker ID to ensure it matches our extracted ID
        // (JmxMetricsCollector extracts from JMX URL, but we want to use ClusterConfig extraction)
        BrokerMetrics normalizedMetrics = new BrokerMetrics(
                endpoint.brokerId,  // Use our extracted broker ID
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

        // Increment VectorClock before emission
        vectorClock.increment(endpoint.brokerId);

        // Create broker Name using ClusterConfig
        Name brokerName = config.getBrokerName(cortex, endpoint.brokerId);

        // Emit metrics via callback
        metricsEmitter.accept(brokerName, normalizedMetrics);

        logger.debug("Emitted metrics for broker {} to Name {}", endpoint.brokerId, brokerName);
    }

    /**
     * Get current VectorClock snapshot.
     *
     * @return Current VectorClock state
     */
    public VectorClockManager getVectorClock() {
        return vectorClock;
    }

    /**
     * Get list of broker endpoints being monitored.
     *
     * @return Immutable list of broker endpoints
     */
    public List<BrokerEndpoint> getBrokerEndpoints() {
        return List.copyOf(brokerEndpoints);
    }

    /**
     * Gracefully shutdown monitoring agent.
     * <p>
     * Stops scheduled collection and waits for in-flight tasks to complete.
     */
    public void shutdown() {
        logger.info("Shutting down broker monitoring agent");
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
    }

    /**
     * Broker endpoint with Kafka and JMX addresses.
     */
    public record BrokerEndpoint(String brokerId, String jmxUrl) {
        public BrokerEndpoint {
            Objects.requireNonNull(brokerId, "brokerId cannot be null");
            Objects.requireNonNull(jmxUrl, "jmxUrl cannot be null");
        }
    }
}
