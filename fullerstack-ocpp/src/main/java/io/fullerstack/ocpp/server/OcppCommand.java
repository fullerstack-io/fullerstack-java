package io.fullerstack.ocpp.server;

import java.time.Instant;

/**
 * Represents a command to be sent from the Central System to a charger.
 * These are semantic commands that get translated to OCPP protocol messages.
 */
public sealed interface OcppCommand permits
    OcppCommand.ChangeAvailability,
    OcppCommand.RemoteStartTransaction,
    OcppCommand.RemoteStopTransaction,
    OcppCommand.UnlockConnector,
    OcppCommand.Reset,
    OcppCommand.UpdateFirmware,
    OcppCommand.GetDiagnostics {

    String chargerId();
    String commandId();

    /**
     * Change the operational status of a connector
     */
    record ChangeAvailability(
        String chargerId,
        String commandId,
        int connectorId,
        String type  // "Operative" or "Inoperative"
    ) implements OcppCommand {}

    /**
     * Remotely start a charging transaction
     */
    record RemoteStartTransaction(
        String chargerId,
        String commandId,
        int connectorId,
        String idTag
    ) implements OcppCommand {}

    /**
     * Remotely stop a charging transaction
     */
    record RemoteStopTransaction(
        String chargerId,
        String commandId,
        String transactionId
    ) implements OcppCommand {}

    /**
     * Unlock a connector (emergency release)
     */
    record UnlockConnector(
        String chargerId,
        String commandId,
        int connectorId
    ) implements OcppCommand {}

    /**
     * Reset the charger (soft or hard reset)
     */
    record Reset(
        String chargerId,
        String commandId,
        String type  // "Soft" or "Hard"
    ) implements OcppCommand {}

    /**
     * Trigger a firmware update
     */
    record UpdateFirmware(
        String chargerId,
        String commandId,
        String firmwareUrl,
        Instant retrieveDate
    ) implements OcppCommand {}

    /**
     * Request diagnostics from charger
     */
    record GetDiagnostics(
        String chargerId,
        String commandId,
        String uploadUrl
    ) implements OcppCommand {}
}
