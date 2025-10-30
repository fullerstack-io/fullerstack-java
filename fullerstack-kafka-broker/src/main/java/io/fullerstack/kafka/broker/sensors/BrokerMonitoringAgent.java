package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.assessment.BrokerHealthAssessor;
import io.fullerstack.kafka.broker.baseline.BaselineService;
import io.fullerstack.kafka.broker.models.BrokerMetrics;
import io.fullerstack.kafka.core.config.ClusterConfig;
import io.fullerstack.kafka.core.config.JmxConnectionPoolConfig;
import io.fullerstack.serventis.signals.MonitorSignal;
import io.fullerstack.serventis.signals.VectorClock;
import io.fullerstack.serventis.signals.VectorClockManager;
import io.humainary.substrates.api.Substrates.Cortex;
import io.humainary.substrates.api.Substrates.Id;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.State;
import io.humainary.substrates.api.Substrates.Subject;
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
 * Monitors multiple Kafka brokers and emits interpreted MonitorSignals (signal-first architecture).
 * <p>
 * <b>Signal-First Architecture:</b>
 * This sensor INTERPRETS JMX data at the point of observation (where context exists)
 * and emits MonitorSignals with embedded meaning, NOT raw data bags.
 * <p>
 * Responsibilities:
 * - Schedule periodic JMX collection from all brokers in cluster
 * - Extract broker IDs from bootstrap servers
 * - INTERPRET JMX data using BrokerHealthAssessor with baseline context
 * - Emit MonitorSignals with assessment, evidence, recommendations
 * - Maintain VectorClock for causal ordering
 * - Handle collection failures gracefully
 * <p>
 * Usage:
 * <pre>
 * ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
 * BaselineService baselineService = new SimpleBaselineService();
 * BiConsumer&lt;Name, MonitorSignal&gt; emitter = (name, signal) -> ...;
 *
 * BrokerMonitoringAgent agent = new BrokerMonitoringAgent(config, baselineService, emitter);
 * agent.start();
 *
 * // ... later ...
 * agent.shutdown();
 * </pre>
 */
public class BrokerMonitoringAgent {
    private static final Logger logger = LoggerFactory.getLogger(BrokerMonitoringAgent.class);

    private final ClusterConfig config;
    private final BaselineService baselineService;
    private final BrokerHealthAssessor assessor;
    private final BiConsumer<Name, MonitorSignal> signalEmitter;
    private final Cortex cortex;
    private final JmxMetricsCollector collector;
    private final VectorClockManager vectorClock;
    private final ScheduledExecutorService scheduler;
    private final List<BrokerEndpoint> brokerEndpoints;
    private final JmxConnectionPool connectionPool;  // Optional connection pool (null if disabled)

    /**
     * Create a new BrokerMonitoringAgent.
     * <p>
     * If {@code config.jmxConnectionPoolConfig()} is non-null and enabled,
     * creates a JMX connection pool for high-frequency monitoring.
     *
     * @param config Cluster configuration with bootstrap servers
     * @param baselineService Service providing baseline expectations and trends
     * @param signalEmitter Callback to emit signals (receives broker Name and MonitorSignal)
     */
    public BrokerMonitoringAgent(
        ClusterConfig config,
        BaselineService baselineService,
        BiConsumer<Name, MonitorSignal> signalEmitter
    ) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.baselineService = Objects.requireNonNull(baselineService, "baselineService cannot be null");
        this.signalEmitter = Objects.requireNonNull(signalEmitter, "signalEmitter cannot be null");
        this.cortex = cortex();

        // Create assessor for signal-first interpretation
        this.assessor = new BrokerHealthAssessor(baselineService);

        // Create connection pool if configured
        JmxConnectionPoolConfig poolConfig = config.jmxConnectionPoolConfig();
        if (poolConfig != null && poolConfig.enabled()) {
            this.connectionPool = new JmxConnectionPool();
            logger.info("JMX connection pooling ENABLED for high-frequency monitoring");
        } else {
            this.connectionPool = null;
            logger.info("JMX connection pooling DISABLED (standard mode)");
        }

        // Create collector with optional connection pool
        this.collector = new JmxMetricsCollector(connectionPool);

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
     * Collect JMX data, interpret with assessor, and emit MonitorSignal.
     * <p>
     * <b>Signal-First Transformation:</b>
     * 1. Collect raw JMX metrics
     * 2. Convert to JmxData for assessor
     * 3. Create Subject (circuit + entity)
     * 4. INTERPRET using BrokerHealthAssessor (baseline comparison, trend detection, condition assessment)
     * 5. Emit MonitorSignal with embedded meaning (not raw data)
     *
     * @param endpoint Broker endpoint to collect from
     */
    private void collectAndEmit(BrokerEndpoint endpoint) {
        // 1. Collect raw JMX metrics
        BrokerMetrics rawMetrics = collector.collect(endpoint.jmxUrl);

        // 2. Convert to JmxData for assessor
        double heapPercent = rawMetrics.heapUsagePercent();
        BrokerHealthAssessor.JmxData jmxData = new BrokerHealthAssessor.JmxData(
            heapPercent,
            rawMetrics.cpuUsage(),
            rawMetrics.requestRate(),
            rawMetrics.underReplicatedPartitions(),
            rawMetrics.offlinePartitionsCount(),
            rawMetrics.networkProcessorAvgIdlePercent(),
            rawMetrics.requestHandlerAvgIdlePercent()
        );

        // 3. Create Name for broker
        Name brokerName = config.getBrokerName(cortex, endpoint.brokerId);

        // Create a simple Subject wrapping the broker name
        // In full circuit context, this would come from Cell.subject()
        Subject brokerSubject = createSubject(brokerName);

        // 4. INTERPRET using assessor (THIS IS WHERE INTERPRETATION HAPPENS)
        MonitorSignal signal = assessor.assess(
            endpoint.brokerId,
            brokerSubject,
            jmxData
        );

        // 5. Increment VectorClock and attach to signal
        vectorClock.increment(endpoint.brokerId);
        VectorClock clock = vectorClock.snapshot();
        MonitorSignal signalWithClock = signal.withClock(clock);

        // 6. Emit SIGNAL with meaning (not raw data)
        signalEmitter.accept(brokerName, signalWithClock);

        logger.debug("Emitted signal for broker {}: condition={}, confidence={}",
            endpoint.brokerId,
            signal.status().condition(),
            signal.status().confidence()
        );
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
     * Stops scheduled collection, waits for in-flight tasks to complete,
     * and closes the JMX connection pool (if enabled).
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

        // Close connection pool if present
        if (connectionPool != null) {
            connectionPool.close();
            logger.info("JMX connection pool closed");
        }
    }

    /**
     * Create a simple Subject for signal emission.
     * <p>
     * In full Circuit context, Subject would come from Cell.subject().
     * This is a simplified version for standalone sensor usage.
     *
     * @param name Broker name
     * @return Subject wrapping the name
     */
    @SuppressWarnings("unchecked")
    private Subject createSubject(final Name name) {
        return new Subject() {
            @Override
            public Id id() {
                return null; // No specific ID
            }

            @Override
            public Name name() {
                return name;
            }

            @Override
            public Class<MonitorSignal> type() {
                return MonitorSignal.class;
            }

            @Override
            public State state() {
                return null; // No state - Subject is used for signal identity only
            }

            @Override
            public int compareTo(Object o) {
                if (!(o instanceof Subject)) return -1;
                return name.toString().compareTo(((Subject) o).name().toString());
            }
        };
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
