package io.fullerstack.kafka.demo;

/**
 * Demo modes - determines which observers to initialize
 */
public enum DemoMode {
    /**
     * Full demo: all observers (producer + consumer + broker)
     */
    FULL,

    /**
     * Producer-only demo: buffer overflow, throttling
     */
    PRODUCER,

    /**
     * Consumer-only demo: lag detection, rebalancing
     */
    CONSUMER,

    /**
     * Broker-only demo: cluster health, failures
     */
    BROKER
}
