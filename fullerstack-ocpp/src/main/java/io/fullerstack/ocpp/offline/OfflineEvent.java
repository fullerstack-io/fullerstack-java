package io.fullerstack.ocpp.offline;

import java.time.Instant;
import java.util.Map;

/**
 * Events logged during offline operation for later synchronization with cloud.
 * <p>
 * Design: Event Sourcing pattern - all state changes are immutable events
 * that can be replayed/synchronized.
 * </p>
 */
public sealed interface OfflineEvent permits
    OfflineEvent.ChargerRegistered,
    OfflineEvent.StatusChanged,
    OfflineEvent.TransactionStarted,
    OfflineEvent.TransactionStopped,
    OfflineEvent.MeterValueRecorded,
    OfflineEvent.FirmwareUpdateCompleted {

    Instant timestamp();
    String eventType();

    record ChargerRegistered(
        Instant timestamp,
        String chargerId,
        String model,
        String vendor,
        String firmwareVersion
    ) implements OfflineEvent {
        @Override
        public String eventType() {
            return "charger.registered";
        }
    }

    record StatusChanged(
        Instant timestamp,
        String chargerId,
        String newStatus
    ) implements OfflineEvent {
        @Override
        public String eventType() {
            return "charger.status.changed";
        }
    }

    record TransactionStarted(
        Instant timestamp,
        String transactionId,
        String chargerId,
        int connectorId,
        String idTag,
        double meterStartKwh
    ) implements OfflineEvent {
        @Override
        public String eventType() {
            return "transaction.started";
        }
    }

    record TransactionStopped(
        Instant timestamp,
        String transactionId,
        double meterStopKwh,
        String reason
    ) implements OfflineEvent {
        @Override
        public String eventType() {
            return "transaction.stopped";
        }
    }

    record MeterValueRecorded(
        Instant timestamp,
        String chargerId,
        int connectorId,
        Map<String, Double> values
    ) implements OfflineEvent {
        @Override
        public String eventType() {
            return "meter.value.recorded";
        }
    }

    record FirmwareUpdateCompleted(
        Instant timestamp,
        String chargerId,
        String newFirmwareVersion,
        boolean success
    ) implements OfflineEvent {
        @Override
        public String eventType() {
            return "firmware.update.completed";
        }
    }
}
