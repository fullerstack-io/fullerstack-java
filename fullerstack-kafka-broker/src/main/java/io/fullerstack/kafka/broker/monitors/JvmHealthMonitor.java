package io.fullerstack.kafka.broker.monitors;

import io.fullerstack.kafka.broker.models.JvmGcMetrics;
import io.fullerstack.kafka.broker.models.JvmMemoryMetrics;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.ext.serventis.Counters;
import io.humainary.substrates.ext.serventis.Gauges;
import io.humainary.substrates.ext.serventis.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.fullerstack.substrates.CortexRuntime.cortex;
import static io.humainary.substrates.ext.serventis.Monitors.Confidence.*;

/**
 * Emits Serventis signals for JVM memory and GC metrics (RC6).
 *
 * <p><b>Layer 2: OBSERVE Phase (Raw Signals)</b>
 * This monitor emits signals using Gauges and Counters APIs:
 * <ul>
 *   <li>Heap metrics → Gauges (INCREMENT/DECREMENT/OVERFLOW)</li>
 *   <li>Non-heap metrics → Gauges</li>
 *   <li>GC count → Counters (INCREMENT/OVERFLOW on storm)</li>
 *   <li>GC time → Counters</li>
 *   <li>Broker health assessment → Monitors (STABLE/DEGRADED/DEFECTIVE)</li>
 * </ul>
 *
 * <p><b>Thresholds:</b>
 * <pre>
 * Heap OVERFLOW: >90% utilization
 * Heap DEGRADED (Monitor): >85% utilization
 * Heap CRITICAL (Monitor): >95% utilization
 * GC Storm: >10 collections/sec
 * </pre>
 */
public class JvmHealthMonitor {
    private static final Logger logger = LoggerFactory.getLogger(JvmHealthMonitor.class);

    // Thresholds
    private static final double HEAP_OVERFLOW_THRESHOLD = 0.90;
    private static final double HEAP_DEGRADED_THRESHOLD = 0.85;
    private static final double HEAP_CRITICAL_THRESHOLD = 0.95;

    private final Name circuitName;
    private final Channel<Gauges.Sign> gaugesChannel;
    private final Channel<Counters.Sign> countersChannel;
    private final Channel<Monitors.Signal> monitorsChannel;

    // Instruments (cached per entity)
    private final Map<String, Gauges.Gauge> heapGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauges.Gauge> nonHeapGauges = new ConcurrentHashMap<>();
    private final Map<String, Counters.Counter> gcCounters = new ConcurrentHashMap<>();
    private final Map<String, Counters.Counter> gcTimeCounters = new ConcurrentHashMap<>();
    private final Map<String, Monitors.Monitor> brokerMonitors = new ConcurrentHashMap<>();

    // Previous values for delta calculation
    private final Map<String, Double> previousHeapUtil = new ConcurrentHashMap<>();
    private final Map<String, Long> previousGcCount = new ConcurrentHashMap<>();
    private final Map<String, Long> previousGcTime = new ConcurrentHashMap<>();
    private final Map<String, Long> previousTimestamp = new ConcurrentHashMap<>();

    /**
     * Creates a JvmHealthMonitor.
     *
     * @param circuitName      Circuit name for logging
     * @param gaugesChannel    Channel for Gauges signals
     * @param countersChannel  Channel for Counters signals
     * @param monitorsChannel  Channel for Monitors signals
     */
    public JvmHealthMonitor(
        Name circuitName,
        Channel<Gauges.Sign> gaugesChannel,
        Channel<Counters.Sign> countersChannel,
        Channel<Monitors.Signal> monitorsChannel
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.gaugesChannel = Objects.requireNonNull(gaugesChannel, "gaugesChannel cannot be null");
        this.countersChannel = Objects.requireNonNull(countersChannel, "countersChannel cannot be null");
        this.monitorsChannel = Objects.requireNonNull(monitorsChannel, "monitorsChannel cannot be null");
    }

