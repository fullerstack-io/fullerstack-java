package io.fullerstack.ocpp.observers;

import io.humainary.substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.monitors.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Observer that monitors renewable energy availability and translates it to signals.
 * <p>
 * Layer 1 (OBSERVE - External Signals): Integrates solar/wind production data into
 * the adaptive system, enabling carbon-optimized charging behavior.
 * <p>
 * <b>Signal-First Pattern - External Renewable Energy Events:</b>
 * <pre>
 * External Renewable Source (Solar API, Wind API, Grid API)
 *   → RenewableEnergyObserver polls/listens
 *   → Translates renewable availability to Monitor signal
 *   → Emits STABLE (high renewable %), DEGRADED (moderate), CRITICAL (low renewable %)
 *   → Reporters/Agents react to maximize charging when renewables abundant
 * </pre>
 *
 * <h3>Renewable Energy Levels:</h3>
 * <table border="1">
 * <tr><th>Renewable %</th><th>Monitor Signal</th><th>Agent Response</th></tr>
 * <tr><td>&gt; 70%</td><td>STABLE</td><td>Charge at full power (clean energy)</td></tr>
 * <tr><td>40-70%</td><td>DEGRADED</td><td>Moderate charging (mixed sources)</td></tr>
 * <tr><td>&lt; 40%</td><td>CRITICAL</td><td>Defer charging (fossil fuel heavy)</td></tr>
 * </table>
 *
 * <h3>Integration Examples:</h3>
 * <ul>
 *   <li><b>WattTime</b>: Real-time grid carbon intensity API</li>
 *   <li><b>ElectricityMap</b>: Live renewable energy mix data</li>
 *   <li><b>Utility API</b>: Local solar/wind production forecasts</li>
 *   <li><b>Site Solar</b>: On-site solar panel production monitoring</li>
 *   <li><b>MQTT</b>: Subscribe to renewable energy event topics</li>
 * </ul>
 *
 * <h3>Adaptive Behavior:</h3>
 * <pre>
 * Sunny midday (12pm, 85% solar):
 *   Renewable: STABLE → RenewableEnergyObserver emits STABLE
 *   → Agent increases charging (abundant clean energy)
 *   → Maximize use of excess solar production
 *
 * Evening (7pm, 25% renewable, 75% fossil):
 *   Renewable: CRITICAL → RenewableEnergyObserver emits CRITICAL
 *   → Agent defers charging to avoid fossil fuel consumption
 *   → Wait for next solar peak
 *
 * Cloudy afternoon (3pm, 55% renewable):
 *   Renewable: DEGRADED → RenewableEnergyObserver emits DEGRADED
 *   → Agent charges at moderate rate
 *   → Balance between energy needs and carbon impact
 * </pre>
 *
 * <h3>Carbon Optimization Example:</h3>
 * <pre>
 * Scenario: 500kWh charging needed over 24 hours
 *
 * Without renewable awareness:
 *   Charge spread evenly: avg 50% renewable
 *   500kWh × 50% fossil = 250kWh fossil fuel
 *   CO2: ~200 lbs (depending on grid)
 *
 * With RenewableEnergyObserver:
 *   Maximize during solar peak (10am-2pm, 85% renewable)
 *   Minimize during evening (6pm-9pm, 25% renewable)
 *   500kWh × 15% fossil = 75kWh fossil fuel
 *   CO2: ~60 lbs (70% reduction!)
 * </pre>
 *
 * <h3>Grid Balancing Benefits:</h3>
 * <ul>
 *   <li><b>Duck Curve Mitigation</b>: Absorb excess solar during midday</li>
 *   <li><b>Curtailment Reduction</b>: Use renewable energy that would be wasted</li>
 *   <li><b>Grid Stability</b>: Flexible load that follows renewable production</li>
 *   <li><b>Carbon Reduction</b>: Charge when grid is cleanest</li>
 * </ul>
 */
