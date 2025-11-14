package io.fullerstack.kafka.consumer.feedback;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 3 (ACT): Closed-loop feedback - Consumer self-regulates based on its own lag health.
 *
 * <p><b>Signal-Flow Pattern:</b>
 * <pre>
 * Layer 1: ConsumerLagObserver → Gauges (lag metrics)
 *                                    ↓
 * Layer 2: ConsumerLagMonitor → Monitors (lag condition)
 *                                    ↓
 * Layer 3: ConsumerSelfRegulator → SELF-REGULATION (pause/resume)
 * </pre>
 *
 * <p><b>Self-Regulation Logic:</b>
 * <ul>
 *   <li><b>DEGRADED + CONFIRMED</b> → Pause consumer (lag critically high)</li>
 *   <li><b>CONVERGING + CONFIRMED</b> → Resume consumer (lag recovering)</li>
 *   <li><b>STABLE + CONFIRMED</b> → Resume consumer (lag healthy)</li>
 *   <li><b>Auto-resume after cooldown</b> → Prevent infinite pause</li>
 * </ul>
 *
 * <p><b>Key Insight:</b>
 * This is NOT monitoring external Kafka brokers - this is the consumer monitoring
 * and regulating ITSELF based on its own internal lag state.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("consumer.health"));
 *
 * // Layer 1: Lag receptor
 * ConsumerLagObserver receptor = new ConsumerLagObserver(circuit, consumer);
 *
 * // Layer 2: Lag monitor (pattern detection)
 * ConsumerLagMonitor monitor = new ConsumerLagMonitor(
 *     circuit,
 *     receptor.gauges()
 * );
 *
 * // Layer 3: Self-regulator (closed-loop feedback)
 * ConsumerSelfRegulator regulator = new ConsumerSelfRegulator(
 *     consumer,
 *     monitor.monitors()
 * );
 *
 * // Consumer now automatically pauses when lag DEGRADED
 * // Consumer automatically resumes when lag CONVERGING/STABLE
 * }</pre>
 *
 * <p><b>Benefits:</b>
 * <ul>
 *   <li>Prevents lag explosion and OOM from accumulating records</li>
 *   <li>Automatic backpressure without manual intervention</li>
 *   <li>Self-healing: resumes automatically when lag recovers</li>
 *   <li>Composable: can stack multiple regulators</li>
 * </ul>
 */
