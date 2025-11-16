package io.fullerstack.ocpp.model;

/**
 * Represents the lifecycle status of a charging transaction.
 */
public enum TransactionStatus {
    /**
     * Transaction has been initiated
     */
    STARTED,

    /**
     * Transaction is actively in progress
     */
    IN_PROGRESS,

    /**
     * Transaction has been stopped
     */
    STOPPED,

    /**
     * Transaction failed or was aborted
     */
    FAILED,

    /**
     * Transaction is suspended (temporarily paused)
     */
    SUSPENDED
}
