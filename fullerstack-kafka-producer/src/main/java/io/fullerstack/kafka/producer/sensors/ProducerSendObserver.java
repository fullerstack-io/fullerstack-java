package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import io.humainary.substrates.ext.serventis.ext.Probes;
import io.humainary.substrates.ext.serventis.ext.Probes.Probe;
import io.humainary.substrates.ext.serventis.ext.Services;
import io.humainary.substrates.ext.serventis.ext.Services.Service;
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
 * Monitors Kafka producer send operations and emits signals using RC7 Serventis API.
 * <p>
 * Collects JMX metrics for producer send operations and emits signals for:
 * <ul>
 *   <li><b>Record Send Rate</b> (Counter): INCREMENT for each send</li>
 *   <li><b>Record Send Total</b> (Counter): Cumulative sends</li>
 *   <li><b>Record Error Rate</b> (Probes + Counter): Operation=SEND, Outcome=FAILURE</li>
 *   <li><b>Record Retry Rate</b> (Services + Counter): retry() signal</li>
 *   <li><b>Request Latency</b> (Gauge): INCREMENT (rising), DECREMENT (improving)</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * // From circuit that provides instruments
 * Counter sendRateCounter = ...;
 * Counter sendTotalCounter = ...;
 * Probe sendProbe = ...;
 * Counter errorCounter = ...;
 * Service retryService = ...;
 * Counter retryCounter = ...;
 * Gauge latencyGauge = ...;
 *
 * ProducerSendObserver observer = new ProducerSendObserver(
 *     "producer-1",
 *     "localhost:11001",  // JMX endpoint
 *     sendRateCounter,
 *     sendTotalCounter,
 *     sendProbe,
 *     errorCounter,
 *     retryService,
 *     retryCounter,
 *     latencyGauge
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
 * @see Probe
 * @see Service
 * @see Gauge
 */
public class ProducerSendObserver implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ProducerSendObserver.class);

    // Thresholds
    private static final double LATENCY_WARNING_MS = 100.0;   // >100ms = warning
    private static final double LATENCY_CRITICAL_MS = 500.0;  // >500ms = critical

    private final String producerId;
    private final String jmxEndpoint;
    private final Counter sendRateCounter;
    private final Counter sendTotalCounter;
    private final Probe sendProbe;
    private final Counter errorCounter;
    private final Service retryService;
    private final Counter retryCounter;
    private final Gauge latencyGauge;

    private final ScheduledExecutorService scheduler;
    private JMXConnector jmxConnector;
    private MBeanServerConnection mbeanServer;
    private volatile boolean running = false;

    // Previous values for delta calculations
    private double previousSendRate = 0.0;
    private long previousSendTotal = 0;
    private double previousErrorRate = 0.0;
    private long previousErrorTotal = 0;
    private double previousRetryRate = 0.0;
    private long previousRetryTotal = 0;
    private double previousLatency = 0.0;

    /**
     * Creates a new producer send observer.
     *
     * @param producerId       Producer identifier (e.g., "producer-1")
     * @param jmxEndpoint     JMX endpoint (e.g., "localhost:11001")
     * @param sendRateCounter Counter for send rate
     * @param sendTotalCounter Counter for total sends
     * @param sendProbe       Probe for send operations
     * @param errorCounter    Counter for error rate
     * @param retryService    Service for retry operations
     * @param retryCounter    Counter for retry rate
     * @param latencyGauge    Gauge for request latency
     */
    public ProducerSendObserver(
        String producerId,
        String jmxEndpoint,
        Counter sendRateCounter,
        Counter sendTotalCounter,
        Probe sendProbe,
        Counter errorCounter,
        Service retryService,
        Counter retryCounter,
        Gauge latencyGauge
    ) {
        this.producerId = producerId;
        this.jmxEndpoint = jmxEndpoint;
        this.sendRateCounter = sendRateCounter;
        this.sendTotalCounter = sendTotalCounter;
        this.sendProbe = sendProbe;
        this.errorCounter = errorCounter;
        this.retryService = retryService;
        this.retryCounter = retryCounter;
        this.latencyGauge = latencyGauge;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "producer-send-observer-" + producerId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts send operation monitoring.
     * <p>
     * Connects to JMX and schedules send metric checks every 10 seconds.
     */
    public void start() {
        if (running) {
            logger.warn("Producer send observer for {} is already running", producerId);
            return;
        }

        try {
            connectJmx();
            running = true;

            // Schedule send metrics monitoring every 10 seconds
            scheduler.scheduleAtFixedRate(
                this::collectAndEmit,
                0,          // Initial delay
                10,         // Period
                TimeUnit.SECONDS
            );

            logger.info("Started producer send observer for {} (JMX: {})", producerId, jmxEndpoint);

        } catch (Exception e) {
            logger.error("Failed to start producer send observer for {}", producerId, e);
            running = false;
            throw new RuntimeException("Failed to start producer send observer", e);
        }
    }

    /**
     * Stops send operation monitoring and releases resources.
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
        logger.info("Stopped producer send observer for {}", producerId);
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
    // Send Metrics Collection & Emission
    // ========================================

    private void collectAndEmit() {
        try {
            // Collect JMX metrics
            double sendRate = getJmxMetricDouble("record-send-rate");
            long sendTotal = getJmxMetricLong("record-send-total");
            double errorRate = getJmxMetricDouble("record-error-rate");
            double retryRate = getJmxMetricDouble("record-retry-rate");
            double latencyAvg = getJmxMetricDouble("request-latency-avg");

            // Emit send rate signal (Counter API - RC7)
            emitSendRateSignal(sendRate);

            // Emit send total signal (Counter API - RC7)
            emitSendTotalSignal(sendTotal);

            // Emit error rate signal (Probes + Counter API - RC7)
            emitErrorRateSignal(errorRate);

            // Emit retry rate signal (Services + Counter API - RC7)
            emitRetryRateSignal(retryRate);

            // Emit latency signal (Gauge API - RC7)
            emitLatencySignal(latencyAvg);

            // Update previous values
            previousSendRate = sendRate;
            previousSendTotal = sendTotal;
            previousErrorRate = errorRate;
            previousRetryRate = retryRate;
            previousLatency = latencyAvg;

        } catch (Exception e) {
            logger.error("Error collecting send metrics for producer {}", producerId, e);
            // Emit failure probe on error
            sendProbe.failed();
        }
    }

    private void emitSendRateSignal(double sendRate) {
        // Send rate is messages/sec - convert to approximate count
        // Since we poll every 10 seconds, multiply by 10 to get approx messages
        long approximateMessages = (long) (sendRate * 10);

        if (approximateMessages > 0) {
            sendRateCounter.increment();
            logger.debug("Producer {} send rate: {:.2f} msg/sec (~{} msgs in 10s)",
                producerId, sendRate, approximateMessages);
        }
    }

    private void emitSendTotalSignal(long sendTotal) {
        long delta = sendTotal - previousSendTotal;

        if (delta > 0) {
            // New sends occurred
            for (int i = 0; i < Math.min(delta, 1000); i++) {
                sendTotalCounter.increment();
            }
            if (delta > 1000) {
                logger.debug("Producer {} sent {} messages (showing first 1000 increments)",
                    producerId, delta);
            } else {
                logger.debug("Producer {} sent {} messages",
                    producerId, delta);
            }
        }
    }

    private void emitErrorRateSignal(double errorRate) {
        // Calculate approximate error count
        long approximateErrors = (long) (errorRate * 10);

        if (approximateErrors > 0) {
            // Emit Probe signal for send failure
            sendProbe.failed();

            // Also increment error counter
            errorCounter.increment();

            logger.warn("Producer {} error rate: {:.2f} errors/sec (~{} errors in 10s)",
                producerId, errorRate, approximateErrors);
        } else if (previousErrorRate > 0 && errorRate == 0) {
            // Errors cleared - emit success
            sendProbe.transmitted();
            logger.info("Producer {} error rate cleared", producerId);
        }

        // Track total error count changes
        double errorRateDelta = errorRate - previousErrorRate;
        if (errorRateDelta > 0.1) {
            logger.warn("Producer {} error rate INCREASING: {:.2f}→{:.2f}",
                producerId, previousErrorRate, errorRate);
        }
    }

    private void emitRetryRateSignal(double retryRate) {
        // Calculate approximate retry count
        long approximateRetries = (long) (retryRate * 10);

        if (approximateRetries > 0) {
            // Emit Service retry signal
            retryService.retry();

            // Also increment retry counter
            retryCounter.increment();

            logger.warn("Producer {} retry rate: {:.2f} retries/sec (~{} retries in 10s)",
                producerId, retryRate, approximateRetries);
        }

        // Track retry rate changes
        double retryRateDelta = retryRate - previousRetryRate;
        if (retryRateDelta > 0.1) {
            logger.warn("Producer {} retry rate INCREASING: {:.2f}→{:.2f}",
                producerId, previousRetryRate, retryRate);
        }
    }

    private void emitLatencySignal(double latencyAvg) {
        double delta = latencyAvg - previousLatency;
        double threshold = 10.0; // 10ms change threshold

        if (delta > threshold) {
            latencyGauge.increment();
            logger.debug("Producer {} latency INCREASED: {:.1f}→{:.1f} ms",
                producerId, previousLatency, latencyAvg);

            if (latencyAvg >= LATENCY_CRITICAL_MS) {
                logger.error("Producer {} latency CRITICAL: {:.1f} ms (threshold: {} ms)",
                    producerId, latencyAvg, LATENCY_CRITICAL_MS);
                latencyGauge.overflow();
            } else if (latencyAvg >= LATENCY_WARNING_MS) {
                logger.warn("Producer {} latency WARNING: {:.1f} ms (threshold: {} ms)",
                    producerId, latencyAvg, LATENCY_WARNING_MS);
            }

        } else if (delta < -threshold) {
            latencyGauge.decrement();
            logger.debug("Producer {} latency DECREASED: {:.1f}→{:.1f} ms",
                producerId, previousLatency, latencyAvg);
        }
        // No signal on minor fluctuations
    }

    private long getJmxMetricLong(String metricName) throws Exception {
        // Build MBean object name for producer metrics
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
