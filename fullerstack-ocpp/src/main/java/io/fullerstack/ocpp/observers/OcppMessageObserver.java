package io.fullerstack.ocpp.observers;

import io.fullerstack.ocpp.model.*;
import io.fullerstack.ocpp.server.OcppMessage;
import io.fullerstack.ocpp.server.OcppMessageHandler;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.Conduit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Observer that translates OCPP messages into Substrates signals.
 * <p>
 * This is Layer 1 (OBSERVE) of the signal-flow architecture:
 * - Receives OCPP protocol messages from chargers
 * - Translates them into domain-agnostic Substrates signals
 * - Emits signals through conduits for processing by higher layers
 * </p>
 * <p>
 * Signal types emitted:
 * - Monitors: Operational status signals (STABLE, DEGRADED, DOWN, etc.)
 * - Counters: Event counting (transaction started, stopped, etc.)
 * - Gauges: Continuous measurements (power, energy, temperature, etc.)
 * </p>
 */
public class OcppMessageObserver implements OcppMessageHandler, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(OcppMessageObserver.class);

    private final Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private final Conduit<Counters.Counter, Counters.Sign> counters;
    private final Conduit<Gauges.Gauge, Gauges.Sign> gauges;

    // Track charger state for monitor signal transitions
    private final Map<String, ChargerStatus> chargerStates;
    private final Map<String, Long> lastHeartbeatTimes;

    public OcppMessageObserver(
        Conduit<Monitors.Monitor, Monitors.Sign> monitors,
        Conduit<Counters.Counter, Counters.Sign> counters,
        Conduit<Gauges.Gauge, Gauges.Sign> gauges
    ) {
        this.monitors = monitors;
        this.counters = counters;
        this.gauges = gauges;
        this.chargerStates = new ConcurrentHashMap<>();
        this.lastHeartbeatTimes = new ConcurrentHashMap<>();
    }

    @Override
    public void handleMessage(OcppMessage message) {
        logger.debug("Processing OCPP message: {} from charger {}",
            message.getClass().getSimpleName(), message.chargerId());

        switch (message) {
            case OcppMessage.BootNotification boot -> handleBootNotification(boot);
            case OcppMessage.StatusNotification status -> handleStatusNotification(status);
            case OcppMessage.Heartbeat heartbeat -> handleHeartbeat(heartbeat);
            case OcppMessage.MeterValues meter -> handleMeterValues(meter);
            case OcppMessage.StartTransaction start -> handleStartTransaction(start);
            case OcppMessage.StopTransaction stop -> handleStopTransaction(stop);
            case OcppMessage.FirmwareStatusNotification firmware -> handleFirmwareStatus(firmware);
            case OcppMessage.DiagnosticsStatusNotification diagnostics -> handleDiagnosticsStatus(diagnostics);
        }
    }

    /**
     * BootNotification → CHARGER_REGISTERED Monitor signal
     */
    private void handleBootNotification(OcppMessage.BootNotification boot) {
        logger.info("Charger {} registered: {} {} (firmware: {})",
            boot.chargerId(), boot.vendor(), boot.model(), boot.firmwareVersion());

        // Emit STABLE monitor signal - charger successfully registered
        Monitors.Monitor chargerMonitor = monitors.percept(
            cortex().name(boot.chargerId())
        );
        chargerMonitor.stable(Monitors.Dimension.CONFIRMED);

        // Track state
        chargerStates.put(boot.chargerId(), ChargerStatus.AVAILABLE);
        lastHeartbeatTimes.put(boot.chargerId(), System.currentTimeMillis());

        // Increment registration counter
        Counters.Counter registrationCounter = counters.percept(
            cortex().name("charger.registrations")
        );
        registrationCounter.increase();
    }

    /**
     * StatusNotification → Monitor signals based on status
     */
    private void handleStatusNotification(OcppMessage.StatusNotification status) {
        String chargerName = cortex().name(
            status.chargerId() + ".connector." + status.connectorId()
        ).path();

        logger.debug("Charger connector {} status: {} (error: {})",
            chargerName, status.status(), status.errorCode());

        Monitors.Monitor connectorMonitor = monitors.percept(
            cortex().name(chargerName)
        );

        // Map OCPP status to Monitor signals
        ChargerStatus currentStatus = mapOcppStatus(status.status());
        ChargerStatus previousStatus = chargerStates.put(chargerName, currentStatus);

        // Emit appropriate monitor signal based on status
        if ("NoError".equals(status.errorCode())) {
            switch (currentStatus) {
                case AVAILABLE, CHARGING -> {
                    connectorMonitor.stable(Monitors.Dimension.CONFIRMED);
                }
                case PREPARING, FINISHING -> {
                    connectorMonitor.converging(Monitors.Dimension.CONFIRMED);
                }
                case SUSPENDED_EV, SUSPENDED_EVSE -> {
                    connectorMonitor.erratic(Monitors.Dimension.CONFIRMED);
                }
                case UNAVAILABLE -> {
                    connectorMonitor.degraded(Monitors.Dimension.CONFIRMED);
                }
                case FAULTED -> {
                    connectorMonitor.defective(Monitors.Dimension.CONFIRMED);
                }
            }
        } else {
            // Error detected - emit DEFECTIVE or DOWN based on severity
            if (isInoperativeError(status.errorCode())) {
                connectorMonitor.down(Monitors.Dimension.CONFIRMED);
            } else {
                connectorMonitor.defective(Monitors.Dimension.CONFIRMED);
            }
        }
    }

    /**
     * Heartbeat → Update last seen time, emit STABLE if healthy
     */
    private void handleHeartbeat(OcppMessage.Heartbeat heartbeat) {
        logger.trace("Heartbeat from charger {}", heartbeat.chargerId());

        long currentTime = System.currentTimeMillis();
        Long lastHeartbeat = lastHeartbeatTimes.put(heartbeat.chargerId(), currentTime);

        // Check if charger was previously considered offline
        if (lastHeartbeat != null && (currentTime - lastHeartbeat) > 300_000) {  // 5 minutes
            logger.info("Charger {} recovered from offline state", heartbeat.chargerId());

            Monitors.Monitor chargerMonitor = monitors.percept(
                cortex().name(heartbeat.chargerId())
            );
            chargerMonitor.converging(Monitors.Dimension.CONFIRMED);  // Recovering
        }
    }

    /**
     * MeterValues → Emit Gauge signals for power, energy, temperature, SoC
     */
    private void handleMeterValues(OcppMessage.MeterValues meter) {
        String baseName = meter.chargerId() + ".connector." + meter.connectorId();
        logger.trace("Meter values from {}: {}", baseName, meter.values());

        // Emit gauge signals for each measured value
        for (Map.Entry<String, Double> entry : meter.values().entrySet()) {
            String measurementType = entry.getKey();
            Double value = entry.getValue();

            String gaugeName = baseName + "." + normalizeMeasurementName(measurementType);
            Gauges.Gauge gauge = gauges.percept(cortex().name(gaugeName));

            // Emit gauge signal - increment/decrement based on value change
            // For simplicity, we'll emit set() signals
            // In production, you'd track previous values and emit increment/decrement
            gauge.set();  // Value changed
        }
    }

    /**
     * StartTransaction → Increment transaction counter, emit STABLE
     */
    private void handleStartTransaction(OcppMessage.StartTransaction start) {
        logger.info("Transaction started on charger {} connector {} by user {}",
            start.chargerId(), start.connectorId(), start.idTag());

        // Increment transaction start counter
        Counters.Counter startCounter = counters.percept(
            cortex().name("transactions.started")
        );
        startCounter.increase();

        // Update connector status to OCCUPIED/CHARGING
        String connectorName = start.chargerId() + ".connector." + start.connectorId();
        chargerStates.put(connectorName, ChargerStatus.CHARGING);

        Monitors.Monitor connectorMonitor = monitors.percept(
            cortex().name(connectorName)
        );
        connectorMonitor.stable(Monitors.Dimension.CONFIRMED);  // Actively charging
    }

    /**
     * StopTransaction → Increment transaction counter, check reason
     */
    private void handleStopTransaction(OcppMessage.StopTransaction stop) {
        logger.info("Transaction {} stopped on charger {} (reason: {})",
            stop.transactionId(), stop.chargerId(), stop.reason());

        // Increment transaction stop counter
        Counters.Counter stopCounter = counters.percept(
            cortex().name("transactions.stopped")
        );
        stopCounter.increase();

        // Check stop reason - EmergencyStop should emit DEFECTIVE
        if ("EmergencyStop".equals(stop.reason()) || "EVDisconnected".equals(stop.reason())) {
            Monitors.Monitor chargerMonitor = monitors.percept(
                cortex().name(stop.chargerId())
            );
            chargerMonitor.erratic(Monitors.Dimension.CONFIRMED);
        }
    }

    /**
     * FirmwareStatusNotification → Monitor firmware update process
     */
    private void handleFirmwareStatus(OcppMessage.FirmwareStatusNotification firmware) {
        logger.info("Firmware update status for charger {}: {}",
            firmware.chargerId(), firmware.status());

        Monitors.Monitor firmwareMonitor = monitors.percept(
            cortex().name(firmware.chargerId() + ".firmware")
        );

        switch (firmware.status()) {
            case "Downloaded", "Installed" -> {
                firmwareMonitor.stable(Monitors.Dimension.CONFIRMED);
            }
            case "Downloading", "Installing" -> {
                firmwareMonitor.converging(Monitors.Dimension.CONFIRMED);
            }
            case "DownloadFailed", "InstallationFailed" -> {
                firmwareMonitor.defective(Monitors.Dimension.CONFIRMED);
            }
        }
    }

    /**
     * DiagnosticsStatusNotification → Monitor diagnostics upload
     */
    private void handleDiagnosticsStatus(OcppMessage.DiagnosticsStatusNotification diagnostics) {
        logger.info("Diagnostics status for charger {}: {}",
            diagnostics.chargerId(), diagnostics.status());

        Monitors.Monitor diagnosticsMonitor = monitors.percept(
            cortex().name(diagnostics.chargerId() + ".diagnostics")
        );

        switch (diagnostics.status()) {
            case "Uploaded" -> diagnosticsMonitor.stable(Monitors.Dimension.CONFIRMED);
            case "Uploading" -> diagnosticsMonitor.converging(Monitors.Dimension.CONFIRMED);
            case "UploadFailed" -> diagnosticsMonitor.defective(Monitors.Dimension.CONFIRMED);
            case "Idle" -> diagnosticsMonitor.stable(Monitors.Dimension.CONFIRMED);
        }
    }

    /**
     * Map OCPP status strings to domain ChargerStatus enum
     */
    private ChargerStatus mapOcppStatus(String ocppStatus) {
        return switch (ocppStatus) {
            case "Available" -> ChargerStatus.AVAILABLE;
            case "Occupied" -> ChargerStatus.OCCUPIED;
            case "Reserved" -> ChargerStatus.RESERVED;
            case "Unavailable" -> ChargerStatus.UNAVAILABLE;
            case "Faulted" -> ChargerStatus.FAULTED;
            case "Preparing" -> ChargerStatus.PREPARING;
            case "Charging" -> ChargerStatus.CHARGING;
            case "SuspendedEV" -> ChargerStatus.SUSPENDED_EV;
            case "SuspendedEVSE" -> ChargerStatus.SUSPENDED_EVSE;
            case "Finishing" -> ChargerStatus.FINISHING;
            default -> ChargerStatus.UNKNOWN;
        };
    }

    /**
     * Check if error code indicates charger is completely inoperative
     */
    private boolean isInoperativeError(String errorCode) {
        return "ConnectorLockFailure".equals(errorCode) ||
               "GroundFailure".equals(errorCode) ||
               "HighTemperature".equals(errorCode) ||
               "InternalError".equals(errorCode);
    }

    /**
     * Normalize measurement names for gauge naming
     */
    private String normalizeMeasurementName(String measurementType) {
        return measurementType
            .replace(".", "_")
            .replace(" ", "_")
            .toLowerCase();
    }

    @Override
    public void close() {
        logger.info("Closing OCPP Message Observer");
        chargerStates.clear();
        lastHeartbeatTimes.clear();
    }
}
