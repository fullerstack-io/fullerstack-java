package io.fullerstack.ocpp.monitors;

import io.humainary.substrates.Conduit;
import io.humainary.substrates.Subscription;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Monitors charger heartbeat status and emits DOWN signals for offline chargers.
 * <p>
 * This is part of Layer 1-2 (OBSERVE/ORIENT) that provides operational
 * situation awareness by tracking which chargers are actively communicating.
 * </p>
 * <p>
 * Pattern similar to JvmMetricsObserver - periodically checks state and emits signals.
 * </p>
 */
public class ChargerConnectionMonitor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ChargerConnectionMonitor.class);
    private static final long HEARTBEAT_TIMEOUT_MS = 300_000;  // 5 minutes
    private static final long CHECK_INTERVAL_MS = 60_000;      // 1 minute

    private final Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private final Map<String, Long> lastHeartbeatTimes;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    public ChargerConnectionMonitor(Conduit<Monitors.Monitor, Monitors.Sign> monitors) {
        this.monitors = monitors;
        this.lastHeartbeatTimes = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "charger-connection-monitor");
            t.setDaemon(true);
            return t;
        });
        this.running = false;
    }

    /**
     * Start monitoring charger connections.
     */
    public void start() {
        if (running) {
            logger.warn("ChargerConnectionMonitor already running");
            return;
        }

        logger.info("Starting ChargerConnectionMonitor");
        running = true;

        scheduler.scheduleAtFixedRate(
            this::checkChargerConnections,
            CHECK_INTERVAL_MS,
            CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Stop monitoring.
     */
    public void stop() {
        if (!running) {
            return;
        }

        logger.info("Stopping ChargerConnectionMonitor");
        running = false;
        scheduler.shutdown();
    }

    /**
     * Update heartbeat time for a charger (called by message observer).
     */
    public void recordHeartbeat(String chargerId) {
        lastHeartbeatTimes.put(chargerId, System.currentTimeMillis());
    }

    /**
     * Periodically check all chargers for heartbeat timeout.
     */
    private void checkChargerConnections() {
        long currentTime = System.currentTimeMillis();

        lastHeartbeatTimes.forEach((chargerId, lastHeartbeat) -> {
            long timeSinceHeartbeat = currentTime - lastHeartbeat;

            Monitors.Monitor chargerMonitor = monitors.percept(
                cortex().name(chargerId)
            );

            if (timeSinceHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                // Charger offline - emit DOWN signal
                logger.warn("Charger {} offline (last heartbeat {} ms ago)",
                    chargerId, timeSinceHeartbeat);
                chargerMonitor.down(Monitors.Dimension.CONFIRMED);
            } else if (timeSinceHeartbeat > (HEARTBEAT_TIMEOUT_MS / 2)) {
                // Approaching timeout - emit DIVERGING (early warning)
                logger.debug("Charger {} heartbeat delayed ({} ms)",
                    chargerId, timeSinceHeartbeat);
                chargerMonitor.diverging(Monitors.Dimension.CONFIRMED);
            }
        });
    }

    @Override
    public void close() {
        stop();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        lastHeartbeatTimes.clear();
    }
}
