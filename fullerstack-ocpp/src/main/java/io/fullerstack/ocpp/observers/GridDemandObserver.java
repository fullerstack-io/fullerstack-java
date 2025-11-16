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
 * Observer that monitors grid demand-response events and translates them to signals.
 * <p>
 * Layer 1 (OBSERVE - External Signals): Integrates utility grid demand signals into
 * the adaptive system, enabling grid-friendly charging behavior.
 * <p>
 * <b>Signal-First Pattern - External Grid Events:</b>
 * <pre>
 * External Event Source (Utility API, MQTT, etc.)
 *   → GridDemandObserver polls/listens
 *   → Translates grid condition to Monitor signal
 *   → Emits STABLE (normal), DEGRADED (high demand), CRITICAL (emergency)
 *   → Reporters/Agents react to reduce/defer charging
 * </pre>
 *
 * <h3>Grid Demand Levels:</h3>
 * <table border="1">
 * <tr><th>Grid Condition</th><th>Monitor Signal</th><th>Agent Response</th></tr>
 * <tr><td>Normal demand</td><td>STABLE</td><td>Charge at full power</td></tr>
 * <tr><td>High demand (peak hours)</td><td>DEGRADED</td><td>Reduce charging power</td></tr>
 * <tr><td>Emergency demand-response</td><td>CRITICAL</td><td>Pause charging or minimum power</td></tr>
 * </table>
 *
 * <h3>Integration Examples:</h3>
 * <ul>
 *   <li><b>OpenADR</b>: Automated Demand Response protocol</li>
 *   <li><b>Utility API</b>: HTTP/REST polling for grid status</li>
 *   <li><b>MQTT</b>: Subscribe to grid event topics</li>
 *   <li><b>Time-based</b>: Simple time-of-day rules (peak = 4pm-9pm)</li>
 * </ul>
 *
 * <h3>Adaptive Behavior:</h3>
 * <pre>
 * Normal operation (11am):
 *   Grid: STABLE → Chargers at 32A (full power)
 *
 * Peak demand event (6pm):
 *   Grid: DEGRADED → GridDemandObserver emits DEGRADED
 *   → Agent reduces chargers to 16A
 *   → Total grid load reduced by 50%
 *
 * Emergency demand-response (heat wave):
 *   Grid: CRITICAL → GridDemandObserver emits CRITICAL
 *   → Agent pauses non-essential charging
 *   → Only emergency/fleet vehicles continue at minimum power
 * </pre>
 */
public class GridDemandObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(GridDemandObserver.class);

    private final Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private final ScheduledExecutorService scheduler;
    private final GridDemandSource demandSource;

    /**
     * Interface for grid demand data sources.
     * Implement this to integrate with actual utility APIs.
     */
    public interface GridDemandSource {
        /**
         * Get current grid demand level.
         *
         * @return demand level: NORMAL, HIGH, or EMERGENCY
         */
        DemandLevel getCurrentDemand();

        enum DemandLevel {
            NORMAL,    // Normal grid operation
            HIGH,      // Peak demand hours or high load
            EMERGENCY  // Critical demand-response event
        }
    }

    public GridDemandObserver(
        Conduit<Monitors.Monitor, Monitors.Sign> monitors,
        GridDemandSource demandSource,
        long pollIntervalSeconds
    ) {
        this.monitors = monitors;
        this.demandSource = demandSource;

        // Start polling scheduler
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "grid-demand-observer");
            t.setDaemon(true);
            return t;
        });

        startPolling(pollIntervalSeconds);

        logger.info("GridDemandObserver initialized (polling every {} seconds)", pollIntervalSeconds);
    }

    /**
     * Start polling grid demand source.
     */
    private void startPolling(long intervalSeconds) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                pollGridDemand();
            } catch (Exception e) {
                logger.error("Error polling grid demand", e);
            }
        }, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Poll grid demand and emit appropriate signal.
     */
    private void pollGridDemand() {
        GridDemandSource.DemandLevel demand = demandSource.getCurrentDemand();

        Monitors.Sign sign = switch (demand) {
            case NORMAL -> Monitors.Sign.STABLE;
            case HIGH -> Monitors.Sign.DEGRADED;
            case EMERGENCY -> Monitors.Sign.CRITICAL;
        };

        // Emit grid demand signal
        Monitors.Monitor gridMonitor = monitors.percept(
            cortex().name("grid").name("demand")
        );

        switch (sign) {
            case STABLE -> gridMonitor.stable(Monitors.Dimension.CONFIRMED);
            case DEGRADED -> gridMonitor.degraded(Monitors.Dimension.CONFIRMED);
            case CRITICAL -> gridMonitor.defective(Monitors.Dimension.CONFIRMED);  // Use DEFECTIVE for emergency
            default -> {}
        }

        logger.debug("Grid demand: {} → {}", demand, sign);
    }

    @Override
    public void close() {
        logger.info("Closing GridDemandObserver");
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
     * Simple time-of-day based grid demand source.
     * For demo/testing purposes - production should use actual utility API.
     */
    public static class TimeOfDayDemandSource implements GridDemandSource {
        @Override
        public DemandLevel getCurrentDemand() {
            int hour = java.time.LocalTime.now().getHour();

            // Peak hours: 4pm-9pm (16:00-21:00)
            if (hour >= 16 && hour < 21) {
                return DemandLevel.HIGH;
            }
            // Off-peak: midnight-6am (0:00-6:00)
            else if (hour >= 0 && hour < 6) {
                return DemandLevel.NORMAL;
            }
            // Normal hours
            else {
                return DemandLevel.NORMAL;
            }
        }
    }
}
