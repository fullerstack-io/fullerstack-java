package io.fullerstack.ocpp.offline;

import io.fullerstack.ocpp.model.Charger;
import io.fullerstack.ocpp.model.Transaction;
import io.fullerstack.ocpp.server.OcppMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages local state when operating in offline mode (no cloud connectivity).
 * <p>
 * Key responsibilities:
 * - Cache charger registrations, status, and transactions locally
 * - Log all OCPP events for later synchronization
 * - Provide local state queries for API and coordination logic
 * - Support state reconciliation when connectivity resumes
 * </p>
 * <p>
 * Design pattern: Event Sourcing - all state changes are logged as events
 * that can be replayed during synchronization.
 * </p>
 */
public class OfflineStateManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(OfflineStateManager.class);
    private static final int MAX_EVENT_LOG_SIZE = 10_000;

    // Local state cache
    private final Map<String, Charger> chargers;
    private final Map<String, Transaction> activeTransactions;
    private final Map<String, List<Transaction>> transactionHistory;

    // Event log for synchronization
    private final List<OfflineEvent> eventLog;
    private final Object eventLogLock = new Object();

    // Connectivity state
    private volatile boolean cloudConnected;
    private Instant lastSyncTime;

    public OfflineStateManager() {
        this.chargers = new ConcurrentHashMap<>();
        this.activeTransactions = new ConcurrentHashMap<>();
        this.transactionHistory = new ConcurrentHashMap<>();
        this.eventLog = new CopyOnWriteArrayList<>();
        this.cloudConnected = true;  // Assume connected initially
        this.lastSyncTime = Instant.now();

        logger.info("OfflineStateManager initialized");
    }

    /**
     * Record a charger registration event.
     */
    public void recordChargerRegistration(Charger charger) {
        chargers.put(charger.chargerId(), charger);
        logEvent(new OfflineEvent.ChargerRegistered(
            Instant.now(),
            charger.chargerId(),
            charger.model(),
            charger.vendor(),
            charger.firmwareVersion()
        ));
        logger.info("Recorded charger registration: {}", charger.chargerId());
    }

    /**
     * Record a charger status change.
     */
    public void recordStatusChange(String chargerId, String status) {
        Charger charger = chargers.get(chargerId);
        if (charger != null) {
            // Update cached charger (simplified - in production, use proper status mapping)
            chargers.put(chargerId, charger.withHeartbeat(Instant.now()));
        }

        logEvent(new OfflineEvent.StatusChanged(
            Instant.now(),
            chargerId,
            status
        ));
        logger.debug("Recorded status change for {}: {}", chargerId, status);
    }

    /**
     * Record a transaction start event.
     */
    public void recordTransactionStart(Transaction transaction) {
        activeTransactions.put(transaction.transactionId(), transaction);

        logEvent(new OfflineEvent.TransactionStarted(
            Instant.now(),
            transaction.transactionId(),
            transaction.chargerId(),
            transaction.connectorId(),
            transaction.idTag(),
            transaction.meterStartKwh()
        ));
        logger.info("Recorded transaction start: {}", transaction.transactionId());
    }

    /**
     * Record a transaction stop event.
     */
    public void recordTransactionStop(String transactionId, double meterStop, String reason) {
        Transaction transaction = activeTransactions.remove(transactionId);
        if (transaction != null) {
            Transaction stoppedTransaction = transaction.stopped(meterStop);

            // Add to history
            transactionHistory
                .computeIfAbsent(transaction.chargerId(), k -> new CopyOnWriteArrayList<>())
                .add(stoppedTransaction);
        }

        logEvent(new OfflineEvent.TransactionStopped(
            Instant.now(),
            transactionId,
            meterStop,
            reason
        ));
        logger.info("Recorded transaction stop: {} (reason: {})", transactionId, reason);
    }

    /**
     * Record a meter value reading.
     */
    public void recordMeterValue(String chargerId, int connectorId, Map<String, Double> values) {
        logEvent(new OfflineEvent.MeterValueRecorded(
            Instant.now(),
            chargerId,
            connectorId,
            new HashMap<>(values)
        ));
        logger.trace("Recorded meter values for {}.connector.{}", chargerId, connectorId);
    }

    /**
     * Get all registered chargers.
     */
    public Collection<Charger> getAllChargers() {
        return Collections.unmodifiableCollection(chargers.values());
    }

    /**
     * Get a specific charger by ID.
     */
    public Optional<Charger> getCharger(String chargerId) {
        return Optional.ofNullable(chargers.get(chargerId));
    }

    /**
     * Get all active transactions.
     */
    public Collection<Transaction> getActiveTransactions() {
        return Collections.unmodifiableCollection(activeTransactions.values());
    }

    /**
     * Get transaction history for a charger.
     */
    public List<Transaction> getTransactionHistory(String chargerId) {
        return Collections.unmodifiableList(
            transactionHistory.getOrDefault(chargerId, Collections.emptyList())
        );
    }

    /**
     * Get unsynchronized events (since last sync).
     */
    public List<OfflineEvent> getUnsynchronizedEvents() {
        synchronized (eventLogLock) {
            // In production, filter by lastSyncTime
            return new ArrayList<>(eventLog);
        }
    }

    /**
     * Mark events as synchronized with cloud.
     */
    public void markEventsSynchronized(Instant syncTime) {
        synchronized (eventLogLock) {
            // Remove synchronized events (keep only newer ones)
            eventLog.removeIf(event -> event.timestamp().isBefore(syncTime));
            lastSyncTime = syncTime;

            logger.info("Marked events synchronized up to {}, remaining: {}",
                syncTime, eventLog.size());
        }
    }

    /**
     * Set cloud connectivity status.
     */
    public void setCloudConnected(boolean connected) {
        boolean wasConnected = this.cloudConnected;
        this.cloudConnected = connected;

        if (!wasConnected && connected) {
            logger.info("Cloud connectivity restored - {} unsynchronized events pending",
                eventLog.size());
        } else if (wasConnected && !connected) {
            logger.warn("Cloud connectivity lost - operating in offline mode");
        }
    }

    /**
     * Check if cloud is currently connected.
     */
    public boolean isCloudConnected() {
        return cloudConnected;
    }

    /**
     * Get the time of last successful synchronization.
     */
    public Instant getLastSyncTime() {
        return lastSyncTime;
    }

    /**
     * Log an event to the offline event log.
     */
    private void logEvent(OfflineEvent event) {
        synchronized (eventLogLock) {
            eventLog.add(event);

            // Prevent unbounded growth
            if (eventLog.size() > MAX_EVENT_LOG_SIZE) {
                logger.warn("Event log exceeds max size ({}), removing oldest events",
                    MAX_EVENT_LOG_SIZE);
                // Remove oldest 10%
                int toRemove = MAX_EVENT_LOG_SIZE / 10;
                eventLog.subList(0, toRemove).clear();
            }
        }
    }

    @Override
    public void close() {
        logger.info("Closing OfflineStateManager (unsynchronized events: {})", eventLog.size());
        chargers.clear();
        activeTransactions.clear();
        transactionHistory.clear();
        eventLog.clear();
    }
}
