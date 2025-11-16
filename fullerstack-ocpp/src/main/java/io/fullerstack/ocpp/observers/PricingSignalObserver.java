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
 * Observer that monitors electricity pricing signals and translates them to signals.
 * <p>
 * Layer 1 (OBSERVE - External Signals): Integrates real-time electricity pricing into
 * the adaptive system, enabling cost-optimized charging behavior.
 * <p>
 * <b>Signal-First Pattern - External Pricing Events:</b>
 * <pre>
 * External Pricing Source (Utility API, MQTT, etc.)
 *   → PricingSignalObserver polls/listens
 *   → Translates price to Monitor signal
 *   → Emits STABLE (cheap), DEGRADED (moderate), CRITICAL (expensive)
 *   → Reporters/Agents react to defer/reduce charging during expensive periods
 * </pre>
 *
 * <h3>Pricing Levels:</h3>
 * <table border="1">
 * <tr><th>Price Range ($/kWh)</th><th>Monitor Signal</th><th>Agent Response</th></tr>
 * <tr><td>&lt; $0.10</td><td>STABLE</td><td>Charge at full power (cheap)</td></tr>
 * <tr><td>$0.10 - $0.30</td><td>DEGRADED</td><td>Reduce charging (moderate cost)</td></tr>
 * <tr><td>&gt; $0.30</td><td>CRITICAL</td><td>Defer charging (expensive peak pricing)</td></tr>
 * </table>
 *
 * <h3>Integration Examples:</h3>
 * <ul>
 *   <li><b>Utility API</b>: HTTP/REST polling for real-time pricing</li>
 *   <li><b>MQTT</b>: Subscribe to pricing event topics</li>
 *   <li><b>Time-of-Use (TOU)</b>: Simple time-based pricing schedules</li>
 *   <li><b>GridX, WattTime</b>: Third-party pricing aggregators</li>
 * </ul>
 *
 * <h3>Adaptive Behavior:</h3>
 * <pre>
 * Off-peak pricing (2am, $0.06/kWh):
 *   Price: STABLE → PricingSignalObserver emits STABLE
 *   → Agent charges at full power (maximize energy during cheap period)
 *
 * Peak pricing (6pm, $0.35/kWh):
 *   Price: CRITICAL → PricingSignalObserver emits CRITICAL
 *   → Agent defers charging or reduces to minimum
 *   → Saves cost, reduces grid stress
 *
 * Moderate pricing (11am, $0.15/kWh):
 *   Price: DEGRADED → PricingSignalObserver emits DEGRADED
 *   → Agent charges at reduced power
 *   → Balance between cost and charging needs
 * </pre>
 *
 * <h3>Cost Optimization Example:</h3>
 * <pre>
 * Scenario: Fleet of 10 EVs needing 500kWh total
 *
 * Without pricing awareness:
 *   Charge whenever plugged in
 *   6pm peak: 200kWh @ $0.35 = $70
 *   11pm off-peak: 300kWh @ $0.08 = $24
 *   Total: $94
 *
 * With PricingSignalObserver:
 *   Defer charging during peak ($0.35)
 *   Maximize during off-peak ($0.08)
 *   2am off-peak: 500kWh @ $0.08 = $40
 *   Total: $40 (saves $54, 57% reduction!)
 * </pre>
 */
public class PricingSignalObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PricingSignalObserver.class);

    private final Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private final ScheduledExecutorService scheduler;
    private final PricingSource pricingSource;

    /**
     * Interface for electricity pricing data sources.
     * Implement this to integrate with actual utility pricing APIs.
     */
    public interface PricingSource {
        /**
         * Get current electricity price.
         *
         * @return price per kWh in dollars
         */
        double getCurrentPricePerKwh();
    }

    public PricingSignalObserver(
        Conduit<Monitors.Monitor, Monitors.Sign> monitors,
        PricingSource pricingSource,
        long pollIntervalSeconds
    ) {
        this.monitors = monitors;
        this.pricingSource = pricingSource;

        // Start polling scheduler
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "pricing-signal-observer");
            t.setDaemon(true);
            return t;
        });

        startPolling(pollIntervalSeconds);

        logger.info("PricingSignalObserver initialized (polling every {} seconds)", pollIntervalSeconds);
    }

    /**
     * Start polling pricing source.
     */
    private void startPolling(long intervalSeconds) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                pollPricing();
            } catch (Exception e) {
                logger.error("Error polling electricity pricing", e);
            }
        }, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Poll pricing and emit appropriate signal.
     */
    private void pollPricing() {
        double pricePerKwh = pricingSource.getCurrentPricePerKwh();

        Monitors.Sign sign = assessPriceLevel(pricePerKwh);

        // Emit pricing signal
        Monitors.Monitor pricingMonitor = monitors.percept(
            cortex().name("pricing").name("electricity")
        );

        switch (sign) {
            case STABLE -> pricingMonitor.stable(Monitors.Dimension.CONFIRMED);
            case DEGRADED -> pricingMonitor.degraded(Monitors.Dimension.CONFIRMED);
            case CRITICAL -> pricingMonitor.defective(Monitors.Dimension.CONFIRMED);  // Use DEFECTIVE for expensive
            default -> {}
        }

        logger.debug("Electricity pricing: ${}/kWh → {}", pricePerKwh, sign);
    }

    /**
     * Assess price level and determine signal.
     */
    private Monitors.Sign assessPriceLevel(double pricePerKwh) {
        // Thresholds based on typical US electricity pricing
        // Adjust these for your region/market
        final double CHEAP_THRESHOLD = 0.10;    // < $0.10/kWh = cheap (off-peak)
        final double EXPENSIVE_THRESHOLD = 0.30; // > $0.30/kWh = expensive (peak)

        if (pricePerKwh < CHEAP_THRESHOLD) {
            logger.debug("CHEAP electricity: ${}/kWh (< ${})", pricePerKwh, CHEAP_THRESHOLD);
            return Monitors.Sign.STABLE;
        } else if (pricePerKwh > EXPENSIVE_THRESHOLD) {
            logger.warn("EXPENSIVE electricity: ${}/kWh (> ${})", pricePerKwh, EXPENSIVE_THRESHOLD);
            return Monitors.Sign.CRITICAL;
        } else {
            logger.info("MODERATE electricity: ${}/kWh", pricePerKwh);
            return Monitors.Sign.DEGRADED;
        }
    }

    @Override
    public void close() {
        logger.info("Closing PricingSignalObserver");
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
     * Simple time-of-use (TOU) pricing source.
     * For demo/testing purposes - production should use actual utility API.
     */
    public static class TimeOfUsePricingSource implements PricingSource {
        @Override
        public double getCurrentPricePerKwh() {
            int hour = java.time.LocalTime.now().getHour();

            // Typical TOU pricing structure:
            // Off-peak (midnight-6am): $0.06/kWh
            // Mid-peak (6am-4pm, 9pm-midnight): $0.12/kWh
            // Peak (4pm-9pm): $0.35/kWh

            if (hour >= 0 && hour < 6) {
                return 0.06;  // Off-peak (cheapest - ideal for EV charging)
            } else if (hour >= 16 && hour < 21) {
                return 0.35;  // Peak (expensive - avoid charging)
            } else {
                return 0.12;  // Mid-peak (moderate)
            }
        }
    }

    /**
     * Fixed pricing source for simple deployments.
     */
    public static class FixedPricingSource implements PricingSource {
        private final double fixedPrice;

        public FixedPricingSource(double fixedPrice) {
            this.fixedPrice = fixedPrice;
        }

        @Override
        public double getCurrentPricePerKwh() {
            return fixedPrice;
        }
    }
}
