package io.fullerstack.kafka.broker.sensors;

import io.fullerstack.kafka.broker.models.JvmGcMetrics;
import io.fullerstack.kafka.broker.models.JvmMemoryMetrics;
import io.fullerstack.kafka.broker.models.SystemMetrics;
import io.fullerstack.kafka.broker.monitors.JvmHealthMonitor;
import io.fullerstack.kafka.broker.monitors.SystemMetricsMonitor;
import io.fullerstack.kafka.core.config.BrokerEndpoint;
import io.fullerstack.kafka.core.config.BrokerSensorConfig;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sensor for collecting broker health metrics (JVM, GC, CPU, FDs) via JMX and emitting Serventis signals (RC6).
 *
 * <p><b>Layer 2: OBSERVE + ORIENT Phases</b>
 * This sensor collects JMX metrics and emits signals using:
 * <ul>
 *   <li>Gauges: Heap, non-heap, CPU, file descriptors</li>
 *   <li>Counters: GC count, GC time</li>
 *   <li>Monitors: Broker health assessment (STABLE/DEGRADED/DEFECTIVE)</li>
 * </ul>
 *
 * <p><b>Metrics Collected (26 total):</b>
 * <ul>
 *   <li><b>JVM Memory (6):</b> Heap used/max/committed, non-heap used/max/committed</li>
 *   <li><b>GC (2):</b> Collection count, collection time (all collectors)</li>
 *   <li><b>CPU (2):</b> Process CPU, system CPU</li>
 *   <li><b>File Descriptors (2):</b> Open FDs, max FDs</li>
 *   <li><b>Derived (1):</b> Broker health assessment</li>
 * </ul>
 *
 * <p><b>Collection Intervals:</b>
 * <ul>
 *   <li>JVM/GC metrics: 10 seconds (default)</li>
 *   <li>System metrics: 30 seconds (default)</li>
 * </ul>
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("broker.health"));
 *
 * // Create channels for different signal types
 * Channel<Gauges.Sign> gaugesChannel = circuit.conduit(
 *     cortex().name("gauges"),
 *     Gauges::composer
 * ).channel(cortex().name("broker-health"));
 *
 * Channel<Counters.Sign> countersChannel = circuit.conduit(
 *     cortex().name("counters"),
 *     Counters::composer
 * ).channel(cortex().name("broker-health"));
 *
 * Channel<Monitors.Sign> monitorsChannel = circuit.conduit(
 *     cortex().name("monitors"),
 *     Monitors::composer
 * ).channel(cortex().name("broker-health"));
 *
 * BrokerSensorConfig config = BrokerSensorConfig.defaults(endpoints);
 * BrokerHealthSensor sensor = new BrokerHealthSensor(
 *     config,
 *     gaugesChannel,
 *     countersChannel,
 *     monitorsChannel,
 *     cortex().name("broker.health")
 * );
 *
 * sensor.start();
 * // ... later ...
 * sensor.close();
 * }</pre>
 */
