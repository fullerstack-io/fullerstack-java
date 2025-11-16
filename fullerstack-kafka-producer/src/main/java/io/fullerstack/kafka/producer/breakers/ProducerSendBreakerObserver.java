package io.fullerstack.kafka.producer.breakers;

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
 * Observes producer send monitor signals and manages circuit breaker state.
 * <p>
 * This observer implements the circuit breaker pattern for producer send operations:
 * <ul>
 *   <li><b>CLOSED</b>: Normal operation, sends proceed</li>
 *   <li><b>OPEN</b>: Circuit broken, sends fail fast</li>
 *   <li><b>HALF_OPEN</b>: Testing recovery, limited sends</li>
 * </ul>
 *
 * <h3>State Transitions (Pure Signal-Driven):</h3>
 * <pre>
 * CLOSE ──[5 consecutive DEGRADED]──> TRIP → OPEN
 *   ▲                                         │
 *   │                                  [First STABLE]
 *   │                                         │
 *   └──[3 STABLE in HALF_OPEN]── HALF_OPEN ◄──┘
 *       │
 *       └──[DEGRADED]──> OPEN
 * </pre>
 * <p>
 * <b>Note</b>: This observer is purely reactive - all state transitions are driven by
 * monitor signals. Recovery timing is controlled by how frequently monitors emit STABLE
 * signals, not by internal timers. Layer 4 Agents can implement retry policies.
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("producer.monitoring"));
 * Conduit<Monitor, Monitors.Signal> monitors = circuit.conduit(...);
 *
 * ProducerSendBreakerObserver observer = new ProducerSendBreakerObserver(
 *     circuit,
 *     monitors,
 *     "producer-1"
 * );
 *
 * // Breakers signals now emitted based on monitor signals
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
public class ProducerSendBreakerObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ProducerSendBreakerObserver.class);

    // Circuit breaker thresholds
    private static final int TRIP_THRESHOLD = 5;           // Consecutive failures to trip circuit
    private static final int HALF_OPEN_SUCCESS_COUNT = 3;  // Successful signals to close from HALF_OPEN

    private final Cortex cortex;
    private final Circuit circuit;
    private final String producerId;
    private final Conduit<Breaker, Breakers.Sign> breakers;
    private final Breaker sendBreaker;
    private final Subscription monitorSubscription;

    // State tracking using Breakers.Sign directly (pure reactive - no scheduler)
    private final AtomicReference<Breakers.Sign> state = new AtomicReference<>(Breakers.Sign.CLOSE);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger halfOpenSuccesses = new AtomicInteger(0);

    /**
     * Creates a new producer send breaker observer.
     *
     * @param circuit    Circuit for creating breakers conduit
     * @param monitors   Monitors conduit to subscribe to
     * @param producerId Producer identifier (e.g., "producer-1")
     */
    public ProducerSendBreakerObserver(
        Circuit circuit,
        Conduit<Monitor, Monitors.Signal> monitors,
        String producerId
    ) {
        this.cortex = cortex();
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        this.producerId = Objects.requireNonNull(producerId, "producerId cannot be null");

        // Create Breakers conduit
        this.breakers = circuit.conduit(
            cortex.name("breakers"),
            Breakers::composer
        );

        // Get Breaker instrument for this producer's send operations
        this.sendBreaker = breakers.percept(cortex.name(producerId + ".send.breaker"));

        // Subscribe to monitor signals (pure reactive - no scheduler needed)
        this.monitorSubscription = monitors.subscribe(cortex.subscriber(
            cortex.name(producerId + ".send-breaker-observer"),
            this::handleMonitorSignal
        ));

        // Initial state: CLOSE
        sendBreaker.close();

        logger.info("[BREAKERS] ProducerSendBreakerObserver created for {} - initial state: CLOSE (pure reactive)",
            producerId);
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

            logger.debug("[BREAKERS] {} received monitor signal: {} (current state: {})",
                producerId, signal.sign(), currentState);

            switch (signal.sign()) {
                case DEGRADED, DEFECTIVE, DOWN -> handleFailureSignal(currentState);
                case STABLE, CONVERGING -> handleSuccessSignal(currentState);
                default -> logger.trace("[BREAKERS] {} ignoring monitor signal: {}",
                    producerId, signal.sign());
            }
        });
    }

    /**
     * Handles failure signals (DEGRADED, DEFECTIVE, DOWN).
     *
     * @param currentState Current circuit breaker state
     */
    private void handleFailureSignal(Breakers.Sign currentState) {
        switch (currentState) {
            case CLOSE -> {
                int failures = consecutiveFailures.incrementAndGet();
                logger.warn("[BREAKERS] {} failure #{} (threshold: {})",
                    producerId, failures, TRIP_THRESHOLD);

                if (failures >= TRIP_THRESHOLD) {
                    tripCircuit();
                }
            }

            case HALF_OPEN -> {
                // Probe failed - back to OPEN
                logger.warn("[BREAKERS] {} HALF_OPEN probe failed - returning to OPEN",
                    producerId);
                halfOpenSuccesses.set(0);
                openCircuit();
            }

            case OPEN -> {
                // Already open, just log
                logger.trace("[BREAKERS] {} already OPEN, ignoring failure", producerId);
            }
        }
    }

    /**
     * Handles success signals (STABLE, CONVERGING).
     *
     * @param currentState Current circuit breaker state
     */
    private void handleSuccessSignal(Breakers.Sign currentState) {
        switch (currentState) {
            case CLOSE -> {
                // Reset failure count on success
                int previousFailures = consecutiveFailures.getAndSet(0);
                if (previousFailures > 0) {
                    logger.info("[BREAKERS] {} recovered - {} failures cleared",
                        producerId, previousFailures);
                }
            }

            case HALF_OPEN -> {
                int successes = halfOpenSuccesses.incrementAndGet();
                logger.info("[BREAKERS] {} HALF_OPEN probe success #{}/{}",
                    producerId, successes, HALF_OPEN_SUCCESS_COUNT);

                if (successes >= HALF_OPEN_SUCCESS_COUNT) {
                    // Enough successful probes - close circuit
                    closeCircuit();
                }
            }

            case OPEN -> {
                // First STABLE signal while OPEN → transition to HALF_OPEN
                logger.info("[BREAKERS] {} received STABLE while OPEN - transitioning to HALF_OPEN",
                    producerId);
                enterHalfOpen();
            }
        }
    }

    /**
     * Trips the circuit: CLOSE → TRIP → OPEN.
     * <p>
     * Purely reactive - no scheduled transition. Will move to HALF_OPEN when first
     * STABLE signal arrives (driven by monitor recovery, not internal timer).
     */
    private void tripCircuit() {
        if (state.compareAndSet(Breakers.Sign.CLOSE, Breakers.Sign.OPEN)) {
            sendBreaker.trip();
            sendBreaker.open();

            logger.error("[BREAKERS] {} CIRCUIT TRIPPED - {} consecutive failures, now OPEN (waiting for STABLE signal)",
                producerId, consecutiveFailures.get());
        }
    }

    /**
     * Opens the circuit: HALF_OPEN → OPEN (after probe failure).
     * <p>
     * Purely reactive - will retry HALF_OPEN when next STABLE signal arrives.
     */
    private void openCircuit() {
        if (state.compareAndSet(Breakers.Sign.HALF_OPEN, Breakers.Sign.OPEN)) {
            sendBreaker.open();

            logger.error("[BREAKERS] {} CIRCUIT RE-OPENED - probe failed (waiting for next STABLE signal)",
                producerId);
        }
    }

    /**
     * Enters HALF_OPEN state: OPEN → HALF_OPEN → PROBE.
     * <p>
     * Triggered by first STABLE signal after OPEN. Probes test if recovery is sustained.
     */
    private void enterHalfOpen() {
        if (state.compareAndSet(Breakers.Sign.OPEN, Breakers.Sign.HALF_OPEN)) {
            sendBreaker.halfOpen();
            sendBreaker.probe();

            halfOpenSuccesses.set(0);  // Reset success counter

            logger.warn("[BREAKERS] {} entered HALF_OPEN - need {} consecutive STABLE signals to close",
                producerId, HALF_OPEN_SUCCESS_COUNT);
        }
    }

    /**
     * Closes the circuit: HALF_OPEN → CLOSE (recovery successful).
     */
    private void closeCircuit() {
        if (state.compareAndSet(Breakers.Sign.HALF_OPEN, Breakers.Sign.CLOSE)) {
            sendBreaker.close();
            consecutiveFailures.set(0);
            halfOpenSuccesses.set(0);

            logger.info("[BREAKERS] {} CIRCUIT CLOSED - recovery successful", producerId);
        }
    }

    /**
     * Manually resets the circuit breaker (operator intervention).
     * <p>
     * Forces circuit back to CLOSE state regardless of current state.
     */
    public void reset() {
        Breakers.Sign previousState = state.getAndSet(Breakers.Sign.CLOSE);

        sendBreaker.reset();
        sendBreaker.close();

        consecutiveFailures.set(0);
        halfOpenSuccesses.set(0);

        logger.warn("[BREAKERS] {} MANUAL RESET - {} → CLOSED", producerId, previousState);
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
        logger.info("[BREAKERS] Shutting down ProducerSendBreakerObserver for {}", producerId);

        if (monitorSubscription != null) {
            monitorSubscription.close();
        }

        logger.info("[BREAKERS] ProducerSendBreakerObserver stopped for {}", producerId);
    }
}
