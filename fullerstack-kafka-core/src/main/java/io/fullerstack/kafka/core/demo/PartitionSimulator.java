package io.fullerstack.kafka.core.demo;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates a Kafka partition with queue depth and overflow detection.
 */
public class PartitionSimulator {

    private final String partitionId;
    private final int maxQueueSize;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger queueDepth = new AtomicInteger(0);
    private final AtomicBoolean overflowing = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledFuture<?> consumerTask;

    public PartitionSimulator(String partitionId, int maxQueueSize, ScheduledExecutorService scheduler) {
        this.partitionId = partitionId;
        this.maxQueueSize = maxQueueSize;
        this.scheduler = scheduler;
    }

    /**
     * Starts consuming messages from the queue.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            // Simulate consumer processing at 80% of max capacity
            consumerTask = scheduler.scheduleAtFixedRate(
                this::consumeMessage,
                0,
                15,  // Consume every 15ms (slower than production to allow buildup)
                TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Stops consuming messages.
     */
    public void stop() {
        running.set(false);
        if (consumerTask != null) {
            consumerTask.cancel(false);
        }
    }

    /**
     * Enqueues a message (called by producer).
     */
    public void enqueue() {
        int newDepth = queueDepth.incrementAndGet();
        checkOverflow(newDepth);
    }

    private void consumeMessage() {
        if (running.get() && queueDepth.get() > 0) {
            int newDepth = queueDepth.decrementAndGet();
            checkOverflow(newDepth);
        }
    }

    private void checkOverflow(int depth) {
        double utilization = (double) depth / maxQueueSize;
        if (utilization > 0.95 && !overflowing.get()) {
            overflowing.set(true);
            System.out.printf("⚠️  OVERFLOW: %s queue at %.1f%% (%d/%d)%n",
                partitionId, utilization * 100, depth, maxQueueSize);
        } else if (utilization < 0.70 && overflowing.get()) {
            overflowing.set(false);
            System.out.printf("✅ RECOVERED: %s queue at %.1f%% (%d/%d)%n",
                partitionId, utilization * 100, depth, maxQueueSize);
        }
    }

    public String getPartitionId() {
        return partitionId;
    }

    public int getQueueDepth() {
        return queueDepth.get();
    }

    public int getMaxQueueSize() {
        return maxQueueSize;
    }

    public double getQueueUtilization() {
        return (double) queueDepth.get() / maxQueueSize;
    }

    public boolean isOverflowing() {
        return overflowing.get();
    }
}
