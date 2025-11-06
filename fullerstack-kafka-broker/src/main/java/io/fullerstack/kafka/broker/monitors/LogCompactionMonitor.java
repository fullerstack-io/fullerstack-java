package io.fullerstack.kafka.broker.monitors;

import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Monitors Kafka log compaction metrics via JMX using RC7 Substrates API.
 *
 * <p><b>Layer 2: OBSERVE Phase (Raw Signals)</b>
 * This monitor emits signals using Counters and Gauges APIs:
 * <ul>
 *   <li><b>Compaction Rate</b> - Counters (INCREMENT on compaction completion)</li>
 *   <li><b>Tombstone Records</b> - Counters (INCREMENT on tombstone detection)</li>
 *   <li><b>Compaction Latency</b> - Gauges (time since last compaction run)</li>
 *   <li><b>Dirty Ratio</b> - Gauges (0-100% uncompacted data)</li>
 *   <li><b>Cleaned Ratio</b> - Gauges (0-100% compacted data, inverse of dirty)</li>
 * </ul>
 *
 * <h3>JMX MBeans</h3>
 * <ul>
 *   <li>{@code kafka.log:type=LogCleanerManager,name=max-clean-time-secs} - Compaction time</li>
 *   <li>{@code kafka.log:type=LogCleanerManager,name=max-dirty-percent} - Dirty ratio</li>
 *   <li>{@code kafka.log:type=LogCleaner,name=time-since-last-run-ms} - Compaction latency</li>
 * </ul>
 *
 * <h3>Signal Thresholds</h3>
 * <pre>
 * Compaction Latency:
 *   OVERFLOW: > 5 minutes (300,000ms)
 *   INCREMENT: > 1 minute (60,000ms)
 *   DECREMENT: <= 1 minute
 *
 * Dirty Ratio (0-100%):
 *   OVERFLOW: > 80% (critical compaction backlog)
 *   INCREMENT: > 50% (growing backlog)
 *   DECREMENT: <= 50% (healthy)
 *
 * Cleaned Ratio (inverse of dirty, 0-100%):
 *   UNDERFLOW: < 20% (very low cleaned ratio)
 *   DECREMENT: < 50% (below target)
 *   INCREMENT: >= 50% (good)
 * </pre>
 *
 * <h3>Tombstone Tracking</h3>
 * <p>Note: Kafka does not expose tombstone count directly via JMX. This implementation
 * provides a placeholder that logs the limitation. Future enhancement would require:
 * <ul>
 *   <li>Custom JMX metric in Kafka broker</li>
 *   <li>Direct log segment parsing</li>
 *   <li>Separate Kafka Streams app to count tombstones</li>
 * </ul>
 *
 * <h3>Graceful Degradation</h3>
 * <p>Compaction may be disabled globally or per-topic. Missing MBeans are logged
 * at DEBUG level without throwing exceptions.
 *
 * <p><b>Thread Safety</b>: All signal emissions synchronized via Circuit's Valve pattern.
 *
 * @see Counter
 * @see Gauge
 */
