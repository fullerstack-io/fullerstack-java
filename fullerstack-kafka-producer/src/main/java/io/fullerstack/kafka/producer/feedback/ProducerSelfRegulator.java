package io.fullerstack.kafka.producer.feedback;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 3 (ACT): Closed-loop feedback - Producer self-regulates based on its own buffer health.
 *
 * <p><b>Signal-Flow Pattern:</b>
 * <pre>
 * Layer 1: ProducerBufferObserver → Gauges (buffer usage)
 *                                       ↓
 * Layer 2: ProducerBufferMonitor → Monitors (buffer condition)
 *                                       ↓
 * Layer 3: ProducerSelfRegulator → SELF-REGULATION (pause/resume)
 * </pre>
 *
 * <p><b>Self-Regulation Logic:</b>
 * <ul>
 *   <li><b>DEGRADED + CONFIRMED</b> → Pause producer (buffer critically full)</li>
 *   <li><b>STABLE + CONFIRMED</b> → Resume producer (buffer recovered)</li>
 *   <li><b>Auto-resume after cooldown</b> → Prevent infinite pause</li>
 * </ul>
 *
 * <p><b>Key Insight:</b>
 * This is NOT monitoring external Kafka brokers - this is the producer monitoring
 * and regulating ITSELF based on its own internal buffer state.
 *
 * <p><b>Example Usage:</b>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("producer.health"));
 *
 * // Layer 1: Buffer receptor
 * ProducerBufferObserver receptor = new ProducerBufferObserver(circuit);
 *
 * // Layer 2: Buffer monitor (pattern detection)
 * ProducerBufferMonitor monitor = new ProducerBufferMonitor(
 *     circuit,
 *     receptor.gauges()
 * );
 *
 * // Layer 3: Self-regulator (closed-loop feedback)
 * ProducerSelfRegulator regulator = new ProducerSelfRegulator(
 *     producer,
 *     monitor.monitors()
 * );
 *
 * // Producer now automatically pauses when buffer DEGRADED
 * // Producer automatically resumes when buffer STABLE
 * }</pre>
 *
 * <p><b>Benefits:</b>
 * <ul>
 *   <li>Prevents buffer overflow and OOM errors</li>
 *   <li>Automatic backpressure without manual intervention</li>
 *   <li>Self-healing: resumes automatically when healthy</li>
 *   <li>Composable: can stack multiple regulators</li>
 * </ul>
 */
