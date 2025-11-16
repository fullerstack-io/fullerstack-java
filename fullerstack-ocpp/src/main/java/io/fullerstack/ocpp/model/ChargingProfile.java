package io.fullerstack.ocpp.model;

import java.time.Instant;
import java.util.List;

/**
 * OCPP 1.6 Smart Charging Profile.
 * <p>
 * Defines power/current limits and schedules for a charger or connector.
 * Enables fine-grained control beyond simple on/off (ChangeAvailability).
 * <p>
 * Use cases:
 * - Load balancing: Limit total power across chargers
 * - Time-of-use pricing: Schedule lower power during peak hours
 * - Grid constraints: Reduce charging when grid stressed
 * - Renewable energy: Increase charging when solar/wind available
 */
public record ChargingProfile(
    int chargingProfileId,
    int stackLevel,  // Priority: higher stack level = higher priority
    ChargingProfilePurpose chargingProfilePurpose,
    ChargingProfileKind chargingProfileKind,
    RecurrencyKind recurrencyKind,  // Optional: daily, weekly
    Instant validFrom,  // Optional: start time
    Instant validTo,    // Optional: end time
    ChargingSchedule chargingSchedule
) {

    /**
     * Purpose of the charging profile.
     */
    public enum ChargingProfilePurpose {
        /** Configuration for the maximum power or current available for an entire charge point */
        ChargePointMaxProfile,

        /** Limits for an ongoing transaction */
        TxDefaultProfile,

        /** Profile with constraints for a specific transaction */
        TxProfile
    }

    /**
     * Indicates how the profile should be applied.
     */
    public enum ChargingProfileKind {
        /** Schedule periods are relative to a fixed point in time (validFrom) */
        Absolute,

        /** Schedule periods are relative to the start of charging */
        Recurring,

        /** Schedule relative to start time */
        Relative
    }

    /**
     * Recurrency pattern for recurring profiles.
     */
    public enum Recurrency Kind {
        Daily,
        Weekly
    }

    /**
     * Charging schedule with one or more periods.
     */
    public record ChargingSchedule(
        ChargingRateUnit chargingRateUnit,
        List<ChargingSchedulePeriod> chargingSchedulePeriod,
        Integer duration,  // Optional: total duration in seconds
        Instant startSchedule,  // Optional: start time for Recurring/Relative profiles
        Double minChargingRate  // Optional: minimum charging rate (safety limit)
    ) {

        /**
         * Unit of the charging rate in schedule periods.
         */
        public enum ChargingRateUnit {
            /** Watts (power) */
            W,

            /** Amperes (current) */
            A
        }

        /**
         * Single period in a charging schedule.
         */
        public record ChargingSchedulePeriod(
            int startPeriod,  // Offset in seconds from schedule start
            double limit,     // Power limit (W) or current limit (A)
            Integer numberPhases  // Optional: 1 or 3 phases
        ) {}
    }
}
