package io.fullerstack.kafka.consumer.sensors;

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
 * Monitors Kafka consumer fetch metrics and emits signals using RC7 Serventis API.
 * <p>
 * Collects JMX metrics for consumer fetch operations and emits signals for:
 * <ul>
 *   <li><b>Fetch Rate</b> (Counter): INCREMENT for each fetch</li>
 *   <li><b>Bytes Consumed Rate</b> (Counter): INCREMENT for bytes consumed</li>
 *   <li><b>Records Consumed Rate</b> (Counter): INCREMENT for records consumed</li>
 *   <li><b>Fetch Size (avg)</b> (Gauge): INCREMENT (growing), DECREMENT (shrinking)</li>
 *   <li><b>Fetch Latency (avg)</b> (Gauge): INCREMENT (rising), DECREMENT (improving), OVERFLOW (critical)</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * // From circuit that provides instruments
 * Counter fetchRateCounter = ...;
 * Counter bytesConsumedCounter = ...;
 * Counter recordsConsumedCounter = ...;
 * Gauge fetchSizeGauge = ...;
 * Gauge fetchLatencyGauge = ...;
 *
 * ConsumerFetchObserver observer = new ConsumerFetchObserver(
 *     "consumer-1",
 *     "localhost:11001",  // JMX endpoint
 *     fetchRateCounter,
 *     bytesConsumedCounter,
 *     recordsConsumedCounter,
 *     fetchSizeGauge,
 *     fetchLatencyGauge
 * );
 *
 * observer.start();  // Begins monitoring every 10 seconds
 *
 * // Later...
 * observer.stop();
 * }</pre>
 *
 * @author Fullerstack
 * @see Counter
 * @see Gauge
 */
