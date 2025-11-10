package io.fullerstack.kafka.core.demo;

import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Queues;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Local adapter for ProducerBufferMonitor that uses the platform MBeanServer directly.
 * <p>
 * This demonstrates the REAL production signal emission logic without requiring
 * JMX remote connections. Uses the exact same thresholds and logic as the production
 * ProducerBufferMonitor class.
 */
public class LocalProducerBufferMonitor implements AutoCloseable {

    // Thresholds (same as production)
    private static final double OVERFLOW_THRESHOLD = 0.95;
    private static final double PRESSURE_THRESHOLD = 0.80;

    private final String producerId;
    private final MBeanServer mbeanServer;
    private final Queues.Queue bufferQueue;
    private final Gauges.Gauge totalBytesGauge;
    private final Counters.Counter exhaustedCounter;
    private final Gauges.Gauge batchSizeGauge;
    private final Gauges.Gauge recordsPerRequestGauge;

    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private ScheduledFuture<?> monitorTask;

    // Previous values for delta calculations
    private long previousTotalBytes = 0;
    private double previousBatchSize = 0.0;
    private double previousRecordsPerRequest = 0.0;
    private long previousExhaustedTotal = 0;

    public LocalProducerBufferMonitor(
        String producerId,
        Queues.Queue bufferQueue,
        Gauges.Gauge totalBytesGauge,
        Counters.Counter exhaustedCounter,
        Gauges.Gauge batchSizeGauge,
        Gauges.Gauge recordsPerRequestGauge
    ) {
        this.producerId = producerId;
        this.mbeanServer = ManagementFactory.getPlatformMBeanServer();
        this.bufferQueue = bufferQueue;
        this.totalBytesGauge = totalBytesGauge;
        this.exhaustedCounter = exhaustedCounter;
        this.batchSizeGauge = batchSizeGauge;
        this.recordsPerRequestGauge = recordsPerRequestGauge;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "local-buffer-monitor-" + producerId);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts monitoring - schedules collection every 10 seconds.
     */
    public void start() {
        if (running) {
            System.out.println("⚠️  Buffer monitor for " + producerId + " already running");
            return;
        }

        running = true;
        monitorTask = scheduler.scheduleAtFixedRate(
            this::collectAndEmit,
            0,
            10,
            TimeUnit.SECONDS
        );

        System.out.printf("✅ Started ProducerBufferMonitor for %s%n", producerId);
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
            long availableBytes = getMetricLong(objectName, "buffer-available-bytes");
            long totalBytes = getMetricLong(objectName, "buffer-total-bytes");
            long exhaustedTotal = getMetricLong(objectName, "buffer-exhausted-total");
            double batchSizeAvg = getMetricDouble(objectName, "batch-size-avg");
            double recordsPerRequestAvg = getMetricDouble(objectName, "records-per-request-avg");

            // Calculate utilization
            double utilization = 1.0 - ((double) availableBytes / totalBytes);
            long utilizationPercent = (long) (utilization * 100);

            // Emit buffer availability signal (REAL production logic)
            emitBufferSignal(utilization, utilizationPercent);

            // Emit total bytes signal
            emitTotalBytesSignal(totalBytes);

            // Emit exhausted counter signal
            emitExhaustedSignal(exhaustedTotal);

            // Emit batch size signal
            emitBatchSizeSignal(batchSizeAvg);

            // Emit records per request signal
            emitRecordsPerRequestSignal(recordsPerRequestAvg);

            // Update previous values
            previousTotalBytes = totalBytes;
            previousBatchSize = batchSizeAvg;
            previousRecordsPerRequest = recordsPerRequestAvg;
            previousExhaustedTotal = exhaustedTotal;

        } catch (Exception e) {
            System.err.printf("❌ Error collecting buffer metrics for %s: %s%n",
                producerId, e.getMessage());
            // Emit overflow on error (conservative approach)
            bufferQueue.overflow();
        }
    }

    /**
     * REAL production signal emission logic from ProducerBufferMonitor.
     */
    private void emitBufferSignal(double utilization, long utilizationPercent) {
        if (utilization >= OVERFLOW_THRESHOLD) {
            bufferQueue.overflow();
            System.out.printf("⚠️  Producer %s buffer OVERFLOW: %d%%%n",
                producerId, utilizationPercent);

        } else if (utilization >= PRESSURE_THRESHOLD) {
            bufferQueue.enqueue();
            System.out.printf("⚡ Producer %s buffer PRESSURE: %d%%%n",
                producerId, utilizationPercent);

        } else {
            bufferQueue.enqueue();
        }
    }

    private void emitTotalBytesSignal(long totalBytes) {
        long delta = totalBytes - previousTotalBytes;

        if (delta > 0) {
            totalBytesGauge.increment();
        } else if (delta < 0) {
            totalBytesGauge.decrement();
        }
    }

    private void emitExhaustedSignal(long exhaustedTotal) {
        long delta = exhaustedTotal - previousExhaustedTotal;

        if (delta > 0) {
            for (int i = 0; i < delta; i++) {
                exhaustedCounter.increment();
            }
            System.out.printf("⚠️  Producer %s buffer EXHAUSTED: %d events%n",
                producerId, delta);
        }
    }

    private void emitBatchSizeSignal(double batchSizeAvg) {
        double delta = batchSizeAvg - previousBatchSize;
        double threshold = 10.0;

        if (delta > threshold) {
            batchSizeGauge.increment();
        } else if (delta < -threshold) {
            batchSizeGauge.decrement();
        }
    }

    private void emitRecordsPerRequestSignal(double recordsPerRequestAvg) {
        double delta = recordsPerRequestAvg - previousRecordsPerRequest;
        double threshold = 0.1;

        if (delta > threshold) {
            recordsPerRequestGauge.increment();
        } else if (delta < -threshold) {
            recordsPerRequestGauge.decrement();
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
