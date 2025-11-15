package io.fullerstack.kafka.core.coordination;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Represents a single injection point where human can inject approval, veto, or override.
 * <p>
 * Used in ADVISORY mode to create a time window (3-30 seconds) where:
 * <ul>
 *   <li>Agent is ABOUT to execute autonomous action</li>
 *   <li>Actor explains to human what's happening</li>
 *   <li>Human CAN inject (veto, pause, override)</li>
 *   <li>If no injection → Agent proceeds autonomously</li>
 * </ul>
 *
 * <h3>Lifecycle:</h3>
 * <pre>
 * 1. Register injection point with timeout
 * 2. Display to human (via CLI/HTTP/Slack)
 * 3. Wait for timeout OR human injection
 * 4. Execute:
 *    - If human injected → call onHumanInjection callback
 *    - If timeout → call autonomousAction runnable
 * </pre>
 */
public class InjectionPoint {
    private static final Logger logger = LoggerFactory.getLogger(InjectionPoint.class);

    private final String actionId;
    private final Duration timeout;
    private final Runnable autonomousAction;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean executed = new AtomicBoolean(false);
    private BiConsumer<HumanResponse.Type, String> injectionCallback;
    private ScheduledFuture<?> timeoutFuture;

    /**
     * Creates a new injection point.
     *
     * @param actionId unique identifier for this action
     * @param timeout how long to wait for human injection
     * @param autonomousAction action to execute if no human injection
     * @param scheduler scheduler for timeout handling
     */
    public InjectionPoint(
        String actionId,
        Duration timeout,
        Runnable autonomousAction,
        ScheduledExecutorService scheduler
    ) {
        this.actionId = Objects.requireNonNull(actionId, "actionId cannot be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout cannot be null");
        this.autonomousAction = Objects.requireNonNull(autonomousAction, "autonomousAction cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");

        // Schedule auto-execution after timeout
        scheduleTimeout();
    }

    /**
     * Schedule auto-execution after timeout (if no human injection).
     */
    private void scheduleTimeout() {
        this.timeoutFuture = scheduler.schedule(() -> {
            if (executed.compareAndSet(false, true)) {
                logger.info("[INJECTION] Timeout reached for {} - executing autonomously", actionId);
                autonomousAction.run();
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Register callback for human injection.
     * <p>
     * Called when human vetoes, pauses, or overrides the action.
     *
     * @param callback callback accepting (injectionType, reason)
     */
    public void onHumanInjection(BiConsumer<HumanResponse.Type, String> callback) {
        this.injectionCallback = callback;
    }

    /**
     * Handle human injection (veto, pause, override).
     * <p>
     * Cancels timeout and calls injection callback.
     *
     * @param type injection type (VETO, PAUSE, OVERRIDE)
     * @param reason reason provided by human
     * @return true if injection was accepted (not already executed)
     */
    public boolean inject(HumanResponse.Type type, String reason) {
        if (executed.compareAndSet(false, true)) {
            // Cancel timeout
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }

            logger.info("[INJECTION] Human injected {} for {} - reason: {}", type, actionId, reason);

            // Call injection callback
            if (injectionCallback != null) {
                injectionCallback.accept(type, reason);
            }

            return true;  // Injection accepted
        } else {
            logger.warn("[INJECTION] Human injection rejected for {} - already executed", actionId);
            return false;  // Already executed, injection too late
        }
    }

    public String getActionId() {
        return actionId;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public boolean isExecuted() {
        return executed.get();
    }
}
