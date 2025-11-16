package io.fullerstack.kafka.consumer.breakers;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Breakers;
import io.humainary.substrates.ext.serventis.ext.Breakers.Breaker;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Observes consumer lag monitor signals and manages circuit breaker state for pause/resume.
 * <p>
 * This observer implements the circuit breaker pattern for consumer lag management:
 * <ul>
 *   <li><b>CLOSED</b>: Normal consumption, no lag issues</li>
 *   <li><b>OPEN</b>: Circuit broken, consumer paused to prevent OOM</li>
 *   <li><b>HALF_OPEN</b>: Testing recovery, partial resume (1 partition)</li>
 * </ul>
 *
 * <h3>State Transitions (Pure Signal-Driven):</h3>
 * <pre>
 * CLOSE ──[3 consecutive DEGRADED]──> TRIP → OPEN
 *   ▲                                        │
 *   │                                 [First STABLE]
 *   │                                        │
 *   └──[3 STABLE in HALF_OPEN]── HALF_OPEN ◄──┘
 *       │
 *       └──[DEGRADED]──> OPEN
 * </pre>
 * <p>
 * <b>Note</b>: This observer is purely reactive - all state transitions are driven by
 * lag monitor signals. No internal timers or polling.
 *
 * <h3>Lag Thresholds:</h3>
 * <ul>
 *   <li><b>TRIP</b>: ≥50,000 messages behind (prevent OOM)</li>
 *   <li><b>HALF_OPEN</b>: ≤10,000 messages behind (safe to test resume)</li>
 *   <li><b>CLOSE</b>: ≤1,000 messages behind (full resume)</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("consumer.monitoring"));
 * Conduit<Monitor, Monitors.Signal> monitors = circuit.conduit(...);
 *
 * ConsumerLagBreakerObserver observer = new ConsumerLagBreakerObserver(
 *     circuit,
 *     monitors,
 *     "consumer-group-1"
 * );
 *
 * // Breakers signals now emitted based on lag monitor signals
 * Conduit<Breaker, Breakers.Sign> breakers = observer.breakers();
 * breakers.subscribe(...);
 *
 * // Later...
 * observer.close();
 * }</pre>
 *
 * @author Fullerstack
 * @see Breakers
 * @see Monitors
 */
