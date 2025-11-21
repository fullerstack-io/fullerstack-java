package io.fullerstack.kafka.core.actors;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Actors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Base utility class for Actors providing rate limiting, idempotency, and Speech Act emission.
 *
 * <p>BaseActor provides common infrastructure for Layer 4 (ACT phase) Actors without imposing
 * subscription structure. Each Actor subclass controls its own subscription strategy and filtering
 * logic using hierarchical Names and Percept semantics.
 *
 * <h3>Percept Model:</h3>
 * <ul>
 *   <li>{@link io.humainary.substrates.ext.serventis.ext.Situations.Situation Situation} (Percept) - emits situational assessments (NORMAL/WARNING/CRITICAL)</li>
 *   <li>{@link Actors.Actor Actor} (Percept) - emits speech acts about actions (DELIVER/DENY)</li>
 * </ul>
 *
 * <h3>Provided Utilities:</h3>
 * <ul>
 *   <li><b>Rate Limiting</b>: Prevents action storms by limiting actions per time interval</li>
 *   <li><b>Idempotency</b>: Prevents duplicate actions using action keys</li>
 *   <li><b>Speech Act Emission</b>: Emits DELIVER/DENY signs for observability</li>
 *   <li><b>Error Handling</b>: Graceful degradation with proper sign emission</li>
 * </ul>
 *
 * <h3>Subclass Responsibilities:</h3>
 * <ul>
 *   <li>Manage own subscription(s) to Situation Conduit</li>
 *   <li>Filter channels using hierarchical Name matching in subscription callback</li>
 *   <li>Call {@link #executeWithProtection} when performing actions</li>
 *   <li>Close subscription(s) in {@link #close()}</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * public class AlertActor extends BaseActor {
 *     private Subscription subscription;
 *
 *     public AlertActor(
 *         Conduit<Situations.Situation, Situations.Signal> reporters,
 *         Conduit<Actors.Actor, Actors.Sign> actors,
 *         ...
 *     ) {
 *         super(actors, "alert-actor", 300_000); // 5 minute rate limit
 *
 *         // Actor controls its own subscription
 *         Name clusterHealthName = cortex().name("cluster.health");
 *
 *         this.subscription = reporters.subscribe(cortex().subscriber(
 *             cortex().name("alert-actor-subscriber"),
 *             (subject, registrar) -> {
 *                 // Filter using hierarchical Name
 *                 if (subject.name().equals(clusterHealthName)) {
 *                     registrar.register(sign -> {
 *                         if (sign == Situations.Sign.CRITICAL) {
 *                             handleCritical(subject.name());
 *                         }
 *                     });
 *                 }
 *             }
 *         ));
 *     }
 *
 *     private void handleCritical(Name reporterName) {
 *         String actionKey = "alert:" + reporterName.path();
 *         executeWithProtection(actionKey, () -> {
 *             // Send alerts...
 *         });
 *     }
 *
 *     @Override
 *     public void close() {
 *         if (subscription != null) subscription.close();
 *     }
 * }
 * }</pre>
 *
 * @see AlertActor
 * @see ThrottleActor
 * @since 1.0.0
 */
