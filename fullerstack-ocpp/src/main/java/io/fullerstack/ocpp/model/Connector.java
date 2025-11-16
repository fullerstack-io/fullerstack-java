package io.fullerstack.ocpp.model;

/**
 * Represents a physical connector on an EV charger.
 */
public record Connector(
    int connectorId,
    ConnectorType type,
    double maxPowerKw,
    ChargerStatus status
) {
    /**
     * Creates a connector in AVAILABLE status.
     */
    public static Connector available(int connectorId, ConnectorType type, double maxPowerKw) {
        return new Connector(connectorId, type, maxPowerKw, ChargerStatus.AVAILABLE);
    }

    /**
     * Returns a copy of this connector with updated status.
     */
    public Connector withStatus(ChargerStatus newStatus) {
        return new Connector(connectorId, type, maxPowerKw, newStatus);
    }
}
