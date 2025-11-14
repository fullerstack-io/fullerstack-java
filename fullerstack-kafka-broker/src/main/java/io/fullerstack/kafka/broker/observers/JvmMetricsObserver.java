package io.fullerstack.kafka.broker.receptors;

import io.fullerstack.kafka.broker.models.JvmGcMetrics;
import io.fullerstack.kafka.broker.models.JvmMemoryMetrics;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 1 (OBSERVE): Collects JVM memory and GC metrics from JMX and emits raw Serventis signals.
 *
 * <p><b>Signal-Flow Architecture - Layer 1 (OBSERVE Phase)</b>
 * This receptor:
 * <ul>
 *   <li>Receives JMX metrics externally (via BrokerHealthSensor)</li>
 *   <li>Emits ONLY raw signals (Gauges, Counters) - NO interpretation</li>
 *   <li>Does NOT perform pattern detection or assessment</li>
 *   <li>Exposes Conduits for Layer 2 (Monitors) to subscribe to</li>
 * </ul>
 *
 * <p><b>Signal Types Emitted:</b>
 * <pre>
 * Heap Memory → Gauges:
 *   - INCREMENT: Heap utilization increasing
 *   - DECREMENT: Heap utilization decreasing
 *   - OVERFLOW: Heap &gt;90% (raw threshold, not assessment)
 *
 * Non-Heap Memory → Gauges:
 *   - INCREMENT: Non-heap growing
 *
 * GC Collections → Counters:
 *   - INCREMENT: GC occurred
 *   - OVERFLOW: GC storm detected (>10 collections/sec)
 *
 * GC Time → Counters:
 *   - INCREMENT: GC time increased
 * </pre>
 *
 * <p><b>Usage Pattern:</b>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("broker.health"));
 *
 * // Create receptor (Layer 1)
 * JvmMetricsObserver receptor = new JvmMetricsObserver(circuit);
 *
 * // Feed JMX metrics
 * receptor.emitMemory(jvmMemoryMetrics);
 * receptor.emitGc(jvmGcMetrics);
 *
 * // Layer 2 (Monitor) subscribes to receptor's conduits
 * JvmHealthMonitor monitor = new JvmHealthMonitor(
 *     circuit,
 *     receptor.gauges(),
 *     receptor.counters()
 * );
 * }</pre>
 *
 * <p><b>Thresholds:</b>
 * Note: These are RAW thresholds for signal emission, not health assessments.
 * Layer 2 (JvmHealthMonitor) performs actual pattern detection and assessment.
 * <pre>
 * Heap OVERFLOW signal: >90% utilization (just reporting what we see)
 * GC OVERFLOW signal: >10 collections/sec (just reporting what we see)
 * </pre>
 */