public class BrokerHealthSensor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(BrokerHealthSensor.class);

    private static final long JVM_COLLECTION_INTERVAL_MS = 10_000;  // 10 seconds
    private static final long SYSTEM_COLLECTION_INTERVAL_MS = 30_000;  // 30 seconds

    private final BrokerSensorConfig config;
    private final JvmHealthMonitor jvmMonitor;
    private final SystemMetricsMonitor systemMonitor;
    private final JmxConnectionPool connectionPool;
    private final ScheduledExecutorService jvmScheduler;
    private final ScheduledExecutorService systemScheduler;
    private volatile boolean started;

    /**
     * Creates a new BrokerHealthSensor.
     *
     * @param config          Sensor configuration
     * @param gaugesChannel   Channel for Gauges signals
     * @param countersChannel Channel for Counters signals
     * @param monitorsChannel Channel for Monitors signals
     * @param circuitName     Circuit name for logging
     */
    public BrokerHealthSensor(
        BrokerSensorConfig config,
        Channel<Gauges.Sign> gaugesChannel,
        Channel<Counters.Sign> countersChannel,
        Channel<Monitors.Signal> monitorsChannel,
        Name circuitName
    ) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(gaugesChannel, "gaugesChannel cannot be null");
        Objects.requireNonNull(countersChannel, "countersChannel cannot be null");
        Objects.requireNonNull(monitorsChannel, "monitorsChannel cannot be null");
        Objects.requireNonNull(circuitName, "circuitName cannot be null");

        // Create monitors
        this.jvmMonitor = new JvmHealthMonitor(circuitName, gaugesChannel, countersChannel, monitorsChannel);
        this.systemMonitor = new SystemMetricsMonitor(circuitName, gaugesChannel);

        // Create connection pool
        this.connectionPool = new JmxConnectionPool();

        // Create schedulers with virtual threads
        this.jvmScheduler = Executors.newScheduledThreadPool(
            1,
            Thread.ofVirtual().name("jvm-health-sensor-", 0).factory()
        );
        this.systemScheduler = Executors.newScheduledThreadPool(
            1,
            Thread.ofVirtual().name("system-metrics-sensor-", 0).factory()
        );

        this.started = false;

        logger.info("BrokerHealthSensor initialized for {} brokers", config.endpoints().size());
    }

    /**
     * Starts scheduled health metrics collection.
     */
    public void start() {
        if (started) {
            logger.warn("BrokerHealthSensor already started");
            return;
        }

        logger.info("Starting broker health sensor: JVM={}ms, System={}ms",
            JVM_COLLECTION_INTERVAL_MS, SYSTEM_COLLECTION_INTERVAL_MS);

        // Schedule JVM/GC collection (10 seconds)
        jvmScheduler.scheduleAtFixedRate(
            this::collectJvmMetrics,
            0,
            JVM_COLLECTION_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        // Schedule system metrics collection (30 seconds)
        systemScheduler.scheduleAtFixedRate(
            this::collectSystemMetrics,
            0,
            SYSTEM_COLLECTION_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        started = true;
    }

    /**
     * Collects JVM memory and GC metrics from all brokers.
     */
    private void collectJvmMetrics() {
        for (BrokerEndpoint endpoint : config.endpoints()) {
            try {
                // Collect memory metrics
                JvmMemoryMetricsCollector memoryCollector = new JvmMemoryMetricsCollector(
                    endpoint.jmxUrl(),
                    connectionPool
                );
                JvmMemoryMetrics memoryMetrics = memoryCollector.collect(endpoint.brokerId());

                // Emit memory signals
                jvmMonitor.emitMemory(memoryMetrics);

                // Assess broker health
                jvmMonitor.assessBrokerHealth(memoryMetrics);

                // Collect GC metrics
                JvmGcMetricsCollector gcCollector = new JvmGcMetricsCollector(
                    endpoint.jmxUrl(),
                    connectionPool
                );
                List<JvmGcMetrics> gcMetrics = gcCollector.collect(endpoint.brokerId());

                // Emit GC signals
                for (JvmGcMetrics gc : gcMetrics) {
                    jvmMonitor.emitGc(gc);
                }

                logger.debug("Collected JVM metrics for broker {}", endpoint.brokerId());

            } catch (Exception e) {
                logger.error("Failed to collect JVM metrics for broker {}: {}",
                    endpoint.brokerId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Collects system metrics from all brokers.
     */
    private void collectSystemMetrics() {
        for (BrokerEndpoint endpoint : config.endpoints()) {
            try {
                SystemMetricsCollector collector = new SystemMetricsCollector(
                    endpoint.jmxUrl(),
                    connectionPool
                );
                SystemMetrics metrics = collector.collect(endpoint.brokerId());

                // Emit system signals
                systemMonitor.emit(metrics);

                logger.debug("Collected system metrics for broker {}", endpoint.brokerId());

            } catch (Exception e) {
                logger.error("Failed to collect system metrics for broker {}: {}",
                    endpoint.brokerId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Checks if the sensor has been started.
     *
     * @return true if started
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Stops the sensor and releases resources.
     */
    @Override
    public void close() {
        if (!started) {
            logger.debug("BrokerHealthSensor not started, nothing to close");
            return;
        }

        logger.info("Stopping broker health sensor");
        started = false;

        // Shutdown schedulers
        shutdownScheduler(jvmScheduler, "JVM");
        shutdownScheduler(systemScheduler, "System");

        // Close connection pool
        try {
            connectionPool.close();
        } catch (Exception e) {
            logger.error("Failed to close JMX connection pool", e);
        }

        logger.info("BrokerHealthSensor stopped");
    }

    private void shutdownScheduler(ScheduledExecutorService scheduler, String name) {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("{} scheduler did not terminate in 5 seconds, forcing shutdown", name);
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted while waiting for {} scheduler shutdown", name);
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