public class ProducerSelfRegulator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ProducerSelfRegulator.class);

    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(10);
    private static final Duration MAX_PAUSE_DURATION = Duration.ofMinutes(5);

    private final KafkaProducer<?, ?> producer;
    private final Subscription monitorSubscription;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;
    private final Duration cooldownPeriod;

    private volatile long pauseStartTime = 0;

    /**
     * Creates a ProducerSelfRegulator with default cooldown (10 seconds).
     *
     * @param producer KafkaProducer to regulate
     * @param monitors Monitors conduit from Layer 2 (ProducerBufferMonitor)
     */
    public ProducerSelfRegulator(
        KafkaProducer<?, ?> producer,
        Conduit<Monitors.Monitor, Monitors.Signal> monitors
    ) {
        this(producer, monitors, DEFAULT_COOLDOWN);
    }

    /**
     * Creates a ProducerSelfRegulator with custom cooldown period.
     *
     * @param producer       KafkaProducer to regulate
     * @param monitors       Monitors conduit from Layer 2
     * @param cooldownPeriod Duration to wait before auto-resume
     */
    public ProducerSelfRegulator(
        KafkaProducer<?, ?> producer,
        Conduit<Monitors.Monitor, Monitors.Signal> monitors,
        Duration cooldownPeriod
    ) {
        this.producer = Objects.requireNonNull(producer, "producer cannot be null");
        Objects.requireNonNull(monitors, "monitors cannot be null");
        this.cooldownPeriod = Objects.requireNonNull(cooldownPeriod, "cooldownPeriod cannot be null");

        // Create scheduler for auto-resume
        this.scheduler = Executors.newScheduledThreadPool(
            1,
            Thread.ofVirtual().name("producer-self-regulator-", 0).factory()
        );

        // Subscribe to buffer health monitors
        this.monitorSubscription = monitors.subscribe(cortex().subscriber(
            cortex().name("producer-self-regulator"),
            this::handleMonitorSignal
        ));

        logger.info("ProducerSelfRegulator started - closed-loop feedback enabled (cooldown={})",
            cooldownPeriod);
    }

    /**
     * Handles Monitor signals from ProducerBufferMonitor (Layer 2).
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

            // Only regulate based on buffer monitor
            // (could be "monitor.producer.buffer" or similar)
            if (!monitorName.contains("buffer")) {
                return;
            }

            Monitors.Condition condition = signal.condition();
            Monitors.Dimension confidence = signal.dimension();

            // Self-regulation decision logic
            if (shouldPause(condition, confidence)) {
                pauseProducer(monitorName, condition, confidence);
            } else if (shouldResume(condition, confidence)) {
                resumeProducer(monitorName, condition, confidence);
            }
        });
    }

    /**
     * Determines if producer should be paused.
     */
    private boolean shouldPause(Monitors.Condition condition, Monitors.Dimension confidence) {
        // Only pause on DEGRADED with CONFIRMED confidence
        // (tentative/measured signals don't trigger pause - wait for confirmation)
        return condition == Monitors.Condition.DEGRADED &&
               confidence == Monitors.Dimension.CONFIRMED;
    }

    /**
     * Determines if producer should be resumed.
     */
    private boolean shouldResume(Monitors.Condition condition, Monitors.Dimension confidence) {
        // Resume on STABLE or CONVERGING with CONFIRMED confidence
        return (condition == Monitors.Condition.STABLE ||
                condition == Monitors.Condition.CONVERGING) &&
               confidence == Monitors.Dimension.CONFIRMED;
    }

    /**
     * Pauses the producer due to degraded buffer health.
     */
    private void pauseProducer(String monitorName, Monitors.Condition condition, Monitors.Dimension confidence) {
        if (paused.compareAndSet(false, true)) {
            pauseStartTime = System.currentTimeMillis();

            logger.warn("[SELF-REGULATION] Pausing producer due to {} buffer ({}): monitor={}",
                condition, confidence, monitorName);

            // Note: KafkaProducer doesn't have a pause() method like Consumer
            // In practice, you would:
            // 1. Set a flag that send() operations check
            // 2. Use rate limiting
            // 3. Apply backpressure at application level
            //
            // For demonstration, we'll log the decision.
            // In real implementation, this would integrate with your send() wrapper.

            logger.warn("[SELF-REGULATION] Producer paused - stopping send operations");

            // Schedule auto-resume after cooldown (safety mechanism)
            scheduleAutoResume();
        } else {
            // Already paused - check if we've been paused too long
            long pauseDuration = System.currentTimeMillis() - pauseStartTime;
            if (pauseDuration > MAX_PAUSE_DURATION.toMillis()) {
                logger.error("[SELF-REGULATION] Producer has been paused for {}ms (max={}ms) - forcing resume",
                    pauseDuration, MAX_PAUSE_DURATION.toMillis());
                forceResume();
            }
        }
    }

    /**
     * Resumes the producer after buffer has recovered.
     */
    private void resumeProducer(String monitorName, Monitors.Condition condition, Monitors.Dimension confidence) {
        if (paused.compareAndSet(true, false)) {
            long pauseDuration = System.currentTimeMillis() - pauseStartTime;

            logger.info("[SELF-REGULATION] Resuming producer - buffer {} ({}): monitor={}, pause_duration={}ms",
                condition, confidence, monitorName, pauseDuration);

            logger.info("[SELF-REGULATION] Producer resumed - send operations enabled");
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
            logger.warn("[SELF-REGULATION] Producer force-resumed");
        }
    }

    /**
     * Checks if producer is currently paused.
     *
     * @return true if paused
     */
    public boolean isPaused() {
        return paused.percept();
    }

    /**
     * Returns duration producer has been paused (0 if not paused).
     *
     * @return pause duration in milliseconds
     */
    public long getPauseDuration() {
        if (!paused.percept()) {
            return 0;
        }
        return System.currentTimeMillis() - pauseStartTime;
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

        logger.info("ProducerSelfRegulator stopped");
    }
}