public class JvmMetricsObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(JvmMetricsObserver.class);

    // Raw thresholds for signal emission (NOT health assessment)
    private static final double HEAP_OVERFLOW_THRESHOLD = 0.90;
    private static final int GC_STORM_THRESHOLD = 10;  // GCs per second

    private final Circuit circuit;
    private final Conduit<Gauges.Gauge, Gauges.Signal> gauges;
    private final Conduit<Counters.Counter, Counters.Signal> counters;

    // Previous values for delta calculation (per entity)
    private final Map<String, Double> previousHeapUtil = new ConcurrentHashMap<>();
    private final Map<String, Long> previousGcCount = new ConcurrentHashMap<>();
    private final Map<String, Long> previousGcTime = new ConcurrentHashMap<>();
    private final Map<String, Long> previousTimestamp = new ConcurrentHashMap<>();

    /**
     * Creates a JvmMetricsObserver (Layer 1 - OBSERVE).
     *
     * @param circuit Circuit for creating Conduits
     */
    public JvmMetricsObserver(Circuit circuit) {
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");

        // Create Conduits for raw signals
        this.gauges = circuit.conduit(cortex().name("gauges"), Gauges::composer);
        this.counters = circuit.conduit(cortex().name("counters"), Counters::composer);

        logger.info("JvmMetricsObserver created (Layer 1 - OBSERVE)");
    }

    /**
     * Emits raw Gauges signals for JVM memory metrics.
     * NO pattern detection or assessment - just raw signals.
     *
     * @param metrics JVM memory metrics from JMX
     */
    public void emitMemory(JvmMemoryMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            String brokerId = metrics.brokerId();
            double heapUtil = metrics.heapUtilization();

            // Get heap gauge for this broker
            Gauges.Gauge heapGauge = gauges.percept(cortex().name("jvm.heap." + brokerId));

            // Emit raw heap signals - NO interpretation, just what we observe
            if (heapUtil >= HEAP_OVERFLOW_THRESHOLD) {
                // Just reporting: "heap is above 90%"
                heapGauge.overflow();
            } else {
                // Report direction of change
                Double prevUtil = previousHeapUtil.get(brokerId);
                if (prevUtil != null) {
                    if (heapUtil > prevUtil) {
                        heapGauge.increment();  // "heap is increasing"
                    } else if (heapUtil < prevUtil) {
                        heapGauge.decrement();  // "heap is decreasing"
                    }
                    // If equal, don't emit (no change)
                } else {
                    heapGauge.increment();  // First observation
                }
            }

            previousHeapUtil.put(brokerId, heapUtil);

            // Non-heap gauge (simplified - just track that it exists)
            Gauges.Gauge nonHeapGauge = gauges.percept(cortex().name("jvm.non-heap." + brokerId));
            nonHeapGauge.increment();  // Non-heap typically grows over time

            logger.debug("[Layer 1] Emitted JVM memory signals for {}: heap={}%",
                brokerId, (int)(heapUtil * 100));

        } catch (java.lang.Exception e) {
            logger.error("Failed to emit JVM memory signals for {}: {}",
                metrics.brokerId(), e.getMessage(), e);
        }
    }

    /**
     * Emits raw Counters signals for GC metrics.
     * NO pattern detection or assessment - just raw signals.
     *
     * @param metrics GC metrics from JMX
     */
    public void emitGc(JvmGcMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            String entityId = metrics.brokerId() + "." + metrics.collectorName();

            // Get GC counter for this collector
            Counters.Counter gcCounter = counters.percept(cortex().name("jvm.gc.count." + entityId));

            // Check for GC storm (raw threshold, not assessment)
            Long prevCount = previousGcCount.get(entityId);
            Long prevTs = previousTimestamp.get(entityId);

            if (prevCount != null && prevTs != null) {
                long intervalMs = metrics.timestamp() - prevTs;
                if (intervalMs > 0) {
                    long delta = metrics.collectionCount() - prevCount;
                    double gcPerSec = (delta * 1000.0) / intervalMs;

                    if (gcPerSec > GC_STORM_THRESHOLD) {
                        // Just reporting: "GC rate is high"
                        gcCounter.overflow();
                    } else {
                        // Report each GC occurrence
                        for (int i = 0; i < delta; i++) {
                            gcCounter.increment();
                        }
                    }
                }
            } else {
                gcCounter.increment();  // First observation
            }

            previousGcCount.put(entityId, metrics.collectionCount());
            previousTimestamp.put(entityId, metrics.timestamp());

            // GC time counter
            Counters.Counter gcTimeCounter = counters.percept(cortex().name("jvm.gc.time." + entityId));

            Long prevTime = previousGcTime.get(entityId);
            if (prevTime != null) {
                long timeDelta = metrics.collectionTime() - prevTime;
                if (timeDelta > 0) {
                    gcTimeCounter.increment();  // "GC time increased"
                }
            }
            previousGcTime.put(entityId, metrics.collectionTime());

            logger.debug("[Layer 1] Emitted GC signals for {}: count={}, time={}ms",
                entityId, metrics.collectionCount(), metrics.collectionTime());

        } catch (java.lang.Exception e) {
            logger.error("Failed to emit GC signals for {}: {}",
                metrics.brokerId(), e.getMessage(), e);
        }
    }

    /**
     * Returns the Gauges conduit for Layer 2 (Monitors) to subscribe to.
     *
     * @return Gauges conduit emitting raw gauge signals
     */
    public Conduit<Gauges.Gauge, Gauges.Signal> gauges() {
        return gauges;
    }

    /**
     * Returns the Counters conduit for Layer 2 (Monitors) to subscribe to.
     *
     * @return Counters conduit emitting raw counter signals
     */
    public Conduit<Counters.Counter, Counters.Signal> counters() {
        return counters;
    }

    @Override
    public void close() {
        logger.info("JvmMetricsObserver closed");
        // Circuit lifecycle is managed externally
    }
}
