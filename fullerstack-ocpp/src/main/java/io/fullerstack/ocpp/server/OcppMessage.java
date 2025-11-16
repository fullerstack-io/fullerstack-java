package io.fullerstack.ocpp.server;

import java.time.Instant;
import java.util.Map;

/**
 * Represents an OCPP message received from or sent to a charger.
 * Simplified representation for demonstration of Substrates integration.
 */
public sealed interface OcppMessage permits
    OcppMessage.BootNotification,
    OcppMessage.StatusNotification,
    OcppMessage.Heartbeat,
    OcppMessage.MeterValues,
    OcppMessage.StartTransaction,
    OcppMessage.StopTransaction,
    OcppMessage.FirmwareStatusNotification,
    OcppMessage.DiagnosticsStatusNotification {

    String chargerId();
    Instant timestamp();
    String messageId();

    /**
     * BootNotification - charger is booting up and registering with central system
     */
    record BootNotification(
        String chargerId,
        Instant timestamp,
        String messageId,
        String model,
        String vendor,
        String serialNumber,
        String firmwareVersion
    ) implements OcppMessage {}

    /**
     * StatusNotification - charger/connector status has changed
     */
    record StatusNotification(
        String chargerId,
        Instant timestamp,
        String messageId,
        int connectorId,
        String status,  // Available, Occupied, Faulted, etc.
        String errorCode
    ) implements OcppMessage {}

    /**
     * Heartbeat - periodic ping from charger to indicate it's alive
     */
    record Heartbeat(
        String chargerId,
        Instant timestamp,
        String messageId
    ) implements OcppMessage {}

    /**
     * MeterValues - energy consumption and power metrics
     */
    record MeterValues(
        String chargerId,
        Instant timestamp,
        String messageId,
        int connectorId,
        String transactionId,
        Map<String, Double> values  // e.g., "Energy.Active.Import.Register" -> 12.5
    ) implements OcppMessage {}

    /**
     * StartTransaction - charging session started
     */
    record StartTransaction(
        String chargerId,
        Instant timestamp,
        String messageId,
        int connectorId,
        String idTag,
        double meterStart
    ) implements OcppMessage {}

    /**
     * StopTransaction - charging session stopped
     */
    record StopTransaction(
        String chargerId,
        Instant timestamp,
        String messageId,
        String transactionId,
        String idTag,
        double meterStop,
        String reason  // Local, Remote, EmergencyStop, etc.
    ) implements OcppMessage {}

    /**
     * FirmwareStatusNotification - firmware update status
     */
    record FirmwareStatusNotification(
        String chargerId,
        Instant timestamp,
        String messageId,
        String status  // Downloaded, DownloadFailed, Installing, Installed, InstallationFailed
    ) implements OcppMessage {}

    /**
     * DiagnosticsStatusNotification - diagnostics upload status
     */
    record DiagnosticsStatusNotification(
        String chargerId,
        Instant timestamp,
        String messageId,
        String status  // Idle, Uploaded, UploadFailed, Uploading
    ) implements OcppMessage {}
}
