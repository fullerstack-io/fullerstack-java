package io.fullerstack.ocpp.model;

/**
 * Represents the operational status of an OCPP charger.
 * Maps to OCPP 2.0 ChargePointStatus and OperationalStatus values.
 */
public enum ChargerStatus {
    /**
     * Charger is available and can accept charging sessions
     */
    AVAILABLE,

    /**
     * Charger is occupied with an active charging session
     */
    OCCUPIED,

    /**
     * Charger is reserved for a specific user/vehicle
     */
    RESERVED,

    /**
     * Charger is unavailable (maintenance, offline, or disabled)
     */
    UNAVAILABLE,

    /**
     * Charger is experiencing a fault
     */
    FAULTED,

    /**
     * Charger is preparing for a charging session
     */
    PREPARING,

    /**
     * Charger is actively charging
     */
    CHARGING,

    /**
     * Charging has completed
     */
    FINISHING,

    /**
     * Charger is suspended (by EV or EVSE)
     */
    SUSPENDED_EV,
    SUSPENDED_EVSE,

    /**
     * Unknown or uninitialized status
     */
    UNKNOWN
}
