package io.fullerstack.kafka.core.demo;

import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Probes;
import io.humainary.substrates.ext.serventis.ext.Services;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Local adapter for ProducerSendObserver that uses the platform MBeanServer directly.
 * <p>
 * This demonstrates the REAL production signal emission logic without requiring
 * JMX remote connections. Uses the exact same thresholds and logic as the production
 * ProducerSendObserver class.
 */
public class LocalProducerSendObserver implements AutoCloseable {

    // Thresholds (same as production)
    private static final double LATENCY_WARNING_MS = 100.0;
    private static final double LATENCY_CRITICAL_MS = 500.0;

    private final String producerId;
    private final MBeanServer mbeanServer;
    private final Counters.Counter sendRateCounter;
    private final Counters.Counter sendTotalCounter;
    private final Probes.Probe sendProbe;
    private final Counters.Counter errorCounter;
    private final Services.Service retryService;
    private final Counters.Counter retryCounter;
    private final Gauges.Gauge latencyGauge;

    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private ScheduledFuture<?> monitorTask;

    // Previous values for delta calculations
    private double previousSendRate = 0.0;
    private long previousSendTotal = 0;
    private double previousErrorRate = 0.0;
    private double previousRetryRate = 0.0;
    private double previousLatency = 0.0;

    public LocalProducerSendObserver(
        String producerId,
        Counters.Counter sendRateCounter,
        Counters.Counter sendTotalCounter,
        Probes.Probe sendProbe,
        Counters.Counter errorCounter,
        Services.Service retryService,
        Counters.Counter retryCounter,
        Gauges.Gauge latencyGauge
    ) {
        this.producerId = producerId;
        this.mbeanServer = ManagementFactory.getPlatformMBeanServer();
        this.sendRateCounter = sendRateCounter;
        this.sendTotalCounter = sendTotalCounter;
        this.sendProbe = sendProbe;
        this.errorCounter = errorCounter;
        this.retryService = retryService;
        this.retryCounter = retryCounter;
        this.latencyGauge = latencyGauge;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "local-send-receptor-" + producerId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts monitoring - schedules collection every 10 seconds.
     */
    public void start() {
        if (running) {
            System.out.println("⚠️  Send receptor for " + producerId + " already running");
            return;
        }

        running = true;
        monitorTask = scheduler.scheduleAtFixedRate(
            this::collectAndEmit,
            0,
            10,
            TimeUnit.SECONDS
        );

        System.out.printf("✅ Started ProducerSendObserver for %s%n", producerId);
    }

    /**
     * Collects JMX metrics and emits signals using REAL production logic.
     */
    private void collectAndEmit() {
        try {
            // Build MBean object name
            ObjectName objectName = new ObjectName(
                String.format("kafka.producer:type=producer-metrics,client-id=%s", producerId)
            );

            // Collect JMX metrics (same as production)
            double sendRate = getMetricDouble(objectName, "record-send-rate");
            long sendTotal = getMetricLong(objectName, "record-send-total");
            double errorRate = getMetricDouble(objectName, "record-error-rate");
            double retryRate = getMetricDouble(objectName, "record-retry-rate");
            double latencyAvg = getMetricDouble(objectName, "request-latency-avg");

            // Emit send rate signal (REAL production logic)
            emitSendRateSignal(sendRate);

            // Emit send total signal
            emitSendTotalSignal(sendTotal);

            // Emit error rate signal
            emitErrorRateSignal(errorRate);

            // Emit retry rate signal
            emitRetryRateSignal(retryRate);

            // Emit latency signal
            emitLatencySignal(latencyAvg);

            // Update previous values
            previousSendRate = sendRate;
            previousSendTotal = sendTotal;
            previousErrorRate = errorRate;
            previousRetryRate = retryRate;
            previousLatency = latencyAvg;

        } catch (Exception e) {
            System.err.printf("❌ Error collecting send metrics for %s: %s%n",
                producerId, e.getMessage());
            // Emit failure probe on error
            sendProbe.fail(Probes.Dimension.OUTBOUND);
        }
    }

    /**
     * REAL production signal emission logic from ProducerSendObserver.
     */
    private void emitSendRateSignal(double sendRate) {
        // Send rate is messages/sec - convert to approximate count
        long approximateMessages = (long) (sendRate * 10);

        if (approximateMessages > 0) {
            sendRateCounter.increment();
        }
    }

    private void emitSendTotalSignal(long sendTotal) {
        long delta = sendTotal - previousSendTotal;

        if (delta > 0) {
            // New sends occurred
            for (int i = 0; i < Math.min(delta, 1000); i++) {
                sendTotalCounter.increment();
            }
        }
    }

    private void emitErrorRateSignal(double errorRate) {
        // Calculate approximate error count
        long approximateErrors = (long) (errorRate * 10);

        if (approximateErrors > 0) {
            // Emit Probe signal for send failure
            sendProbe.fail(Probes.Dimension.OUTBOUND);

            // Also increment error counter
            errorCounter.increment();

            System.out.printf("⚠️  Producer %s error rate: %.2f errors/sec (~%d errors in 10s)%n",
                producerId, errorRate, approximateErrors);

        } else if (previousErrorRate > 0 && errorRate == 0) {
            // Errors cleared - emit success
            sendProbe.transfer(Probes.Dimension.OUTBOUND);
        }

        // Track total error count changes
        double errorRateDelta = errorRate - previousErrorRate;
        if (errorRateDelta > 0.1) {
            System.out.printf("⚠️  Producer %s error rate INCREASING: %.2f→%.2f%n",
                producerId, previousErrorRate, errorRate);
        }
    }

    private void emitRetryRateSignal(double retryRate) {
        // Calculate approximate retry count
        long approximateRetries = (long) (retryRate * 10);

        if (approximateRetries > 0) {
            // Emit Service retry signal
            retryService.retry(Services.Dimension.CALLER);

            // Also increment retry counter
            retryCounter.increment();

            System.out.printf("⚠️  Producer %s retry rate: %.2f retries/sec (~%d retries in 10s)%n",
                producerId, retryRate, approximateRetries);
        }

        // Track retry rate changes
        double retryRateDelta = retryRate - previousRetryRate;
        if (retryRateDelta > 0.1) {
            System.out.printf("⚠️  Producer %s retry rate INCREASING: %.2f→%.2f%n",
                producerId, previousRetryRate, retryRate);
        }
    }

    private void emitLatencySignal(double latencyAvg) {
        double delta = latencyAvg - previousLatency;
        double threshold = 10.0; // 10ms change threshold

        if (delta > threshold) {
            latencyGauge.increment();

            if (latencyAvg >= LATENCY_CRITICAL_MS) {
                System.out.printf("❌ Producer %s latency CRITICAL: %.1f ms (threshold: %.0f ms)%n",
                    producerId, latencyAvg, LATENCY_CRITICAL_MS);
                latencyGauge.overflow();
            } else if (latencyAvg >= LATENCY_WARNING_MS) {
                System.out.printf("⚠️  Producer %s latency WARNING: %.1f ms (threshold: %.0f ms)%n",
                    producerId, latencyAvg, LATENCY_WARNING_MS);
            }

        } else if (delta < -threshold) {
            latencyGauge.decrement();
        }
    }

    private long getMetricLong(ObjectName objectName, String attributeName) throws Exception {
        Object value = mbeanServer.getAttribute(objectName, attributeName);
        return ((Number) value).longValue();
    }

    private double getMetricDouble(ObjectName objectName, String attributeName) throws Exception {
        Object value = mbeanServer.getAttribute(objectName, attributeName);
        return ((Number) value).doubleValue();
    }

    public void stop() {
        running = false;
        if (monitorTask != null) {
            monitorTask.cancel(false);
        }
    }

    @Override
    public void close() {
        stop();
        scheduler.shutdown();
    }
}
