package io.fullerstack.ocpp.model;

/**
 * Physical connector types for EV charging.
 */
public enum ConnectorType {
    /**
     * Type 1 connector (SAE J1772, common in North America)
     */
    TYPE_1,

    /**
     * Type 2 connector (IEC 62196-2, common in Europe)
     */
    TYPE_2,

    /**
     * CCS (Combined Charging System) Type 1
     */
    CCS_TYPE_1,

    /**
     * CCS (Combined Charging System) Type 2
     */
    CCS_TYPE_2,

    /**
     * CHAdeMO fast charging connector
     */
    CHADEMO,

    /**
     * Tesla proprietary connector
     */
    TESLA,

    /**
     * GB/T connector (Chinese standard)
     */
    GB_T,

    /**
     * Generic or unknown connector type
     */
    UNKNOWN
}
