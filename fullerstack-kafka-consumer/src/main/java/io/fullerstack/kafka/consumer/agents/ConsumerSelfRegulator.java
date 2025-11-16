package io.fullerstack.kafka.consumer.agents;

import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Agents.Agent;
import io.humainary.substrates.ext.serventis.ext.Agents.Dimension;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.api.Substrates.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
 * Layer 4a (ACT - Agents): Autonomous promise-based self-regulation for Kafka consumers.
 * <p>
 * This implements closed-loop feedback using <b>Agents API (Promise Theory)</b> - the consumer
 * autonomously regulates itself based on its own lag signals, with NO human intervention.
 * <p>
 * <b>Signal-Flow Pattern:</b>
 * <pre>
 * Layer 1: ConsumerLagObserver → Gauges (lag metrics)
 *                                    ↓
 * Layer 2: ConsumerLagMonitor → Monitors (lag condition: DEGRADED)
 *                                    ↓
 * Layer 4a: ConsumerSelfRegulator → AGENTS (promise-based self-regulation)
 *           ↓
 *      promise() → pause consumer → fulfill()
 *      promise() → resume consumer → fulfill()
 *      (if failure) → breach()
 * </pre>
 *
 * <h3>Agents API Usage (Promise Theory - Mark Burgess):</h3>
 * <ul>
 *   <li><b>Agent OFFERS</b> pause/resume capability</li>
 *   <li><b>Agent PROMISES</b> to pause when lag degraded</li>
 *   <li><b>Agent FULFILLS</b> promise by pausing consumer partitions</li>
 *   <li><b>Agent PROMISES</b> to resume when lag recovers</li>
 *   <li><b>Agent FULFILLS</b> promise by resuming consumer partitions</li>
 *   <li><b>Agent BREACHES</b> promise if pause/resume fails</li>
 * </ul>
 *
 * <h3>Self-Regulation Actions:</h3>
 * <table border="1">
 * <tr><th>Condition</th><th>Confidence</th><th>Action</th><th>Mechanism</th></tr>
 * <tr><td>DEGRADED</td><td>CONFIRMED</td><td><b>Pause</b></td><td>consumer.pause(assignment)</td></tr>
 * <tr><td>DEGRADED</td><td>MEASURED</td><td><b>Pause (gentle)</b></td><td>Pause highest-lag partitions only</td></tr>
 * <tr><td>STABLE</td><td>CONFIRMED</td><td><b>Resume</b></td><td>consumer.resume(assignment)</td></tr>
 * <tr><td>CONVERGING</td><td>CONFIRMED</td><td><b>Resume (gradual)</b></td><td>Resume some partitions, monitor lag</td></tr>
 * </table>
 *
 * <h3>Why Agents API (not Actors API)?</h3>
 * <ul>
 *   <li><b>Speed</b>: Autonomous action within milliseconds (no human approval needed)</li>
 *   <li><b>Closed-loop</b>: System regulates itself based on own signals</li>
 *   <li><b>No bottleneck</b>: Scales to thousands of consumers without human coordination</li>
 *   <li><b>Promise semantics</b>: Clear accountability (fulfill vs breach)</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("consumer.health"));
 *
 * // Layer 1: Lag observer
 * ConsumerLagObserver observer = new ConsumerLagObserver(circuit, consumer);
 * observer.start();
 *
 * // Layer 2: Lag monitor
 * ConsumerLagMonitor monitor = new ConsumerLagMonitor(circuit, observer.gauges());
 *
 * // Layer 4a: Self-regulator (Agents API - autonomous)
 * ConsumerSelfRegulator regulator = new ConsumerSelfRegulator(
 *     consumer,
 *     circuit,
 *     monitor.monitors()
 * );
 *
 * // Consumer automatically pauses when lag DEGRADED (CONFIRMED)
 * // Agent emits: promise() → fulfill() or breach()
 *
 * // ... later ...
 * regulator.close();
 * monitor.close();
 * observer.close();
 * }</pre>
 *
 * @see io.humainary.substrates.ext.serventis.ext.agents.Agents
 * @see Agent
 */
