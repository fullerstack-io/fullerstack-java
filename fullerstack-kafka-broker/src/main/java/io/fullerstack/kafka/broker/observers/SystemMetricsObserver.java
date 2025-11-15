package io.fullerstack.kafka.broker.observers;

import io.fullerstack.kafka.broker.models.SystemMetrics;
import io.humainary.substrates.api.Substrates.Channel;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Emits Serventis signals for system metrics (CPU, file descriptors) using RC6 APIs.
 *
 * <p><b>Layer 1 (OBSERVE): Raw Signal Emission</b>
 * This observer emits raw signals using Gauges API:
 * <ul>
 *   <li>Process CPU → Gauges (INCREMENT/DECREMENT/OVERFLOW >90%)</li>
 *   <li>System CPU → Gauges</li>
 *   <li>Open FDs → Gauges (OVERFLOW >95%)</li>
 * </ul>
 *
 * <p><b>Thresholds:</b>
 * <pre>
 * CPU OVERFLOW: >90%
 * FD OVERFLOW: >95%
 * </pre>
 */
public class SystemMetricsObserver {
    private static final Logger logger = LoggerFactory.getLogger(SystemMetricsObserver.class);

    private static final double CPU_OVERFLOW_THRESHOLD = 0.90;
    private static final double FD_OVERFLOW_THRESHOLD = 0.95;

    private final Name circuitName;
    private final Channel<Gauges.Sign> gaugesChannel;

    // Instruments (cached per entity)
    private final Map<String, Gauges.Gauge> processCpuGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauges.Gauge> systemCpuGauges = new ConcurrentHashMap<>();
    private final Map<String, Gauges.Gauge> fdGauges = new ConcurrentHashMap<>();

    // Previous values for delta calculation
    private final Map<String, Double> previousProcessCpu = new ConcurrentHashMap<>();
    private final Map<String, Double> previousSystemCpu = new ConcurrentHashMap<>();
    private final Map<String, Double> previousFdUtil = new ConcurrentHashMap<>();

    /**
     * Creates a SystemMetricsObserver.
     *
     * @param circuitName   Circuit name for logging
     * @param gaugesChannel Channel for Gauges signals
     */
    public SystemMetricsObserver(
        Name circuitName,
        Channel<Gauges.Sign> gaugesChannel
    ) {
        this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
        this.gaugesChannel = Objects.requireNonNull(gaugesChannel, "gaugesChannel cannot be null");
    }

    /**
     * Emits Serventis signals for system metrics.
     *
     * @param metrics System metrics from JMX
     */
    public void emit(SystemMetrics metrics) {
        Objects.requireNonNull(metrics, "metrics cannot be null");

        try {
            String brokerId = metrics.brokerId();

            // Process CPU
            emitProcessCpu(brokerId, metrics.processCpuLoad());

            // System CPU
            emitSystemCpu(brokerId, metrics.systemCpuLoad());

            // File descriptors
            emitFileDescriptors(brokerId, metrics.fdUtilization());

            logger.debug("Emitted system signals for {}: processCpu={}%, fds={}/{}",
                brokerId,
                (int)(metrics.processCpuLoad() * 100),
                metrics.openFileDescriptorCount(),
                metrics.maxFileDescriptorCount());

        } catch (Exception e) {
            logger.error("Failed to emit system signals for {}: {}",
                metrics.brokerId(), e.getMessage(), e);
        }
    }

    private void emitProcessCpu(String brokerId, double cpuLoad) {
        Gauges.Gauge gauge = processCpuGauges.computeIfAbsent(
            brokerId,
            id -> Gauges.composer(gaugesChannel)
        );

        if (cpuLoad >= CPU_OVERFLOW_THRESHOLD) {
            gauge.overflow();
        } else {
            Double prevCpu = previousProcessCpu.get(brokerId);
            if (prevCpu != null) {
                if (cpuLoad > prevCpu) {
                    gauge.increment();
                } else if (cpuLoad < prevCpu) {
                    gauge.decrement();
                }
            } else {
                gauge.increment();  // First observation
            }
        }

        previousProcessCpu.put(brokerId, cpuLoad);
    }

    private void emitSystemCpu(String brokerId, double cpuLoad) {
        Gauges.Gauge gauge = systemCpuGauges.computeIfAbsent(
            brokerId,
            id -> Gauges.composer(gaugesChannel)
        );

        if (cpuLoad >= CPU_OVERFLOW_THRESHOLD) {
            gauge.overflow();
        } else {
            Double prevCpu = previousSystemCpu.get(brokerId);
            if (prevCpu != null) {
                if (cpuLoad > prevCpu) {
                    gauge.increment();
                } else if (cpuLoad < prevCpu) {
                    gauge.decrement();
                }
            } else {
                gauge.increment();  // First observation
            }
        }

        previousSystemCpu.put(brokerId, cpuLoad);
    }

    private void emitFileDescriptors(String brokerId, double fdUtil) {
        Gauges.Gauge gauge = fdGauges.computeIfAbsent(
            brokerId,
            id -> Gauges.composer(gaugesChannel)
        );

        if (fdUtil >= FD_OVERFLOW_THRESHOLD) {
            gauge.overflow();
        } else {
            Double prevUtil = previousFdUtil.get(brokerId);
            if (prevUtil != null) {
                if (fdUtil > prevUtil) {
                    gauge.increment();
                } else if (fdUtil < prevUtil) {
                    gauge.decrement();
                }
            } else {
                gauge.increment();  // First observation
            }
        }

        previousFdUtil.put(brokerId, fdUtil);
    }
}