public class LogCompactionMonitor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LogCompactionMonitor.class);

    // Thresholds
    private static final long LATENCY_OVERFLOW_MS = 300_000;  // 5 minutes
    private static final long LATENCY_INCREMENT_MS = 60_000;   // 1 minute
    private static final double DIRTY_RATIO_OVERFLOW = 80.0;   // 80%
    private static final double DIRTY_RATIO_INCREMENT = 50.0;  // 50%
    private static final double CLEANED_RATIO_UNDERFLOW = 20.0; // 20%
    private static final double CLEANED_RATIO_DECREMENT = 50.0; // 50%
    private static final long DEFAULT_POLL_INTERVAL_SECONDS = 30;

    private final MBeanServerConnection mbsc;
    private final Circuit circuit;
    private final ScheduledExecutorService scheduler;

    // Conduits
    private final Conduit<Counter, Counters.Sign> counters;
    private final Conduit<Gauge, Gauges.Sign> gauges;

    // Instruments
    private final Counter compactionRateCounter;
    private final Counter tombstoneCounter;
    private final Gauge compactionLatencyGauge;
    private final Gauge dirtyRatioGauge;
    private final Gauge cleanedRatioGauge;

    // State tracking
    private Double previousCleanTime = null;

    /**
     * Creates a LogCompactionMonitor with default poll interval (30 seconds).
     *
     * @param mbsc    MBean server connection for JMX queries
     * @param circuit Circuit for signal emission
     * @throws NullPointerException if any parameter is null
     */
    public LogCompactionMonitor(MBeanServerConnection mbsc, Circuit circuit) {
        this(mbsc, circuit, DEFAULT_POLL_INTERVAL_SECONDS);
    }

    /**
     * Creates a LogCompactionMonitor with custom poll interval.
     *
     * @param mbsc                MBean server connection for JMX queries
     * @param circuit             Circuit for signal emission
     * @param pollIntervalSeconds Seconds between JMX polls
     * @throws NullPointerException if any parameter is null
     */
    public LogCompactionMonitor(
        MBeanServerConnection mbsc,
        Circuit circuit,
        long pollIntervalSeconds
    ) {
        this.mbsc = Objects.requireNonNull(mbsc, "mbsc cannot be null");
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");

        // Create conduits with RC7 pattern
        this.counters = circuit.conduit(cortex().name("counters"), Counters::composer);
        this.gauges = circuit.conduit(cortex().name("gauges"), Gauges::composer);

        // Create instruments
        this.compactionRateCounter = counters.get(cortex().name("log.compaction.rate"));
        this.tombstoneCounter = counters.get(cortex().name("log.tombstones"));
        this.compactionLatencyGauge = gauges.get(cortex().name("log.compaction.latency"));
        this.dirtyRatioGauge = gauges.get(cortex().name("log.dirty-ratio"));
        this.cleanedRatioGauge = gauges.get(cortex().name("log.cleaned-ratio"));

        // Schedule polling
        this.scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(
            this::pollCompactionMetrics,
            0,
            pollIntervalSeconds,
            TimeUnit.SECONDS
        );

        logger.info("LogCompactionMonitor created (poll interval: {}s)", pollIntervalSeconds);
    }

    /**
     * Polls JMX for log compaction metrics and emits signals.
     * <p>
     * Called periodically by scheduler. Queries:
     * <ul>
     *   <li>Compaction rate (max-clean-time-secs)</li>
     *   <li>Tombstone records (placeholder)</li>
     *   <li>Compaction latency (time-since-last-run-ms)</li>
     *   <li>Dirty ratio (max-dirty-percent)</li>
     * </ul>
     */
    void pollCompactionMetrics() {
        try {
            pollCompactionRate();
            pollTombstoneRecords();
            pollCompactionLatency();
            pollDirtyRatio();

            circuit.await();

        } catch (java.lang.Exception e) {
            logger.error("Log compaction metrics polling failed", e);
        }
    }

    /**
     * Polls compaction rate and emits Counter signals.
     * <p>
     * Tracks {@code max-clean-time-secs} to detect compaction completions.
     * Emits INCREMENT when compaction time value changes (indicates a run completed).
     */
    private void pollCompactionRate() {
        try {
            ObjectName mbean = new ObjectName(
                "kafka.log:type=LogCleanerManager,name=max-clean-time-secs"
            );

            Double cleanTime = (Double) mbsc.getAttribute(mbean, "Value");

            // Detect compaction completion by tracking value changes
            if (cleanTime != null && cleanTime > 0) {
                if (previousCleanTime != null && !cleanTime.equals(previousCleanTime)) {
                    compactionRateCounter.increment();
                    logger.debug("Compaction completed in {}s", cleanTime);
                }
                previousCleanTime = cleanTime;
            }

        } catch (InstanceNotFoundException e) {
            logger.debug("LogCleanerManager metrics not available (compaction may be disabled)");
        } catch (java.lang.Exception e) {
            logger.debug("Failed to query compaction rate: {}", e.getMessage());
        }
    }

    /**
     * Polls tombstone records and emits Counter signals.
     * <p>
     * <b>Note:</b> Kafka does not expose tombstone count directly via JMX.
     * This is a placeholder implementation that logs the limitation.
     * <p>
     * Future enhancement would require:
     * <ul>
     *   <li>Custom JMX metric in Kafka broker</li>
     *   <li>Direct log segment parsing</li>
     *   <li>Separate Kafka Streams application</li>
     * </ul>
     */
    private void pollTombstoneRecords() {
        // Tombstone tracking is challenging as Kafka doesn't expose this directly via JMX
        logger.debug("Tombstone tracking not implemented (requires custom JMX metrics or log parsing)");

        // Placeholder: In a real implementation, you would:
        // 1. Query custom JMX metric: kafka.log:type=Log,name=TombstoneCount,topic=*,partition=*
        // 2. Parse log segments directly (file I/O intensive)
        // 3. Use a separate Kafka Streams app to count tombstones

        // For now, this is a no-op to demonstrate the API pattern
    }

    /**
     * Polls compaction latency and emits Gauge signals.
     * <p>
     * Tracks {@code time-since-last-run-ms} and emits:
     * <ul>
     *   <li>OVERFLOW if > 5 minutes (critical delay)</li>
     *   <li>INCREMENT if > 1 minute</li>
     *   <li>DECREMENT if <= 1 minute (healthy)</li>
     * </ul>
     */
    private void pollCompactionLatency() {
        try {
            ObjectName mbean = new ObjectName(
                "kafka.log:type=LogCleaner,name=time-since-last-run-ms"
            );

            Long timeSinceLastRun = (Long) mbsc.getAttribute(mbean, "Value");

            if (timeSinceLastRun != null) {
                if (timeSinceLastRun > LATENCY_OVERFLOW_MS) {
                    compactionLatencyGauge.overflow();
                    logger.warn("High compaction latency: {}ms", timeSinceLastRun);
                } else if (timeSinceLastRun > LATENCY_INCREMENT_MS) {
                    compactionLatencyGauge.increment();
                    logger.debug("Elevated compaction latency: {}ms", timeSinceLastRun);
                } else {
                    compactionLatencyGauge.decrement();
                }
            }

        } catch (InstanceNotFoundException e) {
            logger.debug("LogCleaner metrics not available (compaction may be disabled)");
        } catch (java.lang.Exception e) {
            logger.debug("Failed to query compaction latency: {}", e.getMessage());
        }
    }

    /**
     * Polls dirty ratio and emits Gauge signals for both dirty and cleaned ratios.
     * <p>
     * Tracks {@code max-dirty-percent} (0-100%) and emits:
     * <p>
     * <b>Dirty Ratio Signals:</b>
     * <ul>
     *   <li>OVERFLOW if > 80% (critical backlog)</li>
     *   <li>INCREMENT if > 50% (growing backlog)</li>
     *   <li>DECREMENT if <= 50% (healthy)</li>
     * </ul>
     * <p>
     * <b>Cleaned Ratio Signals (inverse):</b>
     * <ul>
     *   <li>UNDERFLOW if < 20% (very low cleaned ratio)</li>
     *   <li>DECREMENT if < 50% (below target)</li>
     *   <li>INCREMENT if >= 50% (good)</li>
     * </ul>
     */
    private void pollDirtyRatio() {
        try {
            ObjectName mbean = new ObjectName(
                "kafka.log:type=LogCleanerManager,name=max-dirty-percent"
            );

            Double dirtyPercent = (Double) mbsc.getAttribute(mbean, "Value");

            if (dirtyPercent != null) {
                // Emit dirty ratio gauge signals
                if (dirtyPercent > DIRTY_RATIO_OVERFLOW) {
                    dirtyRatioGauge.overflow();
                    logger.warn("High dirty ratio: {}%", dirtyPercent);
                } else if (dirtyPercent > DIRTY_RATIO_INCREMENT) {
                    dirtyRatioGauge.increment();
                    logger.debug("Elevated dirty ratio: {}%", dirtyPercent);
                } else {
                    dirtyRatioGauge.decrement();
                }

                // Emit cleaned ratio gauge signals (inverse of dirty)
                double cleanedPercent = 100.0 - dirtyPercent;
                if (cleanedPercent < CLEANED_RATIO_UNDERFLOW) {
                    cleanedRatioGauge.underflow();
                    logger.warn("Very low cleaned ratio: {}%", cleanedPercent);
                } else if (cleanedPercent < CLEANED_RATIO_DECREMENT) {
                    cleanedRatioGauge.decrement();
                    logger.debug("Below-target cleaned ratio: {}%", cleanedPercent);
                } else {
                    cleanedRatioGauge.increment();
                }
            }

        } catch (InstanceNotFoundException e) {
            logger.debug("LogCleanerManager dirty ratio not available (compaction may be disabled)");
        } catch (java.lang.Exception e) {
            logger.debug("Failed to query dirty ratio: {}", e.getMessage());
        }
    }

    /**
     * Returns the compaction rate counter (for testing).
     *
     * @return Compaction rate counter
     */
    Counter getCompactionRateCounter() {
        return compactionRateCounter;
    }

    /**
     * Returns the compaction latency gauge (for testing).
     *
     * @return Compaction latency gauge
     */
    Gauge getCompactionLatencyGauge() {
        return compactionLatencyGauge;
    }

    /**
     * Returns the dirty ratio gauge (for testing).
     *
     * @return Dirty ratio gauge
     */
    Gauge getDirtyRatioGauge() {
        return dirtyRatioGauge;
    }

    /**
     * Returns the cleaned ratio gauge (for testing).
     *
     * @return Cleaned ratio gauge
     */
    Gauge getCleanedRatioGauge() {
        return cleanedRatioGauge;
    }

    @Override
    public void close() {
        logger.info("Shutting down LogCompactionMonitor");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
