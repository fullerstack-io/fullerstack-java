package io.fullerstack.ocpp.agents;

import io.humainary.substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.agents.Agents;
import io.humainary.substrates.ext.serventis.ext.agents.Agents.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Base class for OCPP autonomous agents (Layer 4 - ACT - Agents).
 * <p>
 * Implements <b>Promise Theory</b> (Mark Burgess) for autonomous self-regulation.
 * Agents act independently based on observed signals, with NO human intervention.
 * <p>
 * Provides core protection mechanisms:
 * - Rate limiting: Prevent rapid-fire actions
 * - Idempotency: Prevent duplicate actions
 * - Promise semantics: promise() → fulfill() or breach()
 * </p>
 * <p>
 * Pattern follows Kafka ConsumerSelfRegulator and ProducerSelfRegulator.
 * </p>
 *
 * <h3>Why Agents API (not Actors API)?</h3>
 * <ul>
 *   <li><b>Speed</b>: Autonomous action within milliseconds (no human approval needed)</li>
 *   <li><b>Closed-loop</b>: System regulates itself based on own signals</li>
 *   <li><b>No bottleneck</b>: Scales to thousands of chargers without human coordination</li>
 *   <li><b>Promise semantics</b>: Clear accountability (fulfill vs breach)</li>
 * </ul>
 */
public abstract class BaseAgent implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(BaseAgent.class);

    protected final Conduit<Agent, Agents.Signal> agents;
    protected final String agentName;
    private final long rateLimitIntervalMs;
    private final AtomicLong lastActionTimeMs;
    private final Set<String> activeActions;

    protected BaseAgent(
        Conduit<Agent, Agents.Signal> agents,
        String agentName,
        long rateLimitIntervalMs
    ) {
        this.agents = agents;
        this.agentName = agentName;
        this.rateLimitIntervalMs = rateLimitIntervalMs;
        this.lastActionTimeMs = new AtomicLong(0);
        this.activeActions = ConcurrentHashMap.newKeySet();

        logger.info("Initialized agent: {} with rate limit {} ms (Agents API - autonomous)",
            agentName, rateLimitIntervalMs);
    }

    /**
     * Execute an autonomous action with rate limiting, idempotency, and promise semantics.
     * <p>
     * Promise Theory pattern:
     * 1. Agent PROMISES to execute action
     * 2. Agent attempts execution
     * 3. Agent FULFILLS promise on success
     * 4. Agent BREACHES promise on failure
     *
     * @param actionKey unique key for this action (for idempotency)
     * @param action the action to execute
     */
    protected void executeWithProtection(String actionKey, ThrowingRunnable action) {
        long now = System.currentTimeMillis();

        // 1. Rate limiting check
        long timeSinceLastAction = now - lastActionTimeMs.get();
        if (timeSinceLastAction < rateLimitIntervalMs) {
            logger.debug("[AGENTS] Agent {} rate limited (last action {} ms ago)",
                agentName, timeSinceLastAction);
            emitBreach(actionKey, "rate_limited");
            return;
        }

        // 2. Idempotency check
        if (!activeActions.add(actionKey)) {
            logger.debug("[AGENTS] Agent {} duplicate action blocked: {}", agentName, actionKey);
            emitBreach(actionKey, "duplicate");
            return;
        }

        Agent agent = agents.channel(cortex().name(agentName + "." + actionKey));

        try {
            // 3. Promise to execute action (PROMISER perspective)
            agent.promise();
            logger.info("[AGENTS] Agent {} promised to execute: {}", agentName, actionKey);

            // 4. Execute action
            action.run();

            // 5. Update rate limit timestamp
            lastActionTimeMs.set(now);

            // 6. Fulfill promise (success)
            agent.fulfill();
            logger.info("[AGENTS] Agent {} fulfilled promise: {}", agentName, actionKey);

        } catch (Exception e) {
            logger.error("[AGENTS] Agent {} action failed: {} - {}", agentName, actionKey, e.getMessage(), e);

            // 7. Breach promise (failure)
            agent.breach();
            logger.warn("[AGENTS] Agent {} breached promise: {} (reason: {})",
                agentName, actionKey, e.getMessage());

        } finally {
            // 8. Remove from active set
            activeActions.remove(actionKey);
        }
    }

    /**
     * Emit BREACH (promise broken without attempting).
     * Used when rate limiting or idempotency prevents action attempt.
     */
    private void emitBreach(String actionKey, String reason) {
        Agent agent = agents.channel(cortex().name(agentName + "." + actionKey));
        agent.breach();
        logger.debug("[AGENTS] Agent {} breached (preemptively) for {} (reason: {})",
            agentName, actionKey, reason);
    }

    @Override
    public void close() {
        logger.info("Closing agent: {}", agentName);
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
