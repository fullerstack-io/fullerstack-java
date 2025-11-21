package io.fullerstack.kafka.core.coordination;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for human interaction in SUPERVISED and ADVISORY modes.
 * <p>
 * Implementations provide different channels for human injection:
 * <ul>
 *   <li><b>CLI</b> - Terminal-based interface (priority 1)</li>
 *   <li><b>HTTP</b> - Web dashboard (future)</li>
 *   <li><b>Slack</b> - Slack bot integration (future)</li>
 * </ul>
 *
 * <h3>Usage Pattern:</h3>
 * <pre>{@code
 * HumanInterface cli = new CliHumanInterface(actors);
 *
 * CompletableFuture<HumanResponse> response = cli.requestApproval(
 *     new DecisionRequest(
 *         "throttle.producer",
 *         Situations.Sign.CRITICAL,
 *         Duration.ofSeconds(3)
 *     )
 * );
 *
 * response.thenAccept(humanResponse -> {
 *     if (humanResponse.approved()) {
 *         // Execute action
 *     } else if (humanResponse.vetoed()) {
 *         // Cancel action
 *     }
 * });
 * }</pre>
 */
public interface HumanInterface {

    /**
     * Request human approval for an action.
     * <p>
     * <b>SUPERVISED mode:</b> Blocks action until human responds
     * <b>ADVISORY mode:</b> Shows pending action, auto-executes if no response
     *
     * @param request decision request with action details and timeout
     * @return future that completes with human response or timeout
     */
    CompletableFuture<HumanResponse> requestApproval(DecisionRequest request);
}