public class RenewableEnergyObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(RenewableEnergyObserver.class);

    private final Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private final ScheduledExecutorService scheduler;
    private final RenewableEnergySource renewableSource;

    /**
     * Interface for renewable energy data sources.
     * Implement this to integrate with actual renewable energy APIs.
     */
    public interface RenewableEnergySource {
        /**
         * Get current renewable energy percentage of total grid mix.
         *
         * @return percentage (0.0 - 1.0) of renewable energy in current grid mix
         */
        double getRenewablePercentage();

        /**
         * Optional: Get carbon intensity (gCO2/kWh).
         *
         * @return carbon intensity in grams CO2 per kWh
         */
        default double getCarbonIntensity() {
            // Default calculation: assume 0 g/kWh for renewable, 500 g/kWh for fossil
            double renewablePct = getRenewablePercentage();
            return (1.0 - renewablePct) * 500.0;
        }
    }

    public RenewableEnergyObserver(
        Conduit<Monitors.Monitor, Monitors.Sign> monitors,
        RenewableEnergySource renewableSource,
        long pollIntervalSeconds
    ) {
        this.monitors = monitors;
        this.renewableSource = renewableSource;

        // Start polling scheduler
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "renewable-energy-observer");
            t.setDaemon(true);
            return t;
        });

        startPolling(pollIntervalSeconds);

        logger.info("RenewableEnergyObserver initialized (polling every {} seconds)", pollIntervalSeconds);
    }

    /**
     * Start polling renewable energy source.
     */
    private void startPolling(long intervalSeconds) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                pollRenewableEnergy();
            } catch (Exception e) {
                logger.error("Error polling renewable energy data", e);
            }
        }, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Poll renewable energy data and emit appropriate signal.
     */
    private void pollRenewableEnergy() {
        double renewablePct = renewableSource.getRenewablePercentage();
        double carbonIntensity = renewableSource.getCarbonIntensity();

        Monitors.Sign sign = assessRenewableLevel(renewablePct);

        // Emit renewable energy signal
        Monitors.Monitor renewableMonitor = monitors.percept(
            cortex().name("renewable").name("energy")
        );

        switch (sign) {
            case STABLE -> renewableMonitor.stable(Monitors.Dimension.CONFIRMED);
            case DEGRADED -> renewableMonitor.degraded(Monitors.Dimension.CONFIRMED);
            case CRITICAL -> renewableMonitor.defective(Monitors.Dimension.CONFIRMED);  // Use DEFECTIVE for low renewable
            default -> {}
        }

        logger.debug("Renewable energy: {}% ({}g CO2/kWh) → {}",
            (int)(renewablePct * 100), (int)carbonIntensity, sign);
    }

    /**
     * Assess renewable energy level and determine signal.
     */
    private Monitors.Sign assessRenewableLevel(double renewablePct) {
        // Thresholds for renewable energy percentage
        final double HIGH_RENEWABLE = 0.70;   // > 70% = abundant clean energy
        final double LOW_RENEWABLE = 0.40;    // < 40% = fossil fuel heavy

        if (renewablePct > HIGH_RENEWABLE) {
            logger.debug("HIGH renewable energy: {}% (> {}%)",
                (int)(renewablePct * 100), (int)(HIGH_RENEWABLE * 100));
            return Monitors.Sign.STABLE;
        } else if (renewablePct < LOW_RENEWABLE) {
            logger.warn("LOW renewable energy: {}% (< {}%) - fossil fuel heavy",
                (int)(renewablePct * 100), (int)(LOW_RENEWABLE * 100));
            return Monitors.Sign.CRITICAL;
        } else {
            logger.info("MODERATE renewable energy: {}%", (int)(renewablePct * 100));
            return Monitors.Sign.DEGRADED;
        }
    }

    @Override
    public void close() {
        logger.info("Closing RenewableEnergyObserver");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
    }

    /**
     * Simple time-based solar production source.
     * Models typical solar production curve throughout the day.
     * For demo/testing purposes - production should use actual renewable data APIs.
     */
    public static class SolarProductionSource implements RenewableEnergySource {
        @Override
        public double getRenewablePercentage() {
            int hour = java.time.LocalTime.now().getHour();

            // Model typical solar production curve + baseline renewable (wind, hydro)
            // Peak solar: 10am-2pm (~85% renewable including baseline)
            // Evening: 6pm-9pm (minimal solar, ~25% renewable from wind/hydro)
            // Night: midnight-6am (~30% renewable from wind)

            if (hour >= 10 && hour < 14) {
                return 0.85;  // Peak solar + baseline renewables
            } else if (hour >= 8 && hour < 10) {
                return 0.70;  // Morning solar ramp-up
            } else if (hour >= 14 && hour < 17) {
                return 0.65;  // Afternoon solar declining
            } else if (hour >= 18 && hour < 21) {
                return 0.25;  // Evening - solar gone, limited wind
            } else {
                return 0.30;  // Night - wind + hydro only
            }
        }

        @Override
        public double getCarbonIntensity() {
            double renewablePct = getRenewablePercentage();
            // Renewable: ~0 g/kWh
            // Natural gas: ~400 g/kWh
            // Coal: ~900 g/kWh
            // Average fossil mix: ~500 g/kWh
            return (1.0 - renewablePct) * 500.0;
        }
    }

    /**
     * Fixed renewable percentage source for simple deployments.
     */
    public static class FixedRenewableSource implements RenewableEnergySource {
        private final double fixedPercentage;

        public FixedRenewableSource(double fixedPercentage) {
            this.fixedPercentage = Math.max(0.0, Math.min(1.0, fixedPercentage));
        }

        @Override
        public double getRenewablePercentage() {
            return fixedPercentage;
        }
    }
}