public class ConsumerSelfRegulator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerSelfRegulator.class);

    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);
    private static final Duration MAX_PAUSE_DURATION = Duration.ofMinutes(10);

    private final KafkaConsumer<?, ?> consumer;
    private final Subscription monitorSubscription;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;
    private final Duration cooldownPeriod;

    private volatile long pauseStartTime = 0;
    private volatile Set<TopicPartition> pausedPartitions = Set.of();

    /**
     * Creates a ConsumerSelfRegulator with default cooldown (30 seconds).
     *
     * @param consumer KafkaConsumer to regulate
     * @param monitors Monitors conduit from Layer 2 (ConsumerLagMonitor)
     */
    public ConsumerSelfRegulator(
        KafkaConsumer<?, ?> consumer,
        Conduit<Monitors.Monitor, Monitors.Signal> monitors
    ) {
        this(consumer, monitors, DEFAULT_COOLDOWN);
    }

    /**
     * Creates a ConsumerSelfRegulator with custom cooldown period.
     *
     * @param consumer       KafkaConsumer to regulate
     * @param monitors       Monitors conduit from Layer 2
     * @param cooldownPeriod Duration to wait before auto-resume
     */
    public ConsumerSelfRegulator(
        KafkaConsumer<?, ?> consumer,
        Conduit<Monitors.Monitor, Monitors.Signal> monitors,
        Duration cooldownPeriod
    ) {
        this.consumer = Objects.requireNonNull(consumer, "consumer cannot be null");
        Objects.requireNonNull(monitors, "monitors cannot be null");
        this.cooldownPeriod = Objects.requireNonNull(cooldownPeriod, "cooldownPeriod cannot be null");

        // Create scheduler for auto-resume
        this.scheduler = Executors.newScheduledThreadPool(
            1,
            Thread.ofVirtual().name("consumer-self-regulator-", 0).factory()
        );

        // Subscribe to lag health monitors
        this.monitorSubscription = monitors.subscribe(cortex().subscriber(
            cortex().name("consumer-self-regulator"),
            this::handleMonitorSignal
        ));

        logger.info("ConsumerSelfRegulator started - closed-loop feedback enabled (cooldown={})",
            cooldownPeriod);
    }

    /**
     * Handles Monitor signals from ConsumerLagMonitor (Layer 2).
     *
     * @param subject   Subject of the monitor channel
     * @param registrar Registrar to register pipes
     */
    private void handleMonitorSignal(
        Subject<Channel<Monitors.Signal>> subject,
        Registrar<Monitors.Signal> registrar
    ) {
        registrar.register(signal -> {
            String monitorName = subject.name().toString();

            // Only regulate based on lag monitor
            // (could be "monitor.consumer.lag" or similar)
            if (!monitorName.contains("lag")) {
                return;
            }

            Monitors.Condition condition = signal.condition();
            Monitors.Dimension confidence = signal.dimension();

            // Self-regulation decision logic
            if (shouldPause(condition, confidence)) {
                pauseConsumer(monitorName, condition, confidence);
            } else if (shouldResume(condition, confidence)) {
                resumeConsumer(monitorName, condition, confidence);
            }
        });
    }

    /**
     * Determines if consumer should be paused.
     */
    private boolean shouldPause(Monitors.Condition condition, Monitors.Dimension confidence) {
        // Pause on DEGRADED with CONFIRMED confidence
        // (lag is critically high and confirmed)
        return condition == Monitors.Condition.DEGRADED &&
               confidence == Monitors.Dimension.CONFIRMED;
    }

    /**
     * Determines if consumer should be resumed.
     */
    private boolean shouldResume(Monitors.Condition condition, Monitors.Dimension confidence) {
        // Resume on CONVERGING (lag reducing) or STABLE (lag healthy)
        // with CONFIRMED confidence
        return (condition == Monitors.Condition.CONVERGING ||
                condition == Monitors.Condition.STABLE) &&
               confidence == Monitors.Dimension.CONFIRMED;
    }

    /**
     * Pauses the consumer due to degraded lag health.
     */
    private void pauseConsumer(String monitorName, Monitors.Condition condition, Monitors.Dimension confidence) {
        if (paused.compareAndSet(false, true)) {
            pauseStartTime = System.currentTimeMillis();

            // Get current assignment
            Set<TopicPartition> assignment = consumer.assignment();
            pausedPartitions = Set.copyOf(assignment);

            logger.warn("[SELF-REGULATION] Pausing consumer due to {} lag ({}): monitor={}, partitions={}",
                condition, confidence, monitorName, assignment.size());

            // Pause all assigned partitions
            consumer.pause(assignment);

            logger.warn("[SELF-REGULATION] Consumer paused - {} partitions paused",
                assignment.size());

            // Schedule auto-resume after cooldown (safety mechanism)
            scheduleAutoResume();
        } else {
            // Already paused - check if we've been paused too long
            long pauseDuration = System.currentTimeMillis() - pauseStartTime;
            if (pauseDuration > MAX_PAUSE_DURATION.toMillis()) {
                logger.error("[SELF-REGULATION] Consumer has been paused for {}ms (max={}ms) - forcing resume",
                    pauseDuration, MAX_PAUSE_DURATION.toMillis());
                forceResume();
            }
        }
    }

    /**
     * Resumes the consumer after lag has recovered.
     */
    private void resumeConsumer(String monitorName, Monitors.Condition condition, Monitors.Dimension confidence) {
        if (paused.compareAndSet(true, false)) {
            long pauseDuration = System.currentTimeMillis() - pauseStartTime;

            logger.info("[SELF-REGULATION] Resuming consumer - lag {} ({}): monitor={}, pause_duration={}ms",
                condition, confidence, monitorName, pauseDuration);

            // Resume previously paused partitions
            if (!pausedPartitions.isEmpty()) {
                consumer.resume(pausedPartitions);
                logger.info("[SELF-REGULATION] Consumer resumed - {} partitions resumed",
                    pausedPartitions.size());
                pausedPartitions = Set.of();
            }
        }
    }

    /**
     * Schedules automatic resume after cooldown period.
     * Safety mechanism to prevent infinite pause.
     */
    private void scheduleAutoResume() {
        scheduler.schedule(() -> {
            if (paused.percept()) {
                logger.warn("[SELF-REGULATION] Auto-resume triggered after cooldown ({})",
                    cooldownPeriod);
                forceResume();
            }
        }, cooldownPeriod.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Forces resume (used by auto-resume or max pause timeout).
     */
    private void forceResume() {
        if (paused.compareAndSet(true, false)) {
            if (!pausedPartitions.isEmpty()) {
                consumer.resume(pausedPartitions);
                logger.warn("[SELF-REGULATION] Consumer force-resumed - {} partitions resumed",
                    pausedPartitions.size());
                pausedPartitions = Set.of();
            }
        }
    }

    /**
     * Checks if consumer is currently paused.
     *
     * @return true if paused
     */
    public boolean isPaused() {
        return paused.percept();
    }

    /**
     * Returns duration consumer has been paused (0 if not paused).
     *
     * @return pause duration in milliseconds
     */
    public long getPauseDuration() {
        if (!paused.percept()) {
            return 0;
        }
        return System.currentTimeMillis() - pauseStartTime;
    }

    /**
     * Returns set of currently paused partitions.
     *
     * @return paused partitions (empty if not paused)
     */
    public Set<TopicPartition> getPausedPartitions() {
        return pausedPartitions;
    }

    @Override
    public void close() {
        if (monitorSubscription != null) {
            monitorSubscription.close();
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("ConsumerSelfRegulator stopped");
    }
}