public abstract class BaseActor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(BaseActor.class);

    private final Conduit<Actors.Actor, Actors.Sign> actors;
    private final String actorName;
    private final long rateLimitIntervalMs;
    private final AtomicLong lastActionTimeMs;
    private final Set<String> activeActions;

    /**
     * Creates a new BaseActor with rate limiting and idempotency support.
     *
     * <p><b>Note</b>: Subclasses are responsible for managing their own subscriptions.
     * BaseActor does NOT subscribe to any sources automatically.
     *
     * @param actors                 Actors conduit for emitting speech acts
     * @param actorName              Name of this actor (e.g., "alert-actor", "throttle-actor")
     * @param rateLimitIntervalMs    Minimum milliseconds between actions (e.g., 300000 = 5 minutes)
     */
    protected BaseActor(
        Conduit<Actors.Actor, Actors.Sign> actors,
        String actorName,
        long rateLimitIntervalMs
    ) {
        this.actors = actors;
        this.actorName = actorName;
        this.rateLimitIntervalMs = rateLimitIntervalMs;
        this.lastActionTimeMs = new AtomicLong(0);
        this.activeActions = ConcurrentHashMap.newKeySet();

        logger.info("BaseActor '{}' initialized with {}ms rate limit", actorName, rateLimitIntervalMs);
    }

    /**
     * Executes an action with rate limiting, idempotency, and Speech Act emission.
     *
     * <p>This method provides the core Actor infrastructure:
     * <ol>
     *   <li>Rate limiting: Checks if sufficient time has passed since last action</li>
     *   <li>Idempotency: Ensures action isn't already executing for this key</li>
     *   <li>Execution: Runs the provided action</li>
     *   <li>Speech Acts: Emits DELIVER on success, DENY on failure or rate limit</li>
     *   <li>Cleanup: Removes action from active set</li>
     * </ol>
     *
     * <p><b>Thread Safety</b>: This method is thread-safe and can be called from
     * subscription callbacks executing on the circuit thread.
     *
     * @param actionKey  Unique key identifying this action (for idempotency)
     * @param action     The action to execute (throwing runnable)
     */
    protected void executeWithProtection(String actionKey, ThrowingRunnable action) {
        // Rate limiting check
        long now = System.currentTimeMillis();
        long timeSinceLastAction = now - lastActionTimeMs.get();

        if (timeSinceLastAction < rateLimitIntervalMs) {
            Actors.Actor actor = actors.percept(cortex().name(actorName));
            actor.deny(); // DENY: Rate limited

            logger.debug("Actor '{}' denied action '{}' (rate limited: {}ms < {}ms)",
                actorName, actionKey, timeSinceLastAction, rateLimitIntervalMs);
            return;
        }

        // Idempotency check
        if (!activeActions.add(actionKey)) {
            Actors.Actor actor = actors.percept(cortex().name(actorName));
            actor.deny(); // DENY: Already executing

            logger.debug("Actor '{}' denied action '{}' (duplicate)", actorName, actionKey);
            return;
        }

        try {
            // Execute action
            action.run();

            // Update last action time
            lastActionTimeMs.set(now);

            // Emit DELIVER sign (success)
            Actors.Actor actor = actors.percept(cortex().name(actorName));
            actor.deliver();

            logger.info("Actor '{}' delivered action '{}'", actorName, actionKey);

        } catch (java.lang.Exception e) {
            // Emit DENY sign (failure)
            Actors.Actor actor = actors.percept(cortex().name(actorName));
            actor.deny();

            logger.error("Actor '{}' failed action '{}': {}", actorName, actionKey, e.getMessage(), e);

        } finally {
            // Cleanup: Remove from active set
            activeActions.remove(actionKey);
        }
    }

    /**
     * Gets the Actor Percept for emitting speech acts.
     *
     * <p>Subclasses can use this to emit additional speech acts beyond DELIVER/DENY
     * (e.g., PROMISE before starting long-running operations).
     *
     * @return The Actor Percept for this actor
     */
    protected Actors.Actor getActor() {
        return actors.percept(cortex().name(actorName));
    }

    /**
     * Gets the actor's name.
     *
     * @return The actor name
     */
    protected String getActorName() {
        return actorName;
    }

    /**
     * Closes this actor and cleans up resources.
     *
     * <p><b>Subclass Implementation</b>: Subclasses MUST override this method
     * to close their subscription(s):
     * <pre>{@code
     * @Override
     * public void close() {
     *     if (subscription != null) subscription.close();
     * }
     * }</pre>
     */
    @Override
    public abstract void close();

    /**
     * Functional interface for actions that may throw exceptions.
     */
    @FunctionalInterface
    protected interface ThrowingRunnable {
        void run() throws java.lang.Exception;
    }
}