    /**
     * Emits Serventis signals for JVM memory metrics.
     *
     * @param metrics JVM memory metrics from JMX
     */
    public void emitMemory(JvmMemoryMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            String brokerId = metrics.brokerId();
            double heapUtil = metrics.heapUtilization();

            // Get or create heap gauge
            Gauges.Gauge heapGauge = heapGauges.computeIfAbsent(
                brokerId,
                id -> Gauges.composer(gaugesChannel)
            );

            // Emit heap signals
            if (heapUtil >= HEAP_OVERFLOW_THRESHOLD) {
                heapGauge.overflow();
            } else {
                Double prevUtil = previousHeapUtil.get(brokerId);
                if (prevUtil != null) {
                    if (heapUtil > prevUtil) {
                        heapGauge.increment();
                    } else if (heapUtil < prevUtil) {
                        heapGauge.decrement();
                    }
                } else {
                    heapGauge.increment();  // First observation
                }
            }

            previousHeapUtil.put(brokerId, heapUtil);

            // Non-heap gauge (simplified - just track changes)
            Gauges.Gauge nonHeapGauge = nonHeapGauges.computeIfAbsent(
                brokerId,
                id -> Gauges.composer(gaugesChannel)
            );
            nonHeapGauge.increment();  // Simplified: non-heap typically grows

            logger.debug("Emitted JVM memory signals for {}: heap={}%",
                brokerId, (int)(heapUtil * 100));

        } catch (Exception e) {
            logger.error("Failed to emit JVM memory signals for {}: {}",
                metrics.brokerId(), e.getMessage(), e);
        }
    }

    /**
     * Emits Serventis signals for GC metrics.
     *
     * @param metrics GC metrics from JMX
     */
    public void emitGc(JvmGcMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            String entityId = metrics.brokerId() + "." + metrics.collectorName();

            // Get or create GC counter
            Counters.Counter gcCounter = gcCounters.computeIfAbsent(
                entityId,
                id -> Counters.composer(countersChannel)
            );

            // Check for GC storm
            Long prevCount = previousGcCount.get(entityId);
            Long prevTs = previousTimestamp.get(entityId);

            if (prevCount != null && prevTs != null) {
                long intervalMs = metrics.timestamp() - prevTs;
                if (metrics.isGcStorm(prevCount, intervalMs)) {
                    gcCounter.overflow();
                } else {
                    long delta = metrics.collectionCount() - prevCount;
                    for (int i = 0; i < delta; i++) {
                        gcCounter.increment();
                    }
                }
            } else {
                gcCounter.increment();  // First observation
            }

            previousGcCount.put(entityId, metrics.collectionCount());
            previousTimestamp.put(entityId, metrics.timestamp());

            // GC time counter
            Counters.Counter gcTimeCounter = gcTimeCounters.computeIfAbsent(
                entityId,
                id -> Counters.composer(countersChannel)
            );

            Long prevTime = previousGcTime.get(entityId);
            if (prevTime != null) {
                long timeDelta = metrics.collectionTime() - prevTime;
                if (timeDelta > 0) {
                    gcTimeCounter.increment();
                }
            }
            previousGcTime.put(entityId, metrics.collectionTime());

            logger.debug("Emitted GC signals for {}: count={}, time={}ms",
                entityId, metrics.collectionCount(), metrics.collectionTime());

        } catch (Exception e) {
            logger.error("Failed to emit GC signals for {}: {}",
                metrics.brokerId(), e.getMessage(), e);
        }
    }

    /**
     * Assesses broker health based on heap and GC metrics and emits Monitor signal.
     *
     * @param memoryMetrics JVM memory metrics
     */
    public void assessBrokerHealth(JvmMemoryMetrics memoryMetrics) {
        Objects.requireNonNull(memoryMetrics, "memoryMetrics cannot be null");

        try {
            String brokerId = memoryMetrics.brokerId();
            double heapUtil = memoryMetrics.heapUtilization();

            // Get or create broker monitor
            Monitors.Monitor monitor = brokerMonitors.computeIfAbsent(
                brokerId,
                id -> Monitors.composer(monitorsChannel)
            );

            // Assess condition based on heap utilization
            if (heapUtil >= HEAP_CRITICAL_THRESHOLD) {
                monitor.defective(CONFIRMED);
                logger.warn("Broker {} health DEFECTIVE: heap={}%", brokerId, (int)(heapUtil * 100));

            } else if (heapUtil >= HEAP_DEGRADED_THRESHOLD) {
                monitor.degraded(CONFIRMED);
                logger.warn("Broker {} health DEGRADED: heap={}%", brokerId, (int)(heapUtil * 100));

            } else if (heapUtil >= 0.75) {
                monitor.diverging(MEASURED);
                logger.debug("Broker {} health DIVERGING: heap={}%", brokerId, (int)(heapUtil * 100));

            } else {
                monitor.stable(CONFIRMED);
                logger.debug("Broker {} health STABLE: heap={}%", brokerId, (int)(heapUtil * 100));
            }

        } catch (Exception e) {
            logger.error("Failed to assess broker health for {}: {}",
                memoryMetrics.brokerId(), e.getMessage(), e);
        }
    }
}
