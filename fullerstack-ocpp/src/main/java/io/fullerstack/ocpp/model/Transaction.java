package io.fullerstack.ocpp.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Domain model representing a charging transaction session.
 */
public record Transaction(
    String transactionId,
    String chargerId,
    int connectorId,
    String idTag,
    Instant startTime,
    Instant stopTime,
    TransactionStatus status,
    double meterStartKwh,
    double meterStopKwh,
    List<MeterValue> meterValues
) {
    /**
     * Creates a new transaction in STARTED status.
     */
    public static Transaction started(
        String transactionId,
        String chargerId,
        int connectorId,
        String idTag,
        double meterStartKwh
    ) {
        return new Transaction(
            transactionId,
            chargerId,
            connectorId,
            idTag,
            Instant.now(),
            null,
            TransactionStatus.STARTED,
            meterStartKwh,
            0.0,
            new ArrayList<>()
        );
    }

    /**
     * Returns a copy of this transaction with updated status.
     */
    public Transaction withStatus(TransactionStatus newStatus) {
        return new Transaction(
            transactionId,
            chargerId,
            connectorId,
            idTag,
            startTime,
            stopTime,
            newStatus,
            meterStartKwh,
            meterStopKwh,
            meterValues
        );
    }

    /**
     * Returns a copy of this transaction with a meter value added.
     */
    public Transaction withMeterValue(MeterValue meterValue) {
        List<MeterValue> updatedValues = new ArrayList<>(meterValues);
        updatedValues.add(meterValue);
        return new Transaction(
            transactionId,
            chargerId,
            connectorId,
            idTag,
            startTime,
            stopTime,
            status,
            meterStartKwh,
            meterStopKwh,
            updatedValues
        );
    }

    /**
     * Returns a copy of this transaction marked as stopped.
     */
    public Transaction stopped(double meterStopKwh) {
        return new Transaction(
            transactionId,
            chargerId,
            connectorId,
            idTag,
            startTime,
            Instant.now(),
            TransactionStatus.STOPPED,
            meterStartKwh,
            meterStopKwh,
            meterValues
        );
    }

    /**
     * Returns the total energy consumed in this transaction (kWh).
     */
    public double energyConsumedKwh() {
        return meterStopKwh - meterStartKwh;
    }
}
