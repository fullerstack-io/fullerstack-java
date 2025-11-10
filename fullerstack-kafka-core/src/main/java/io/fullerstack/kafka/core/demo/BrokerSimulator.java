package io.fullerstack.kafka.core.demo;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simulates a Kafka broker with CPU, memory usage, and health states.
 */
public class BrokerSimulator {

    private final String brokerId;
    private final ScheduledExecutorService scheduler;
    private final Random random = new Random();

    private final AtomicReference<Double> cpuUsage = new AtomicReference<>(0.3);  // 30% baseline
    private final AtomicReference<Double> memoryUsage = new AtomicReference<>(0.4);  // 40% baseline
    private final AtomicReference<BrokerHealth> health = new AtomicReference<>(BrokerHealth.HEALTHY);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ScheduledFuture<?> metricsTask;

    public BrokerSimulator(String brokerId, ScheduledExecutorService scheduler) {
        this.brokerId = brokerId;
        this.scheduler = scheduler;
    }

    /**
     * Starts simulating broker metrics.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            metricsTask = scheduler.scheduleAtFixedRate(
                this::updateMetrics,
                0,
                500,  // Update every 500ms
                TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Stops the broker simulation.
     */
    public void stop() {
        running.set(false);
        if (metricsTask != null) {
            metricsTask.cancel(false);
        }
    }

    /**
     * Simulates load increase (e.g., from high producer throughput).
     */
    public void increaseLoad() {
        cpuUsage.updateAndGet(current -> Math.min(0.95, current + 0.15));
        memoryUsage.updateAndGet(current -> Math.min(0.90, current + 0.10));
        updateHealth();
    }

    /**
     * Simulates load decrease (e.g., from throttling).
     */
    public void decreaseLoad() {
        cpuUsage.updateAndGet(current -> Math.max(0.20, current - 0.20));
        memoryUsage.updateAndGet(current -> Math.max(0.30, current - 0.15));
        updateHealth();
    }

    private void updateMetrics() {
        // Add small random fluctuation to make it realistic
        cpuUsage.updateAndGet(current -> {
            double delta = (random.nextDouble() - 0.5) * 0.02;  // ±1%
            return Math.max(0.1, Math.min(1.0, current + delta));
        });

        memoryUsage.updateAndGet(current -> {
            double delta = (random.nextDouble() - 0.5) * 0.01;  // ±0.5%
            return Math.max(0.2, Math.min(1.0, current + delta));
        });

        updateHealth();
    }

    private void updateHealth() {
        double cpu = cpuUsage.get();
        double mem = memoryUsage.get();

        BrokerHealth oldHealth = health.get();
        BrokerHealth newHealth;

        if (cpu > 0.90 || mem > 0.85) {
            newHealth = BrokerHealth.CRITICAL;
        } else if (cpu > 0.75 || mem > 0.70) {
            newHealth = BrokerHealth.DEGRADED;
        } else if (cpu > 0.60 || mem > 0.60) {
            newHealth = BrokerHealth.WARNING;
        } else {
            newHealth = BrokerHealth.HEALTHY;
        }

        if (newHealth != oldHealth) {
            health.set(newHealth);
            System.out.printf("🏥 BROKER HEALTH: %s %s → %s (CPU=%.1f%%, Mem=%.1f%%)%n",
                brokerId, oldHealth, newHealth, cpu * 100, mem * 100);
        }
    }

    public String getBrokerId() {
        return brokerId;
    }

    public double getCpuUsage() {
        return cpuUsage.get();
    }

    public double getMemoryUsage() {
        return memoryUsage.get();
    }

    public BrokerHealth getHealth() {
        return health.get();
    }

    public enum BrokerHealth {
        HEALTHY,
        WARNING,
        DEGRADED,
        CRITICAL
    }
}
