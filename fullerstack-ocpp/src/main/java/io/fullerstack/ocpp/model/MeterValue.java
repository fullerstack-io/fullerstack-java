package io.fullerstack.ocpp.model;

import java.time.Instant;

/**
 * Represents a meter reading during a charging transaction.
 */
public record MeterValue(
    Instant timestamp,
    double energyActiveImportKwh,
    double powerActiveImportKw,
    double currentImportA,
    double voltageV,
    double temperatureC,
    double socPercent
) {
    /**
     * Creates a basic meter value with only energy reading.
     */
    public static MeterValue energy(double energyKwh) {
        return new MeterValue(
            Instant.now(),
            energyKwh,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0
        );
    }

    /**
     * Creates a comprehensive meter value with all readings.
     */
    public static MeterValue comprehensive(
        double energyKwh,
        double powerKw,
        double currentA,
        double voltageV,
        double temperatureC,
        double socPercent
    ) {
        return new MeterValue(
            Instant.now(),
            energyKwh,
            powerKw,
            currentA,
            voltageV,
            temperatureC,
            socPercent
        );
    }
}