public class ConsumerFetchObserver implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerFetchObserver.class);

    // Thresholds
    private static final double FETCH_LATENCY_WARNING_MS = 500.0;   // >500ms = warning
    private static final double FETCH_LATENCY_CRITICAL_MS = 1000.0; // >1000ms = critical
    private static final double FETCH_SIZE_CHANGE_THRESHOLD_BYTES = 10_000; // 10KB

    private final String consumerId;
    private final String jmxEndpoint;
    private final Counter fetchRateCounter;
    private final Counter bytesConsumedCounter;
    private final Counter recordsConsumedCounter;
    private final Gauge fetchSizeGauge;
    private final Gauge fetchLatencyGauge;

    private final ScheduledExecutorService scheduler;
    private JMXConnector jmxConnector;
    private MBeanServerConnection mbeanServer;
    private volatile boolean running = false;

    // Previous values for delta calculations
    private double previousFetchRate = 0.0;
    private double previousBytesRate = 0.0;
    private double previousRecordsRate = 0.0;
    private double previousFetchSize = 0.0;
    private double previousFetchLatency = 0.0;

    /**
     * Creates a new consumer fetch observer.
     *
     * @param consumerId              Consumer identifier (e.g., "consumer-1")
     * @param jmxEndpoint            JMX endpoint (e.g., "localhost:11001")
     * @param fetchRateCounter       Counter for fetch rate
     * @param bytesConsumedCounter   Counter for bytes consumed rate
     * @param recordsConsumedCounter Counter for records consumed rate
     * @param fetchSizeGauge         Gauge for fetch size
     * @param fetchLatencyGauge      Gauge for fetch latency
     */
    public ConsumerFetchObserver(
        String consumerId,
        String jmxEndpoint,
        Counter fetchRateCounter,
        Counter bytesConsumedCounter,
        Counter recordsConsumedCounter,
        Gauge fetchSizeGauge,
        Gauge fetchLatencyGauge
    ) {
        this.consumerId = consumerId;
        this.jmxEndpoint = jmxEndpoint;
        this.fetchRateCounter = fetchRateCounter;
        this.bytesConsumedCounter = bytesConsumedCounter;
        this.recordsConsumedCounter = recordsConsumedCounter;
        this.fetchSizeGauge = fetchSizeGauge;
        this.fetchLatencyGauge = fetchLatencyGauge;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "consumer-fetch-observer-" + consumerId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts fetch monitoring.
     * <p>
     * Connects to JMX and schedules fetch checks every 10 seconds.
     */
    public void start() {
        if (running) {
            logger.warn("Consumer fetch observer for {} is already running", consumerId);
            return;
        }

        try {
            connectJmx();
            running = true;

            // Schedule fetch monitoring every 10 seconds
            scheduler.scheduleAtFixedRate(
                this::collectAndEmit,
                0,          // Initial delay
                10,         // Period
                TimeUnit.SECONDS
            );

            logger.info("Started consumer fetch observer for {} (JMX: {})", consumerId, jmxEndpoint);

        } catch (Exception e) {
            logger.error("Failed to start consumer fetch observer for {}", consumerId, e);
            running = false;
            throw new RuntimeException("Failed to start consumer fetch observer", e);
        }
    }

    /**
     * Stops fetch monitoring and releases resources.
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
        logger.info("Stopped consumer fetch observer for {}", consumerId);
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
    // Fetch Metrics Collection & Emission
    // ========================================

    private void collectAndEmit() {
        try {
            // Collect JMX metrics
            double fetchRate = getJmxMetricDouble("fetch-rate");
            double bytesRate = getJmxMetricDouble("bytes-consumed-rate");
            double recordsRate = getJmxMetricDouble("records-consumed-rate");
            double fetchSize = getJmxMetricDouble("fetch-size-avg");
            double fetchLatency = getJmxMetricDouble("fetch-latency-avg");

            // Emit fetch rate signal (Counter API - RC7)
            emitFetchRateSignal(fetchRate);

            // Emit bytes consumed signal (Counter API - RC7)
            emitBytesConsumedSignal(bytesRate);

            // Emit records consumed signal (Counter API - RC7)
            emitRecordsConsumedSignal(recordsRate);

            // Emit fetch size signal (Gauge API - RC7)
            emitFetchSizeSignal(fetchSize);

            // Emit fetch latency signal (Gauge API - RC7)
            emitFetchLatencySignal(fetchLatency);

            // Update previous values
            previousFetchRate = fetchRate;
            previousBytesRate = bytesRate;
            previousRecordsRate = recordsRate;
            previousFetchSize = fetchSize;
            previousFetchLatency = fetchLatency;

        } catch (Exception e) {
            logger.error("Error collecting fetch metrics for consumer {}", consumerId, e);
            // Emit overflow on error (conservative - assume latency issue)
            fetchLatencyGauge.overflow();
        }
    }

    private void emitFetchRateSignal(double fetchRate) {
        // Fetch rate is fetches/sec - convert to approximate count
        long approximateFetches = (long) (fetchRate * 10);

        if (approximateFetches > 0) {
            fetchRateCounter.increment();
            logger.debug("Consumer {} fetch rate: {:.2f} fetch/sec (~{} in 10s)",
                consumerId, fetchRate, approximateFetches);
        }

        // Track rate changes
        double rateDelta = fetchRate - previousFetchRate;
        if (rateDelta > 0.1) {
            logger.info("Consumer {} fetch rate INCREASING: {:.2f}→{:.2f}",
                consumerId, previousFetchRate, fetchRate);
        } else if (rateDelta < -0.1) {
            logger.info("Consumer {} fetch rate DECREASING: {:.2f}→{:.2f}",
                consumerId, previousFetchRate, fetchRate);
        }
    }

    private void emitBytesConsumedSignal(double bytesRate) {
        // Bytes rate is bytes/sec - convert to approximate count
        long approximateBytes = (long) (bytesRate * 10);

        if (approximateBytes > 0) {
            bytesConsumedCounter.increment();
            logger.debug("Consumer {} bytes consumed rate: {:.2f} bytes/sec (~{} in 10s)",
                consumerId, bytesRate, approximateBytes);
        }

        // Track rate changes
        double rateDelta = bytesRate - previousBytesRate;
        if (Math.abs(rateDelta) > 1000) {  // >1KB/s change
            logger.debug("Consumer {} bytes consumed rate changed: {:.2f}→{:.2f}",
                consumerId, previousBytesRate, bytesRate);
        }
    }

    private void emitRecordsConsumedSignal(double recordsRate) {
        // Records rate is records/sec - convert to approximate count
        long approximateRecords = (long) (recordsRate * 10);

        if (approximateRecords > 0) {
            recordsConsumedCounter.increment();
            logger.debug("Consumer {} records consumed rate: {:.2f} records/sec (~{} in 10s)",
                consumerId, recordsRate, approximateRecords);
        }

        // Track rate changes
        double rateDelta = recordsRate - previousRecordsRate;
        if (Math.abs(rateDelta) > 1.0) {  // >1 record/s change
            logger.debug("Consumer {} records consumed rate changed: {:.2f}→{:.2f}",
                consumerId, previousRecordsRate, recordsRate);
        }
    }

    private void emitFetchSizeSignal(double fetchSize) {
        double delta = fetchSize - previousFetchSize;

        if (delta > FETCH_SIZE_CHANGE_THRESHOLD_BYTES) {
            fetchSizeGauge.increment();
            logger.debug("Consumer {} fetch size INCREASED: {:.0f}→{:.0f} bytes",
                consumerId, previousFetchSize, fetchSize);

        } else if (delta < -FETCH_SIZE_CHANGE_THRESHOLD_BYTES) {
            fetchSizeGauge.decrement();
            logger.debug("Consumer {} fetch size DECREASED: {:.0f}→{:.0f} bytes",
                consumerId, previousFetchSize, fetchSize);
        }
        // No signal on minor fluctuations
    }

    private void emitFetchLatencySignal(double fetchLatency) {
        double delta = fetchLatency - previousFetchLatency;
        double threshold = 50.0; // 50ms change threshold

        if (delta > threshold) {
            fetchLatencyGauge.increment();
            logger.debug("Consumer {} fetch latency INCREASED: {:.1f}→{:.1f} ms",
                consumerId, previousFetchLatency, fetchLatency);

            if (fetchLatency >= FETCH_LATENCY_CRITICAL_MS) {
                logger.error("Consumer {} fetch latency CRITICAL: {:.1f} ms (threshold: {} ms)",
                    consumerId, fetchLatency, FETCH_LATENCY_CRITICAL_MS);
                fetchLatencyGauge.overflow();
            } else if (fetchLatency >= FETCH_LATENCY_WARNING_MS) {
                logger.warn("Consumer {} fetch latency WARNING: {:.1f} ms (threshold: {} ms)",
                    consumerId, fetchLatency, FETCH_LATENCY_WARNING_MS);
            }

        } else if (delta < -threshold) {
            fetchLatencyGauge.decrement();
            logger.debug("Consumer {} fetch latency DECREASED: {:.1f}→{:.1f} ms",
                consumerId, previousFetchLatency, fetchLatency);
        }
        // No signal on minor fluctuations
    }

    private double getJmxMetricDouble(String metricName) throws Exception {
        // Build MBean object name for consumer fetch manager metrics
        ObjectName objectName = new ObjectName(
            String.format("kafka.consumer:type=consumer-fetch-manager-metrics,client-id=%s", consumerId)
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
