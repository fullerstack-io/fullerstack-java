package io.fullerstack.kafka.core.demo;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulates a Kafka producer with configurable throughput and throttling.
 *
 * <p>Supports:
 * <ul>
 *   <li>Dynamic rate adjustment (throttling)</li>
 *   <li>Message production tracking</li>
 *   <li>Start/stop control</li>
 * </ul>
 */
public class ProducerSimulator {

    private final String producerId;
    private final String targetPartition;
    private final int baseMessagesPerSecond;
    private final ScheduledExecutorService scheduler;
    private final KafkaClusterSimulator.MessageCallback callback;

    private final AtomicInteger currentRate;
    private final AtomicLong totalProduced = new AtomicLong(0);
    private final AtomicBoolean throttled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledFuture<?> producerTask;

    public ProducerSimulator(
        String producerId,
        String targetPartition,
        int messagesPerSecond,
        ScheduledExecutorService scheduler,
        KafkaClusterSimulator.MessageCallback callback
    ) {
        this.producerId = producerId;
        this.targetPartition = targetPartition;
        this.baseMessagesPerSecond = messagesPerSecond;
        this.currentRate = new AtomicInteger(messagesPerSecond);
        this.scheduler = scheduler;
        this.callback = callback;
    }

    /**
     * Starts producing messages.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            // Schedule message production
            long intervalMs = 1000 / currentRate.get();
            producerTask = scheduler.scheduleAtFixedRate(
                this::produceMessage,
                0,
                intervalMs,
                TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Stops producing messages.
     */
    public void stop() {
        running.set(false);
        if (producerTask != null) {
            producerTask.cancel(false);
        }
    }

    /**
     * Applies throttling - reduces rate by 50%.
     */
    public void throttle() {
        if (throttled.compareAndSet(false, true)) {
            int newRate = baseMessagesPerSecond / 2;
            currentRate.set(newRate);
            System.out.printf("🔽 THROTTLE: %s reduced to %d msg/s (50%%)%n", producerId, newRate);

            // Restart with new rate
            stop();
            start();
        }
    }

    /**
     * Removes throttling - restores base rate.
     */
    public void resume() {
        if (throttled.compareAndSet(true, false)) {
            currentRate.set(baseMessagesPerSecond);
            System.out.printf("🔼 RESUME: %s restored to %d msg/s (100%%)%n", producerId, baseMessagesPerSecond);

            // Restart with base rate
            stop();
            start();
        }
    }

    /**
     * Opens circuit breaker - stops all production.
     */
    public void openCircuit() {
        currentRate.set(0);
        stop();
        System.out.printf("⛔ CIRCUIT OPEN: %s stopped producing%n", producerId);
    }

    /**
     * Closes circuit breaker - resumes production.
     */
    public void closeCircuit() {
        currentRate.set(throttled.get() ? baseMessagesPerSecond / 2 : baseMessagesPerSecond);
        start();
        System.out.printf("✅ CIRCUIT CLOSED: %s resumed at %d msg/s%n", producerId, currentRate.get());
    }

    private void produceMessage() {
        if (running.get() && currentRate.get() > 0) {
            totalProduced.incrementAndGet();
            callback.onMessage(producerId, targetPartition);
        }
    }

    public String getProducerId() {
        return producerId;
    }

    public int getCurrentRate() {
        return currentRate.get();
    }

    public long getTotalProduced() {
        return totalProduced.get();
    }

    public boolean isThrottled() {
        return throttled.get();
    }

    public boolean isCircuitOpen() {
        return currentRate.get() == 0 && !running.get();
    }
}
