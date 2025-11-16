package io.fullerstack.ocpp.reporters;

import io.humainary.substrates.Channel;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.Name;
import io.humainary.substrates.Registrar;
import io.humainary.substrates.Subject;
import io.humainary.substrates.Subscription;
import io.humainary.substrates.ext.serventis.ext.gauges.Gauges;
import io.humainary.substrates.ext.serventis.ext.reporters.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Reporter that assesses total power consumption against grid capacity.
 * <p>
 * Layer 3 (DECIDE): Assesses urgency of load balancing situation.
 * <p>
 * <b>Assessment Logic:</b>
 * <pre>
 * Total Power vs Grid Capacity:
 *   < 80%  → NORMAL   (plenty of headroom)
 *   80-95% → WARNING  (approaching capacity, preemptive action)
 *   > 95%  → CRITICAL (exceeding or at capacity, immediate action required)
 * </pre>
 *
 * <h3>Signal Flow:</h3>
 * <pre>
 * MeterValues (per charger) → Power Gauges
 *                                   ↓
 * LoadBalancingReporter aggregates → Total power gauge
 *                                   ↓
 * Assess against grid capacity → Emit urgency signal
 *                                   ↓
 * LoadBalancingAgent reacts → Adjust power limits
 * </pre>
 */
public class LoadBalancingReporter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(LoadBalancingReporter.class);

    private static final double WARNING_THRESHOLD = 0.80;   // 80% of capacity
    private static final double CRITICAL_THRESHOLD = 0.95;  // 95% of capacity

    private final Conduit<Reporters.Reporter, Reporters.Signal> reporters;
    private final Subscription gaugeSubscription;
    private final double gridCapacityKw;

    // Track power consumption per charger
    private final Map<String, Double> chargerPowerKw;

    public LoadBalancingReporter(
        Conduit<Gauges.Gauge, Gauges.Sign> gauges,
        Conduit<Reporters.Reporter, Reporters.Signal> reporters,
        double gridCapacityKw
    ) {
        this.reporters = reporters;
        this.gridCapacityKw = gridCapacityKw;
        this.chargerPowerKw = new ConcurrentHashMap<>();

        // Subscribe to power gauges from all chargers
        this.gaugeSubscription = gauges.subscribe(
            cortex().subscriber(
                cortex().name("load-balancing-gauge-subscriber"),
                this::handlePowerGauge
            )
        );

        logger.info("LoadBalancingReporter initialized (Grid capacity: {}kW)", gridCapacityKw);
    }

    /**
     * Handle power gauge updates and assess total load.
     */
    private void handlePowerGauge(
        Subject<Channel<Gauges.Sign>> subject,
        Registrar<Gauges.Sign> registrar
    ) {
        Name gaugeName = subject.name();

        // Filter for power gauges (pattern: "{chargerId}.power")
        if (isPowerGauge(gaugeName)) {
            String chargerId = extractChargerId(gaugeName);

            registrar.register(sign -> {
                if (sign instanceof Gauges.Sign.Measured measured) {
                    double powerKw = measured.value() / 1000.0;  // Assume watts → kilowatts

                    // Update charger power
                    if (powerKw > 0) {
                        chargerPowerKw.put(chargerId, powerKw);
                    } else {
                        chargerPowerKw.remove(chargerId);  // Charger stopped charging
                    }

                    // Assess total load and emit urgency signal
                    assessLoadBalancing();
                }
            });
        }
    }

    /**
     * Assess total power consumption and emit appropriate urgency signal.
     */
    private void assessLoadBalancing() {
        double totalPowerKw = getTotalPowerKw();
        double utilizationRatio = totalPowerKw / gridCapacityKw;

        Reporters.Sign sign = assessUrgency(utilizationRatio, totalPowerKw);

        // Emit reporter signal
        Reporters.Reporter reporter = reporters.percept(
            cortex().name("load-balancing").name("health")
        );

        reporter.sign(sign, Reporters.Dimension.CONFIRMED);

        logger.debug("Load balancing assessment: {}kW / {}kW ({}%) → {}",
            totalPowerKw, gridCapacityKw, (int)(utilizationRatio * 100), sign);
    }

    /**
     * Assess urgency based on grid utilization.
     */
    private Reporters.Sign assessUrgency(double utilizationRatio, double totalPowerKw) {
        if (utilizationRatio >= CRITICAL_THRESHOLD) {
            logger.warn("CRITICAL: Total power {}kW at {}% of capacity (threshold: {}%)",
                totalPowerKw, (int)(utilizationRatio * 100), (int)(CRITICAL_THRESHOLD * 100));
            return Reporters.Sign.CRITICAL;

        } else if (utilizationRatio >= WARNING_THRESHOLD) {
            logger.info("WARNING: Total power {}kW at {}% of capacity (threshold: {}%)",
                totalPowerKw, (int)(utilizationRatio * 100), (int)(WARNING_THRESHOLD * 100));
            return Reporters.Sign.WARNING;

        } else {
            logger.debug("NORMAL: Total power {}kW at {}% of capacity",
                totalPowerKw, (int)(utilizationRatio * 100));
            return Reporters.Sign.NORMAL;
        }
    }

    /**
     * Get total power consumption across all chargers.
     */
    private double getTotalPowerKw() {
        return chargerPowerKw.values().stream()
            .mapToDouble(Double::doubleValue)
            .sum();
    }

    /**
     * Check if gauge is a power gauge.
     */
    private boolean isPowerGauge(Name gaugeName) {
        return gaugeName.depth() == 2 && "power".equals(gaugeName.value());
    }

    /**
     * Extract charger ID from gauge name.
     */
    private String extractChargerId(Name gaugeName) {
        Name enclosure = gaugeName.enclosure();
        return enclosure != null ? enclosure.value() : gaugeName.value();
    }

    @Override
    public void close() {
        logger.info("Closing LoadBalancingReporter");
        if (gaugeSubscription != null) {
            gaugeSubscription.cancel();
        }
        chargerPowerKw.clear();
    }
}
