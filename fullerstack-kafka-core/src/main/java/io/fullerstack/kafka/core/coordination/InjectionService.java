package io.fullerstack.kafka.core.coordination;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Manages injection points for selective human intervention in ADVISORY mode.
 * <p>
 * <b>Key Concept:</b> "Selective Injection" - Human has ultimate authority but doesn't want to
 * make ALL decisions. System acts autonomously by default, but human CAN inject within time windows.
 * <p>
 * <b>Responsibilities:</b>
 * <ul>
 *   <li>Register injection points with timeout windows (3-30 seconds)</li>
 *   <li>Route human responses to appropriate callbacks</li>
 *   <li>Auto-execute if human doesn't inject within window</li>
 *   <li>Track injection statistics (% auto-execute vs human inject)</li>
 * </ul>
 *
 * <h3>ADVISORY Mode Pattern:</h3>
 * <pre>
 * STEP 1: Actor explains → "Buffer at 95%, I'm throttling in 3 seconds"
 * STEP 2: Register injection point
 * STEP 3: Wait for timeout OR human injection
 * STEP 4a: If timeout → Agent executes autonomously
 * STEP 4b: If human injects → Handle veto/pause/override
 * </pre>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * InjectionService injectionService = new InjectionService(cliInterface);
 *
 * // Register injection point
 * InjectionPoint point = injectionService.register(
 *     "throttle.producer-1",
 *     Duration.ofSeconds(3),
 *     () -> {
 *         // This runs if human DOESN'T inject
 *         agent.promise();
 *         throttleProducer();
 *         agent.fulfill();
 *     }
 * );
 *
 * // Register callback for human injection
 * point.onHumanInjection((type, reason) -> {
 *     switch (type) {
 *         case VETO -> {
 *             human.deny();
 *             agent.retract();
 *         }
 *         case OVERRIDE -> {
 *             human.command();
 *             executeAlternative(reason);
 *         }
 *     }
 * });
 * }</pre>
 */
public class InjectionService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(InjectionService.class);

    private final HumanInterface humanInterface;
    private final Map<String, InjectionPoint> activeInjections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    // Statistics tracking
    private long totalInjectionPoints = 0;
    private long humanInjections = 0;
    private long autoExecutions = 0;

    /**
     * Creates a new injection service.
     *
     * @param humanInterface interface for human interaction (CLI, HTTP, Slack)
     */
    public InjectionService(HumanInterface humanInterface) {
        this.humanInterface = Objects.requireNonNull(humanInterface, "humanInterface cannot be null");
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "injection-service");
            t.setDaemon(true);
            return t;
        });

        logger.info("InjectionService initialized");
    }

    /**
     * Register injection point with timeout window (ADVISORY mode).
     * <p>
     * Creates a window where human CAN inject, but action proceeds autonomously if no injection.
     *
     * @param actionId unique identifier for this action
     * @param injectionWindow how long to wait for human injection (3-30 seconds)
     * @param autonomousAction action to execute if no human injection
     * @return injection point for registering callbacks
     */
    public InjectionPoint register(
        String actionId,
        Duration injectionWindow,
        Runnable autonomousAction
    ) {
        // Wrap autonomous action to track statistics
        Runnable trackedAction = () -> {
            try {
                autonomousAction.run();
                autoExecutions++;
                logger.info("[STATS] Auto-execution: {} (total: {}, rate: {}%)",
                    actionId, autoExecutions, getAutoExecutionRate());
            } finally {
                activeInjections.remove(actionId);
            }
        };

        InjectionPoint injectionPoint = new InjectionPoint(
            actionId,
            injectionWindow,
            trackedAction,
            scheduler
        );

        activeInjections.put(actionId, injectionPoint);
        totalInjectionPoints++;

        logger.debug("[INJECTION] Registered injection point: {} (window: {})",
            actionId, injectionWindow);

        return injectionPoint;
    }

    /**
     * Request human approval (SUPERVISED mode).
     * <p>
     * Blocks action until human responds - NO auto-execution.
     *
     * @param request decision request with action details
     * @param timeout maximum wait time for human response
     * @return human response (approved, vetoed, etc.)
     */
    public HumanResponse requestApproval(DecisionRequest request, Duration timeout) {
        try {
            CompletableFuture<HumanResponse> future = humanInterface.requestApproval(request);

            HumanResponse response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (response.approved()) {
                logger.info("[SUPERVISED] Human approved: {}", request.action());
            } else if (response.vetoed()) {
                logger.warn("[SUPERVISED] Human vetoed: {} - reason: {}",
                    request.action(), response.reason());
                humanInjections++;
            }

            return response;

        } catch (TimeoutException e) {
            logger.warn("[SUPERVISED] Human approval timeout: {}", request.action());
            return HumanResponse.timeout();

        } catch (Exception e) {
            logger.error("[SUPERVISED] Human approval failed: {}", request.action(), e);
            return HumanResponse.timeout();
        }
    }

    /**
     * Handle human injection for a specific action.
     * <p>
     * Called when human uses CLI/HTTP/Slack to inject veto/pause/override.
     *
     * @param actionId action identifier
     * @param type injection type (VETO, PAUSE, OVERRIDE)
     * @param reason reason provided by human
     * @return true if injection was accepted (not already executed)
     */
    public boolean handleHumanInjection(String actionId, HumanResponse.Type type, String reason) {
        InjectionPoint point = activeInjections.get(actionId);

        if (point == null) {
            logger.warn("[INJECTION] No active injection point for {}", actionId);
            return false;
        }

        boolean accepted = point.inject(type, reason);

        if (accepted) {
            humanInjections++;
            activeInjections.remove(actionId);

            logger.info("[STATS] Human injection: {} (total: {}, rate: {}%)",
                actionId, humanInjections, getHumanInjectionRate());
        }

        return accepted;
    }

    /**
     * Get auto-execution rate (percentage of actions that run without human intervention).
     *
     * @return percentage (0-100)
     */
    public double getAutoExecutionRate() {
        if (totalInjectionPoints == 0) return 0.0;
        return (double) autoExecutions / totalInjectionPoints * 100.0;
    }

    /**
     * Get human injection rate (percentage of actions where human intervened).
     *
     * @return percentage (0-100)
     */
    public double getHumanInjectionRate() {
        if (totalInjectionPoints == 0) return 0.0;
        return (double) humanInjections / totalInjectionPoints * 100.0;
    }

    /**
     * Get injection statistics.
     *
     * @return statistics map
     */
    public Map<String, Long> getStatistics() {
        return Map.of(
            "total", totalInjectionPoints,
            "auto_executions", autoExecutions,
            "human_injections", humanInjections,
            "active", (long) activeInjections.size()
        );
    }

    @Override
    public void close() {
        logger.info("Shutting down injection service");

        logger.info("[STATS] Final injection statistics:");
        logger.info("  Total injection points: {}", totalInjectionPoints);
        logger.info("  Auto-executions: {} ({}%)", autoExecutions, getAutoExecutionRate());
        logger.info("  Human injections: {} ({}%)", humanInjections, getHumanInjectionRate());

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

        logger.info("Injection service stopped");
    }
}
