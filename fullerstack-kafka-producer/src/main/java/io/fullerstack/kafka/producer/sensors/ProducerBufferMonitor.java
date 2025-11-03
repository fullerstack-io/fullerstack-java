package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.Queues.Queue;
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
 * Monitors Kafka producer buffer utilization and emits queue signals using RC1 Serventis API.
 * <p>
 * Collects JMX metrics from producer buffer and emits signals based on utilization thresholds:
 * <ul>
 *   <li><b>OVERFLOW</b> (≥95%): Buffer nearly full → {@code queue.overflow(utilization%)}</li>
 *   <li><b>PUT with pressure</b> (80-95%): High utilization → {@code queue.put(utilization%)}</li>
 *   <li><b>PUT normal</b> (<80%): Normal operation → {@code queue.put()}</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * QueueFlowCircuit circuit = new QueueFlowCircuit();
 * Queue bufferQueue = circuit.queueFor("producer-1.buffer");
 *
 * ProducerBufferMonitor monitor = new ProducerBufferMonitor(
 *     "producer-1",
 *     "localhost:11001",  // JMX endpoint
 *     bufferQueue
 * );
 *
 * monitor.start();  // Begins monitoring every 10 seconds
 *
 * // Later...
 * monitor.stop();
 * }</pre>
 *
 * @author Fullerstack
 * @see Queue
 */
public class ProducerBufferMonitor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ProducerBufferMonitor.class);

    // Thresholds for buffer state assessment
    private static final double OVERFLOW_THRESHOLD = 0.95;   // 95%+ = overflow
    private static final double PRESSURE_THRESHOLD = 0.80;   // 80-95% = pressure

    private final String producerId;
    private final String jmxEndpoint;
    private final Queue bufferQueue;

    private final ScheduledExecutorService scheduler;
    private JMXConnector jmxConnector;
    private MBeanServerConnection mbeanServer;
    private volatile boolean running = false;

    /**
     * Creates a new producer buffer monitor.
     *
     * @param producerId   Producer identifier (e.g., "producer-1")
     * @param jmxEndpoint  JMX endpoint (e.g., "localhost:11001")
     * @param bufferQueue  Queue instrument for emitting buffer signals
     */
    public ProducerBufferMonitor(
        String producerId,
        String jmxEndpoint,
        Queue bufferQueue
    ) {
        this.producerId = producerId;
        this.jmxEndpoint = jmxEndpoint;
        this.bufferQueue = bufferQueue;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "producer-buffer-monitor-" + producerId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts buffer monitoring.
     * <p>
     * Connects to JMX and schedules buffer checks every 10 seconds.
     */
    public void start() {
        if (running) {
            logger.warn("Producer buffer monitor for {} is already running", producerId);
            return;
        }

        try {
            connectJmx();
            running = true;

            // Schedule buffer monitoring every 10 seconds
            scheduler.scheduleAtFixedRate(
                this::collectAndEmit,
                0,          // Initial delay
                10,         // Period
                TimeUnit.SECONDS
            );

            logger.info("Started producer buffer monitor for {} (JMX: {})", producerId, jmxEndpoint);

        } catch (Exception e) {
            logger.error("Failed to start producer buffer monitor for {}", producerId, e);
            running = false;
            throw new RuntimeException("Failed to start producer buffer monitor", e);
        }
    }

    /**
     * Stops buffer monitoring and releases resources.
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
        logger.info("Stopped producer buffer monitor for {}", producerId);
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
    // Buffer Metrics Collection & Emission
    // ========================================

    private void collectAndEmit() {
        try {
            // Collect JMX metrics
            long availableBytes = getJmxMetric("buffer-available-bytes");
            long totalBytes = getJmxMetric("buffer-total-bytes");

            // Calculate utilization
            double utilization = 1.0 - ((double) availableBytes / totalBytes);
            long utilizationPercent = (long) (utilization * 100);

            // Emit signal based on buffer state (RC1 instrument pattern)
            emitBufferSignal(utilization, utilizationPercent);

        } catch (Exception e) {
            logger.error("Error collecting buffer metrics for producer {}", producerId, e);
            // Emit overflow on error (conservative approach)
            bufferQueue.overflow();
        }
    }

    private void emitBufferSignal(double utilization, long utilizationPercent) {
        if (utilization >= OVERFLOW_THRESHOLD) {
            // Buffer nearly full - OVERFLOW signal
            bufferQueue.overflow(utilizationPercent);
            logger.warn("Producer buffer {} OVERFLOW: utilization={}%",
                producerId, utilizationPercent);

        } else if (utilization >= PRESSURE_THRESHOLD) {
            // High utilization - PUT with pressure indication
            bufferQueue.put(utilizationPercent);
            logger.debug("Producer buffer {} PRESSURE: utilization={}%",
                producerId, utilizationPercent);

        } else {
            // Normal operation - PUT without units (or with low units)
            bufferQueue.put();
            logger.trace("Producer buffer {} NORMAL: utilization={}%",
                producerId, utilizationPercent);
        }
    }

    private long getJmxMetric(String metricName) throws Exception {
        // Build MBean object name for producer metrics
        // Example: kafka.producer:type=producer-metrics,client-id=producer-1
        ObjectName objectName = new ObjectName(
            String.format("kafka.producer:type=producer-metrics,client-id=%s", producerId)
        );

        // Get metric value
        Object value = mbeanServer.getAttribute(objectName, metricName);

        if (value instanceof Number) {
            return ((Number) value).longValue();
        } else {
            throw new IllegalArgumentException(
                "Metric " + metricName + " is not a number: " + value
            );
        }
    }
}
