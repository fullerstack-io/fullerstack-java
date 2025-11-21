package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import io.humainary.substrates.ext.serventis.ext.Queues.Queue;
import io.humainary.substrates.ext.serventis.ext.Services;
import io.humainary.substrates.ext.serventis.ext.Services.Service;
import io.humainary.substrates.ext.serventis.ext.Services.Dimension;
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
 * Monitors Kafka producer buffer utilization and emits signals using RC7 Serventis API.
 * <p>
 * Collects JMX metrics from producer buffer and emits signals based on utilization thresholds:
 * <ul>
 *   <li><b>Buffer Available Bytes</b> (Queue): OVERFLOW (≥95%), PUT (normal)</li>
 *   <li><b>Buffer Total Bytes</b> (Gauge): INCREMENT (growing), DECREMENT (shrinking)</li>
 *   <li><b>Buffer Exhausted Total</b> (Counter): INCREMENT (each exhaustion event)</li>
 *   <li><b>Batch Size Avg</b> (Gauge): INCREMENT (growing), DECREMENT (shrinking)</li>
 *   <li><b>Records Per Request Avg</b> (Gauge): INCREMENT (growing), DECREMENT (shrinking)</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * // From circuit that provides instruments
 * Queue bufferQueue = ...;
 * Gauge totalBytesGauge = ...;
 * Counter exhaustedCounter = ...;
 * Gauge batchSizeGauge = ...;
 * Gauge recordsPerRequestGauge = ...;
 *
 * ProducerBufferMonitor monitor = new ProducerBufferMonitor(
 *     "producer-1",
 *     "localhost:11001",  // JMX endpoint
 *     bufferQueue,
 *     totalBytesGauge,
 *     exhaustedCounter,
 *     batchSizeGauge,
 *     recordsPerRequestGauge
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
 * @see Gauge
 * @see Counter
 */
public class ProducerBufferMonitor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ProducerBufferMonitor.class);

    // Thresholds for buffer state assessment
    private static final double OVERFLOW_THRESHOLD = 0.95;   // 95%+ = overflow
    private static final double PRESSURE_THRESHOLD = 0.80;   // 80-95% = pressure

    private final String producerId;
    private final String jmxEndpoint;
    private final Queue bufferQueue;
    private final Gauge totalBytesGauge;
    private final Counter exhaustedCounter;
    private final Gauge batchSizeGauge;
    private final Gauge recordsPerRequestGauge;
    private final Service monitorService;  // Optional service for lifecycle tracking

    private final ScheduledExecutorService scheduler;
    private JMXConnector jmxConnector;
    private MBeanServerConnection mbeanServer;
    private volatile boolean running = false;

    // Previous values for delta calculations
    private long previousTotalBytes = 0;
    private double previousBatchSize = 0.0;
    private double previousRecordsPerRequest = 0.0;
    private long previousExhaustedTotal = 0;

    /**
     * Creates a new producer buffer monitor.
     *
     * @param producerId              Producer identifier (e.g., "producer-1")
     * @param jmxEndpoint            JMX endpoint (e.g., "localhost:11001")
     * @param bufferQueue            Queue instrument for buffer pressure signals
     * @param totalBytesGauge        Gauge instrument for total buffer bytes
     * @param exhaustedCounter       Counter instrument for buffer exhaustion events
     * @param batchSizeGauge         Gauge instrument for average batch size
     * @param recordsPerRequestGauge Gauge instrument for average records per request
     */
    public ProducerBufferMonitor(
        String producerId,
        String jmxEndpoint,
        Queue bufferQueue,
        Gauge totalBytesGauge,
        Counter exhaustedCounter,
        Gauge batchSizeGauge,
        Gauge recordsPerRequestGauge
    ) {
        this(producerId, jmxEndpoint, bufferQueue, totalBytesGauge, exhaustedCounter,
            batchSizeGauge, recordsPerRequestGauge, null);
    }

    /**
     * Creates a new producer buffer monitor with service lifecycle tracking.
     *
     * @param producerId              Producer identifier (e.g., "producer-1")
     * @param jmxEndpoint            JMX endpoint (e.g., "localhost:11001")
     * @param bufferQueue            Queue instrument for buffer pressure signals
     * @param totalBytesGauge        Gauge instrument for total buffer bytes
     * @param exhaustedCounter       Counter instrument for buffer exhaustion events
     * @param batchSizeGauge         Gauge instrument for average batch size
     * @param recordsPerRequestGauge Gauge instrument for average records per request
     * @param monitorService         Service instrument for lifecycle tracking (optional)
     */
    public ProducerBufferMonitor(
        String producerId,
        String jmxEndpoint,
        Queue bufferQueue,
        Gauge totalBytesGauge,
        Counter exhaustedCounter,
        Gauge batchSizeGauge,
        Gauge recordsPerRequestGauge,
        Service monitorService
    ) {
        this.producerId = producerId;
        this.jmxEndpoint = jmxEndpoint;
        this.bufferQueue = bufferQueue;
        this.totalBytesGauge = totalBytesGauge;
        this.exhaustedCounter = exhaustedCounter;
        this.batchSizeGauge = batchSizeGauge;
        this.recordsPerRequestGauge = recordsPerRequestGauge;
        this.monitorService = monitorService;
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

            // Schedule buffer monitoring every 2 seconds (for responsive demo)
            scheduler.scheduleAtFixedRate(
                this::collectAndEmit,
                0,          // Initial delay
                2,          // Period (2s for live demo feel)
                TimeUnit.SECONDS
            );

            // Emit service START signal (meta-monitoring)
            if (monitorService != null) {
                monitorService.start(Dimension.CALLER);
            }

            logger.info("Started producer buffer monitor for {} (JMX: {})", producerId, jmxEndpoint);

        } catch (Exception e) {
            logger.error("Failed to start producer buffer monitor for {}", producerId, e);

            // Emit service FAIL signal (failed to start)
            if (monitorService != null) {
                monitorService.fail(Dimension.CALLER);
            }

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

        // Emit service STOP signal (meta-monitoring)
        if (monitorService != null) {
            monitorService.stop(Dimension.CALLER);
        }

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
        // Emit service CALL signal (attempting JMX poll)
        if (monitorService != null) {
            monitorService.call(Dimension.CALLER);
        }

        try {
            // Collect JMX metrics
            long availableBytes = getJmxMetricLong("buffer-available-bytes");
            long totalBytes = getJmxMetricLong("buffer-total-bytes");
            long exhaustedTotal = getJmxMetricLong("buffer-exhausted-total");
            double batchSizeAvg = getJmxMetricDouble("batch-size-avg");
            double recordsPerRequestAvg = getJmxMetricDouble("records-per-request-avg");

            // Calculate utilization
            double utilization = 1.0 - ((double) availableBytes / totalBytes);
            long utilizationPercent = (long) (utilization * 100);

            // Emit buffer availability signal (Queue API - RC7)
            emitBufferSignal(utilization, utilizationPercent);

            // Emit total bytes signal (Gauge API - RC7)
            emitTotalBytesSignal(totalBytes);

            // Emit exhausted counter signal (Counter API - RC7)
            emitExhaustedSignal(exhaustedTotal);

            // Emit batch size signal (Gauge API - RC7)
            emitBatchSizeSignal(batchSizeAvg);

            // Emit records per request signal (Gauge API - RC7)
            emitRecordsPerRequestSignal(recordsPerRequestAvg);

            // Update previous values
            previousTotalBytes = totalBytes;
            previousBatchSize = batchSizeAvg;
            previousRecordsPerRequest = recordsPerRequestAvg;
            previousExhaustedTotal = exhaustedTotal;

            // Emit service SUCCESS signal (JMX poll succeeded)
            if (monitorService != null) {
                monitorService.success(Dimension.CALLER);
            }

        } catch (Exception e) {
            logger.error("Error collecting buffer metrics for producer {}", producerId, e);

            // Emit service FAIL signal (JMX poll failed - connection lost?)
            if (monitorService != null) {
                monitorService.fail(Dimension.CALLER);
            }

            // Emit overflow on error (conservative approach)
            bufferQueue.overflow();
        }
    }

    private void emitBufferSignal(double utilization, long utilizationPercent) {
        if (utilization >= OVERFLOW_THRESHOLD) {
            // Buffer nearly full - OVERFLOW signal
            bufferQueue.overflow();
            logger.warn("Producer buffer {} OVERFLOW: utilization={}%",
                producerId, utilizationPercent);

        } else if (utilization >= PRESSURE_THRESHOLD) {
            // High utilization - enqueue with pressure indication
            bufferQueue.enqueue();
            logger.debug("Producer buffer {} PRESSURE: utilization={}%",
                producerId, utilizationPercent);

        } else {
            // Normal operation - PUT without units (or with low units)
            bufferQueue.enqueue();
            logger.trace("Producer buffer {} NORMAL: utilization={}%",
                producerId, utilizationPercent);
        }
    }

    private void emitTotalBytesSignal(long totalBytes) {
        long delta = totalBytes - previousTotalBytes;

        if (delta > 0) {
            totalBytesGauge.increment();
            logger.debug("Producer buffer {} total bytes INCREASED: {}→{} bytes",
                producerId, previousTotalBytes, totalBytes);
        } else if (delta < 0) {
            totalBytesGauge.decrement();
            logger.debug("Producer buffer {} total bytes DECREASED: {}→{} bytes",
                producerId, previousTotalBytes, totalBytes);
        }
        // No signal on no change (steady state)
    }

    private void emitExhaustedSignal(long exhaustedTotal) {
        long delta = exhaustedTotal - previousExhaustedTotal;

        if (delta > 0) {
            // Buffer exhaustion events occurred
            for (int i = 0; i < delta; i++) {
                exhaustedCounter.increment();
            }
            logger.warn("Producer buffer {} EXHAUSTED: {} new exhaustion events (total={})",
                producerId, delta, exhaustedTotal);
        }
        // Counter is monotonic - only increment
    }

    private void emitBatchSizeSignal(double batchSizeAvg) {
        double delta = batchSizeAvg - previousBatchSize;
        double threshold = 10.0; // 10 bytes change threshold to avoid noise

        if (delta > threshold) {
            batchSizeGauge.increment();
            logger.debug("Producer {} batch size INCREASED: {:.1f}→{:.1f} bytes",
                producerId, previousBatchSize, batchSizeAvg);
        } else if (delta < -threshold) {
            batchSizeGauge.decrement();
            logger.debug("Producer {} batch size DECREASED: {:.1f}→{:.1f} bytes",
                producerId, previousBatchSize, batchSizeAvg);
        }
        // No signal on minor fluctuations
    }

    private void emitRecordsPerRequestSignal(double recordsPerRequestAvg) {
        double delta = recordsPerRequestAvg - previousRecordsPerRequest;
        double threshold = 0.1; // 0.1 records threshold

        if (delta > threshold) {
            recordsPerRequestGauge.increment();
            logger.debug("Producer {} records/request INCREASED: {:.2f}→{:.2f}",
                producerId, previousRecordsPerRequest, recordsPerRequestAvg);
        } else if (delta < -threshold) {
            recordsPerRequestGauge.decrement();
            logger.debug("Producer {} records/request DECREASED: {:.2f}→{:.2f}",
                producerId, previousRecordsPerRequest, recordsPerRequestAvg);
        }
        // No signal on minor fluctuations
    }

    private long getJmxMetricLong(String metricName) throws Exception {
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
