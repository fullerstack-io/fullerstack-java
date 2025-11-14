package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Monitors Kafka producer connection metrics and emits signals using RC7 Serventis API.
 * <p>
 * Collects JMX metrics for producer connections and emits signals for:
 * <ul>
 *   <li><b>Connection Count</b> (Gauge): INCREMENT (new), DECREMENT (closed)</li>
 *   <li><b>Connection Creation Rate</b> (Counter): INCREMENT for each new connection</li>
 *   <li><b>IO Wait Time</b> (Gauge): INCREMENT (rising), DECREMENT (improving)</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * // From circuit that provides instruments
 * Gauge connectionCountGauge = ...;
 * Counter creationRateCounter = ...;
 * Gauge ioWaitGauge = ...;
 *
 * ProducerConnectionObserver receptor = new ProducerConnectionObserver(
 *     "producer-1",
 *     "localhost:11001",  // JMX endpoint
 *     connectionCountGauge,
 *     creationRateCounter,
 *     ioWaitGauge
 * );
 *
 * receptor.start();  // Begins monitoring every 10 seconds
 *
 * // Later...
 * receptor.stop();
 * }</pre>
 *
 * @author Fullerstack
 * @see Gauge
 * @see Counter
 */
public class ProducerConnectionObserver implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ProducerConnectionObserver.class);

    // Thresholds
    private static final double IO_WAIT_WARNING_MS = 50.0;   // >50ms = warning
    private static final double IO_WAIT_CRITICAL_MS = 100.0; // >100ms = critical

    private final String producerId;
    private final String jmxEndpoint;
    private final Gauge connectionCountGauge;
    private final Counter creationRateCounter;
    private final Gauge ioWaitGauge;

    private final ScheduledExecutorService scheduler;
    private JMXConnector jmxConnector;
    private MBeanServerConnection mbeanServer;
    private volatile boolean running = false;

    // Previous values for delta calculations
    private int previousConnectionCount = 0;
    private double previousCreationRate = 0.0;
    private double previousIoWait = 0.0;

    /**
     * Creates a new producer connection receptor.
     *
     * @param producerId           Producer identifier (e.g., "producer-1")
     * @param jmxEndpoint         JMX endpoint (e.g., "localhost:11001")
     * @param connectionCountGauge Gauge for connection count
     * @param creationRateCounter Counter for connection creation rate
     * @param ioWaitGauge         Gauge for IO wait time
     */
    public ProducerConnectionObserver(
        String producerId,
        String jmxEndpoint,
        Gauge connectionCountGauge,
        Counter creationRateCounter,
        Gauge ioWaitGauge
    ) {
        this.producerId = producerId;
        this.jmxEndpoint = jmxEndpoint;
        this.connectionCountGauge = connectionCountGauge;
        this.creationRateCounter = creationRateCounter;
        this.ioWaitGauge = ioWaitGauge;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "producer-connection-receptor-" + producerId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts connection monitoring.
     * <p>
     * Connects to JMX and schedules connection checks every 10 seconds.
     */
    public void start() {
        if (running) {
            logger.warn("Producer connection receptor for {} is already running", producerId);
            return;
        }

        try {
            connectJmx();
            running = true;

            // Schedule connection monitoring every 10 seconds
            scheduler.scheduleAtFixedRate(
                this::collectAndEmit,
                0,          // Initial delay
                10,         // Period
                TimeUnit.SECONDS
            );

            logger.info("Started producer connection receptor for {} (JMX: {})", producerId, jmxEndpoint);

        } catch (Exception e) {
            logger.error("Failed to start producer connection receptor for {}", producerId, e);
            running = false;
            throw new RuntimeException("Failed to start producer connection receptor", e);
        }
    }

    /**
     * Stops connection monitoring and releases resources.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        closeJmx();
        logger.info("Stopped producer connection receptor for {}", producerId);
    }

    @Override
    public void close() {
        stop();
    }

    // ========================================
    // JMX Connection Management
    // ========================================

    private void connectJmx() throws IOException {
        String serviceUrl = "service:jmx:rmi:///jndi/rmi://" + jmxEndpoint + "/jmxrmi";
        JMXServiceURL url = new JMXServiceURL(serviceUrl);
        jmxConnector = JMXConnectorFactory.connect(url, null);
        mbeanServer = jmxConnector.getMBeanServerConnection();
        logger.debug("Connected to JMX endpoint: {}", jmxEndpoint);
    }

    private void closeJmx() {
        if (jmxConnector != null) {
            try {
                jmxConnector.close();
                logger.debug("Closed JMX connection to {}", jmxEndpoint);
            } catch (IOException e) {
                logger.warn("Error closing JMX connection to {}", jmxEndpoint, e);
            }
        }
    }

    // ========================================
    // Connection Metrics Collection & Emission
    // ========================================

    private void collectAndEmit() {
        try {
            // Collect JMX metrics
            int connectionCount = getJmxMetricInt("connection-count");
            double creationRate = getJmxMetricDouble("connection-creation-rate");
            double ioWaitNs = getJmxMetricDouble("io-wait-time-ns-avg");
            double ioWaitMs = ioWaitNs / 1_000_000.0; // Convert nanoseconds to milliseconds

            // Emit connection count signal (Gauge API - RC7)
            emitConnectionCountSignal(connectionCount);

            // Emit creation rate signal (Counter API - RC7)
            emitCreationRateSignal(creationRate);

            // Emit IO wait signal (Gauge API - RC7)
            emitIoWaitSignal(ioWaitMs);

            // Update previous values
            previousConnectionCount = connectionCount;
            previousCreationRate = creationRate;
            previousIoWait = ioWaitMs;

        } catch (Exception e) {
            logger.error("Error collecting connection metrics for producer {}", producerId, e);
            // Emit overflow on error (conservative - assume connection issue)
            connectionCountGauge.overflow();
        }
    }

    private void emitConnectionCountSignal(int connectionCount) {
        int delta = connectionCount - previousConnectionCount;

        if (delta > 0) {
            // Connections increased
            for (int i = 0; i < delta; i++) {
                connectionCountGauge.increment();
            }
            logger.debug("Producer {} connections INCREASED by {}: total={}",
                producerId, delta, connectionCount);

        } else if (delta < 0) {
            // Connections decreased
            int absoluteDelta = Math.abs(delta);
            for (int i = 0; i < absoluteDelta; i++) {
                connectionCountGauge.decrement();
            }
            logger.debug("Producer {} connections DECREASED by {}: total={}",
                producerId, absoluteDelta, connectionCount);
        }
        // No signal on no change
    }

    private void emitCreationRateSignal(double creationRate) {
        // Creation rate is connections/sec - convert to approximate count
        long approximateCreations = (long) (creationRate * 10);

        if (approximateCreations > 0) {
            creationRateCounter.increment();
            logger.debug("Producer {} connection creation rate: {:.2f} conn/sec (~{} in 10s)",
                producerId, creationRate, approximateCreations);
        }

        // Track rate changes
        double rateDelta = creationRate - previousCreationRate;
        if (rateDelta > 0.5) {
            logger.info("Producer {} connection creation rate INCREASING: {:.2f}→{:.2f}",
                producerId, previousCreationRate, creationRate);
        }
    }

    private void emitIoWaitSignal(double ioWaitMs) {
        double delta = ioWaitMs - previousIoWait;
        double threshold = 5.0; // 5ms change threshold

        if (delta > threshold) {
            ioWaitGauge.increment();
            logger.debug("Producer {} IO wait time INCREASED: {:.1f}→{:.1f} ms",
                producerId, previousIoWait, ioWaitMs);

            if (ioWaitMs >= IO_WAIT_CRITICAL_MS) {
                logger.error("Producer {} IO wait time CRITICAL: {:.1f} ms (threshold: {} ms)",
                    producerId, ioWaitMs, IO_WAIT_CRITICAL_MS);
                ioWaitGauge.overflow();
            } else if (ioWaitMs >= IO_WAIT_WARNING_MS) {
                logger.warn("Producer {} IO wait time WARNING: {:.1f} ms (threshold: {} ms)",
                    producerId, ioWaitMs, IO_WAIT_WARNING_MS);
            }

        } else if (delta < -threshold) {
            ioWaitGauge.decrement();
            logger.debug("Producer {} IO wait time DECREASED: {:.1f}→{:.1f} ms",
                producerId, previousIoWait, ioWaitMs);
        }
        // No signal on minor fluctuations
    }

    private int getJmxMetricInt(String metricName) throws Exception {
        // Build MBean object name for producer metrics
        ObjectName objectName = new ObjectName(
            String.format("kafka.producer:type=producer-metrics,client-id=%s", producerId)
        );

        // Get metric value
        Object value = mbeanServer.getAttribute(objectName, metricName);

        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else {
            throw new IllegalArgumentException(
                "Metric " + metricName + " is not a number: " + value
            );
        }
    }

    private double getJmxMetricDouble(String metricName) throws Exception {
        // Build MBean object name for producer metrics
        ObjectName objectName = new ObjectName(
            String.format("kafka.producer:type=producer-metrics,client-id=%s", producerId)
        );

        // Get metric value
        Object value = mbeanServer.getAttribute(objectName, metricName);

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        } else {
            throw new IllegalArgumentException(
                "Metric " + metricName + " is not a number: " + value
            );
        }
    }
}