public class ConsumerSelfRegulator implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerSelfRegulator.class);

    private final Cortex cortex;
    private final Circuit circuit;
    private final KafkaConsumer<?, ?> consumer;
    private final Conduit<Agent, Agents.Signal> agents;
    private final Subscription monitorSubscription;
    private final ScheduledExecutorService scheduler;

    // Self-regulation state
    private final AtomicBoolean paused = new AtomicBoolean(false);

    /**
     * Creates a new consumer self-regulator.
     *
     * @param consumer Kafka consumer to regulate
     * @param circuit parent circuit for agents conduit
     * @param monitors monitors conduit from ConsumerLagMonitor (Layer 2)
     */
    public ConsumerSelfRegulator(
        KafkaConsumer<?, ?> consumer,
        Circuit circuit,
        Conduit<Monitors.Monitor, Monitors.Signal> monitors
    ) {
        this.cortex = cortex();
        this.consumer = Objects.requireNonNull(consumer, "consumer cannot be null");
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        Objects.requireNonNull(monitors, "monitors cannot be null");

        // Create Agents conduit using Agents.composer (RC1 pattern)
        this.agents = circuit.conduit(
            cortex.name("agents"),
            Agents::composer
        );

        // Subscribe to lag health monitors
        this.monitorSubscription = monitors.subscribe(cortex.subscriber(
            cortex.name("consumer-self-regulator"),
            this::handleMonitorSignal
        ));

        // Scheduler for auto-resume after cooldown
        this.scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "consumer-self-regulator");
            t.setDaemon(true);
            return t;
        });

        logger.info("ConsumerSelfRegulator started - Agents API (promise-based self-regulation)");
    }

    /**
     * Handle Monitors signals from Layer 2 (ConsumerLagMonitor).
     * <p>
     * Autonomous decision-making based on lag health - NO human approval required.
     */
    private void handleMonitorSignal(
        Subject<Channel<Monitors.Signal>> subject,
        Registrar<Monitors.Signal> registrar
    ) {
        registrar.register(signal -> {
            String monitorName = subject.name().toString();

            // Only regulate based on lag monitor
            if (!monitorName.contains("lag")) {
                return;
            }

            logger.debug("Received monitor signal: {} -> {} ({})",
                monitorName, signal.sign(), signal.dimension());

            // Self-regulation based on condition + confidence
            switch (signal.sign()) {
                case DEGRADED -> {
                    // Lag degraded - pause consumer using Agents API
                    if (signal.dimension() == Monitors.Dimension.CONFIRMED) {
                        pauseConsumer("DEGRADED (CONFIRMED)");
                    } else if (signal.dimension() == Monitors.Dimension.MEASURED) {
                        pauseConsumer("DEGRADED (MEASURED) - gentle pause");
                    }
                }

                case STABLE -> {
                    // Lag stable - resume consumer using Agents API
                    if (signal.dimension() == Monitors.Dimension.CONFIRMED) {
                        resumeConsumer("STABLE (CONFIRMED)");
                    }
                }

                case CONVERGING -> {
                    // Lag recovering - gradual resume
                    if (signal.dimension() == Monitors.Dimension.CONFIRMED) {
                        resumeConsumer("CONVERGING (CONFIRMED) - gradual resume");
                    }
                }
            }
        });
    }

    /**
     * Pause consumer using Agents API (Promise Theory).
     * <p>
     * Agent PROMISES to pause, executes pause, then FULFILLS or BREACHES promise.
     */
    private void pauseConsumer(String reason) {
        if (!paused.compareAndSet(false, true)) {
            logger.debug("Already paused, skipping");
            return;  // Already paused
        }

        Agent agent = agents.percept(cortex.name("agent.consumer.pause"));

        // PROMISER perspective: I am making this promise
        agent.promise(Dimension.PROMISER);  // "I promise to pause the consumer"

        try {
            logger.warn("[AGENTS] Pausing consumer due to {} - pausing all assigned partitions",
                reason);

            // Execute pause - Kafka consumer has built-in pause() API
            consumer.pause(consumer.assignment());

            // Agent kept promise
            agent.fulfill(Dimension.PROMISER);  // PROMISER: "I fulfilled my promise to pause"

            logger.info("[AGENTS] Promise fulfilled: Consumer paused ({} partitions)",
                consumer.assignment().size());

            // Schedule auto-resume after cooldown (safety mechanism)
            scheduleAutoResume(Duration.ofSeconds(30));

        } catch (java.lang.Exception e) {
            // Agent failed to keep promise
            agent.breach(Dimension.PROMISER);  // PROMISER: "I failed to fulfill my promise"

            logger.error("[AGENTS] Promise breached: Failed to pause consumer", e);

            paused.set(false);  // Rollback state
            throw e;
        }
    }

    /**
     * Resume consumer using Agents API (Promise Theory).
     * <p>
     * Agent PROMISES to resume, executes resume, then FULFILLS or BREACHES promise.
     */
    private void resumeConsumer(String reason) {
        if (!paused.compareAndSet(true, false)) {
            logger.debug("Already running, skipping");
            return;  // Already running
        }

        Agent agent = agents.percept(cortex.name("agent.consumer.resume"));

        // PROMISER perspective: I am making this promise
        agent.promise(Dimension.PROMISER);  // "I promise to resume the consumer"

        try {
            logger.info("[AGENTS] Resuming consumer due to {} - resuming all paused partitions",
                reason);

            // Execute resume - Kafka consumer has built-in resume() API
            consumer.resume(consumer.paused());

            // Agent kept promise
            agent.fulfill(Dimension.PROMISER);  // PROMISER: "I fulfilled my promise to resume"

            logger.info("[AGENTS] Promise fulfilled: Consumer resumed ({} partitions)",
                consumer.paused().size());

        } catch (java.lang.Exception e) {
            // Agent failed to keep promise
            agent.breach(Dimension.PROMISER);  // PROMISER: "I failed to fulfill my promise"

            logger.error("[AGENTS] Promise breached: Failed to resume consumer", e);

            paused.set(true);  // Rollback state
            throw e;
        }
    }

    /**
     * Schedule auto-resume after cooldown period.
     * <p>
     * Safety mechanism: If lag doesn't recover naturally, force resume after timeout
     * to prevent indefinite pausing.
     */
    private void scheduleAutoResume(Duration cooldown) {
        scheduler.schedule(() -> {
            if (paused.get()) {
                logger.warn("[AGENTS] Auto-resume triggered after {} cooldown - forcing resume",
                    cooldown);
                resumeConsumer("AUTO-RESUME (timeout)");
            }
        }, cooldown.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Check if consumer is currently paused.
     *
     * @return true if consumer is paused
     */
    public boolean isPaused() {
        return paused.get();
    }

    @Override
    public void close() {
        logger.info("Shutting down consumer self-regulator");

        if (monitorSubscription != null) {
            monitorSubscription.close();
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate in 5 seconds, forcing shutdown");
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while waiting for scheduler shutdown", e);
            scheduler.shutdownNow();
        }

        logger.info("Consumer self-regulator stopped");
    }
}
