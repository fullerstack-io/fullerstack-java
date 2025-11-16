package io.fullerstack.ocpp.server;

import io.fullerstack.ocpp.model.ChargingProfile;
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
    OcppCommand.GetDiagnostics,
    OcppCommand.SetChargingProfile,
    OcppCommand.ClearChargingProfile,
    OcppCommand.GetCompositeSchedule {

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

    /**
     * Set a charging profile for fine-grained power/current control.
     * <p>
     * Enables load balancing, time-of-use optimization, grid constraint management.
     * <p>
     * Example use cases:
     * - Limit charger to 16A instead of 32A during peak demand
     * - Schedule lower power (3kW) during expensive hours, higher (11kW) during off-peak
     * - Dynamically adjust based on total grid consumption
     */
    record SetChargingProfile(
        String chargerId,
        String commandId,
        int connectorId,  // 0 = entire charger, >0 = specific connector
        ChargingProfile chargingProfile
    ) implements OcppCommand {}

    /**
     * Clear a previously set charging profile.
     * <p>
     * Used to remove temporary limits and return to default charging behavior.
     */
    record ClearChargingProfile(
        String chargerId,
        String commandId,
        Integer id,  // Optional: specific profile ID, null = all profiles
        Integer connectorId,  // Optional: specific connector, null = all connectors
        ChargingProfile.ChargingProfilePurpose chargingProfilePurpose,  // Optional: filter by purpose
        Integer stackLevel  // Optional: filter by stack level
    ) implements OcppCommand {}

    /**
     * Get the composite charging schedule as calculated by the charger.
     * <p>
     * Returns the effective schedule after combining all active profiles.
     * Useful for verifying load balancing calculations.
     */
    record GetCompositeSchedule(
        String chargerId,
        String commandId,
        int connectorId,
        int duration,  // Duration in seconds
        ChargingProfile.ChargingSchedule.ChargingRateUnit chargingRateUnit  // W or A
    ) implements OcppCommand {}
}
