package io.fullerstack.ocpp.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain model representing an OCPP-compliant EV charger (Charge Point).
 * <p>
 * This model is independent of OCPP protocol messages and represents
 * the semantic state of a charger in the system.
 * </p>
 */
public record Charger(
    String chargerId,
    String model,
    String vendor,
    String serialNumber,
    String firmwareVersion,
    List<Connector> connectors,
    ChargerStatus status,
    Instant lastHeartbeat,
    Instant bootTime,
    boolean isOnline,
    Optional<String> currentTransactionId
) {
    /**
     * Creates a new Charger with default values for a newly registered charger.
     */
    public static Charger registered(
        String chargerId,
        String model,
        String vendor,
        String serialNumber,
        String firmwareVersion,
        List<Connector> connectors
    ) {
        return new Charger(
            chargerId,
            model,
            vendor,
            serialNumber,
            firmwareVersion,
            connectors,
            ChargerStatus.AVAILABLE,
            Instant.now(),
            Instant.now(),
            true,
            Optional.empty()
        );
    }

    /**
     * Returns a copy of this charger with updated status.
     */
    public Charger withStatus(ChargerStatus newStatus) {
        return new Charger(
            chargerId,
            model,
            vendor,
            serialNumber,
            firmwareVersion,
            connectors,
            newStatus,
            lastHeartbeat,
            bootTime,
            isOnline,
            currentTransactionId
        );
    }

    /**
     * Returns a copy of this charger with updated heartbeat time.
     */
    public Charger withHeartbeat(Instant timestamp) {
        return new Charger(
            chargerId,
            model,
            vendor,
            serialNumber,
            firmwareVersion,
            connectors,
            status,
            timestamp,
            bootTime,
            true,  // Receiving heartbeat means online
            currentTransactionId
        );
    }

    /**
     * Returns a copy of this charger with updated transaction ID.
     */
    public Charger withTransaction(String transactionId) {
        return new Charger(
            chargerId,
            model,
            vendor,
            serialNumber,
            firmwareVersion,
            connectors,
            status,
            lastHeartbeat,
            bootTime,
            isOnline,
            Optional.ofNullable(transactionId)
        );
    }

    /**
     * Returns a copy of this charger marked as offline.
     */
    public Charger markOffline() {
        return new Charger(
            chargerId,
            model,
            vendor,
            serialNumber,
            firmwareVersion,
            connectors,
            ChargerStatus.UNAVAILABLE,
            lastHeartbeat,
            bootTime,
            false,
            currentTransactionId
        );
    }
}