public class ConsumerLagBreakerObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerLagBreakerObserver.class);

    // Lag-based thresholds (messages behind)
    private static final long SEVERE_LAG_THRESHOLD = 50_000;   // Trip circuit
    private static final long MODERATE_LAG_THRESHOLD = 10_000; // Half-open possible
    private static final long NORMAL_LAG_THRESHOLD = 1_000;    // Close circuit

    // Circuit breaker thresholds
    private static final int TRIP_THRESHOLD = 3;                  // Consecutive DEGRADED signals to trip
    private static final int HALF_OPEN_SUCCESS_COUNT = 3;         // Consecutive STABLE signals to close

    private final Cortex cortex;
    private final Circuit circuit;
    private final String consumerGroupId;
    private final Conduit<Breaker, Breakers.Sign> breakers;
    private final Breaker lagBreaker;
    private final Subscription monitorSubscription;

    // State tracking using Breakers.Sign directly (pure reactive - no scheduler)
    private final AtomicReference<Breakers.Sign> state = new AtomicReference<>(Breakers.Sign.CLOSE);
    private final AtomicInteger consecutiveDegradations = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);

    /**
     * Creates a new consumer lag breaker observer.
     *
     * @param circuit          Circuit for creating breakers conduit
     * @param monitors         Monitors conduit to subscribe to
     * @param consumerGroupId  Consumer group identifier (e.g., "consumer-group-1")
     */
    public ConsumerLagBreakerObserver(
        Circuit circuit,
        Conduit<Monitor, Monitors.Signal> monitors,
        String consumerGroupId
    ) {
        this.cortex = cortex();
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        this.consumerGroupId = Objects.requireNonNull(consumerGroupId, "consumerGroupId cannot be null");

        // Create Breakers conduit
        this.breakers = circuit.conduit(
            cortex.name("breakers"),
            Breakers::composer
        );

        // Get Breaker instrument for this consumer group's lag
        this.lagBreaker = breakers.percept(cortex.name(consumerGroupId + ".lag.breaker"));

        // Subscribe to monitor signals (pure reactive - no scheduler needed)
        this.monitorSubscription = monitors.subscribe(cortex.subscriber(
            cortex.name(consumerGroupId + ".lag-breaker-observer"),
            this::handleMonitorSignal
        ));

        // Initial state: CLOSE
        lagBreaker.close();

        logger.info("[BREAKERS] ConsumerLagBreakerObserver created for {} - initial state: CLOSE (pure reactive)",
            consumerGroupId);
    }

    /**
     * Handles monitor signals and triggers circuit breaker state transitions.
     *
     * @param subject   Subject of the monitor signal
     * @param registrar Registrar for registering signal handlers
     */
    private void handleMonitorSignal(
        Subject<Channel<Monitors.Signal>> subject,
        Registrar<Monitors.Signal> registrar
    ) {
        registrar.register(signal -> {
            Breakers.Sign currentState = state.get();

            logger.debug("[BREAKERS] {} received lag monitor signal: {} (current state: {})",
                consumerGroupId, signal.sign(), currentState);

            switch (signal.sign()) {
                case DEGRADED, DEFECTIVE, DOWN -> handleLagExcessive(currentState);
                case STABLE, CONVERGING -> handleLagNormal(currentState);
                default -> logger.trace("[BREAKERS] {} ignoring monitor signal: {}",
                    consumerGroupId, signal.sign());
            }
        });
    }

    /**
     * Handles excessive lag signals (DEGRADED, DEFECTIVE, DOWN).
     * <p>
     * Lag is excessive when ≥50,000 messages behind.
     *
     * @param currentState Current circuit breaker state
     */
    private void handleLagExcessive(Breakers.Sign currentState) {
        switch (currentState) {
            case CLOSE -> {
                int degradations = consecutiveDegradations.incrementAndGet();
                logger.warn("[BREAKERS] {} lag excessive - degradation #{} (threshold: {})",
                    consumerGroupId, degradations, TRIP_THRESHOLD);

                if (degradations >= TRIP_THRESHOLD) {
                    tripCircuit();
                }
            }

            case HALF_OPEN -> {
                // Probe failed (lag still high) - back to OPEN
                logger.warn("[BREAKERS] {} HALF_OPEN test failed - lag still excessive, returning to OPEN",
                    consumerGroupId);
                halfOpenSuccesses.set(0);
                openCircuit();
            }

            case OPEN -> {
                // Already open, just log
                logger.trace("[BREAKERS] {} already OPEN (paused), lag still excessive", consumerGroupId);
            }
        }
    }

    /**
     * Handles normal lag signals (STABLE, CONVERGING).
     * <p>
     * Lag is normal when <1,000 messages behind.
     *
     * @param currentState Current circuit breaker state
     */
    private void handleLagNormal(Breakers.Sign currentState) {
        switch (currentState) {
            case CLOSE -> {
                // Reset degradation count on recovery
                int previousDegradations = consecutiveDegradations.getAndSet(0);
                if (previousDegradations > 0) {
                    logger.info("[BREAKERS] {} lag recovered - {} degradations cleared",
                        consumerGroupId, previousDegradations);
                }
            }

            case HALF_OPEN -> {
                int successes = halfOpenSuccesses.incrementAndGet();
                logger.info("[BREAKERS] {} HALF_OPEN test success #{}/{} - lag manageable",
                    consumerGroupId, successes, HALF_OPEN_SUCCESS_COUNT);

                if (successes >= HALF_OPEN_SUCCESS_COUNT) {
                    // Enough successful polls with manageable lag - close circuit
                    closeCircuit();
                }
            }

            case OPEN -> {
                // Lag improved while paused - consider HALF_OPEN
                logger.info("[BREAKERS] {} lag improved while OPEN - transitioning to HALF_OPEN",
                    consumerGroupId);
                enterHalfOpen();
            }
        }
    }

    /**
     * Trips the circuit: CLOSE → TRIP → OPEN.
     * <p>
     * Pauses consumer to prevent OOM. Purely reactive - will transition to HALF_OPEN
     * when first STABLE signal arrives (lag monitor detects improvement).
     */
    private void tripCircuit() {
        if (state.compareAndSet(Breakers.Sign.CLOSE, Breakers.Sign.OPEN)) {
            lagBreaker.trip();
            lagBreaker.open();

            logger.error("[BREAKERS] {} CIRCUIT TRIPPED - lag excessive (≥{} messages), PAUSING consumer (waiting for STABLE signal)",
                consumerGroupId, SEVERE_LAG_THRESHOLD);
        }
    }

    /**
     * Opens the circuit: HALF_OPEN → OPEN (after test failure).
     * <p>
     * Purely reactive - will retry HALF_OPEN when next STABLE signal arrives.
     */
    private void openCircuit() {
        if (state.compareAndSet(Breakers.Sign.HALF_OPEN, Breakers.Sign.OPEN)) {
            lagBreaker.open();

            logger.error("[BREAKERS] {} CIRCUIT RE-OPENED - lag still excessive, PAUSING again (waiting for STABLE)",
                consumerGroupId);
        }
    }

    /**
     * Enters HALF_OPEN state: OPEN → HALF_OPEN → PROBE.
     * <p>
     * Triggered by first STABLE signal after OPEN. Tests partial resume.
     */
    private void enterHalfOpen() {
        if (state.compareAndSet(Breakers.Sign.OPEN, Breakers.Sign.HALF_OPEN)) {
            lagBreaker.halfOpen();
            lagBreaker.probe();

            halfOpenSuccesses.set(0);  // Reset success counter

            logger.warn("[BREAKERS] {} entered HALF_OPEN - need {} consecutive STABLE signals (lag manageable) to close",
                consumerGroupId, HALF_OPEN_SUCCESS_COUNT);
        }
    }

    /**
     * Closes the circuit: HALF_OPEN → CLOSE (recovery successful).
     * <p>
     * Fully resumes consumer - lag is manageable.
     */
    private void closeCircuit() {
        if (state.compareAndSet(Breakers.Sign.HALF_OPEN, Breakers.Sign.CLOSE)) {
            lagBreaker.close();
            consecutiveDegradations.set(0);
            halfOpenSuccesses.set(0);

            logger.info("[BREAKERS] {} CIRCUIT CLOSED - lag manageable, RESUMING full consumption",
                consumerGroupId);
        }
    }


    /**
     * Manually resets the circuit breaker (operator intervention).
     * <p>
     * Forces circuit back to CLOSE state regardless of lag.
     */
    public void reset() {
        Breakers.Sign previousState = state.getAndSet(Breakers.Sign.CLOSE);

        lagBreaker.reset();
        lagBreaker.close();

        consecutiveDegradations.set(0);
        halfOpenSuccesses.set(0);

        logger.warn("[BREAKERS] {} MANUAL RESET - {} → CLOSED (lag ignored)", consumerGroupId, previousState);
    }

    /**
     * Gets the breakers conduit for subscribing to breaker signals.
     *
     * @return Breakers conduit
     */
    public Conduit<Breaker, Breakers.Sign> breakers() {
        return breakers;
    }

    /**
     * Gets the current circuit breaker state (for testing/debugging).
     *
     * @return Current state (Breakers.Sign)
     */
    public Breakers.Sign getState() {
        return state.get();
    }

    @Override
    public void close() {
        logger.info("[BREAKERS] Shutting down ConsumerLagBreakerObserver for {}", consumerGroupId);

        if (monitorSubscription != null) {
            monitorSubscription.close();
        }

        logger.info("[BREAKERS] ConsumerLagBreakerObserver stopped for {}", consumerGroupId);
    }
}
