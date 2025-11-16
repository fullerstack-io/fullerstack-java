package io.fullerstack.ocpp.actors;

import io.humainary.substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Actors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Base class for OCPP actors (Layer 4 - ACT).
 * <p>
 * Provides core protection mechanisms:
 * - Rate limiting: Prevent rapid-fire actions
 * - Idempotency: Prevent duplicate actions
 * - Speech acts: Emit DELIVER/DENY signals for observability
 * </p>
 * <p>
 * Pattern follows the Kafka BaseActor implementation.
 * </p>
 */
public abstract class BaseActor implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(BaseActor.class);

    protected final Conduit<Actors.Actor, Actors.Sign> actors;
    protected final String actorName;
    private final long rateLimitIntervalMs;
    private final AtomicLong lastActionTimeMs;
    private final Set<String> activeActions;

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

        logger.info("Initialized actor: {} with rate limit {} ms", actorName, rateLimitIntervalMs);
    }

    /**
     * Execute an action with rate limiting and idempotency protection.
     *
     * @param actionKey unique key for this action (for idempotency)
     * @param action the action to execute
     */
    protected void executeWithProtection(String actionKey, ThrowingRunnable action) {
        long now = System.currentTimeMillis();

        // 1. Rate limiting check
        long timeSinceLastAction = now - lastActionTimeMs.get();
        if (timeSinceLastAction < rateLimitIntervalMs) {
            logger.debug("Actor {} rate limited (last action {} ms ago)",
                actorName, timeSinceLastAction);
            emitDeny(actionKey, "rate_limited");
            return;
        }

        // 2. Idempotency check
        if (!activeActions.add(actionKey)) {
            logger.debug("Actor {} duplicate action blocked: {}", actorName, actionKey);
            emitDeny(actionKey, "duplicate");
            return;
        }

        try {
            // 3. Execute action
            logger.info("Actor {} executing action: {}", actorName, actionKey);
            action.run();

            // 4. Update rate limit timestamp
            lastActionTimeMs.set(now);

            // 5. Emit DELIVER speech act (success)
            emitDeliver(actionKey);

        } catch (Exception e) {
            logger.error("Actor {} action failed: {} - {}", actorName, actionKey, e.getMessage(), e);
            // 6. Emit DENY speech act (failure)
            emitDeny(actionKey, "failed: " + e.getMessage());

        } finally {
            // 7. Remove from active set
            activeActions.remove(actionKey);
        }
    }

    /**
     * Emit DELIVER speech act (action succeeded).
     */
    private void emitDeliver(String actionKey) {
        Actors.Actor actor = actors.percept(cortex().name(actorName + "." + actionKey));
        actor.deliver();
        logger.debug("Actor {} emitted DELIVER for {}", actorName, actionKey);
    }

    /**
     * Emit DENY speech act (action blocked or failed).
     */
    private void emitDeny(String actionKey, String reason) {
        Actors.Actor actor = actors.percept(cortex().name(actorName + "." + actionKey));
        actor.deny();
        logger.debug("Actor {} emitted DENY for {} (reason: {})", actorName, actionKey, reason);
    }

    @Override
    public void close() {
        logger.info("Closing actor: {}", actorName);
        activeActions.clear();
    }

    /**
     * Functional interface for throwable runnables.
     */
    @FunctionalInterface
    protected interface ThrowingRunnable {
        void run() throws Exception;
    }
}
