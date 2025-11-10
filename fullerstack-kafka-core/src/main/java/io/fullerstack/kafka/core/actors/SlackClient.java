package io.fullerstack.kafka.core.actors;

/**
 * Slack API client for sending messages.
 *
 * <p>This interface abstracts Slack Incoming Webhooks integration, allowing
 * AlertActor to send channel messages without direct API coupling.
 *
 * <h3>Implementation Notes:</h3>
 * <ul>
 *   <li>Use Slack Incoming Webhooks (https://api.slack.com/messaging/webhooks)</li>
 *   <li>Requires webhook URL per channel/workspace</li>
 *   <li>Supports markdown formatting for rich messages</li>
 *   <li>Should implement retry logic for transient failures</li>
 * </ul>
 *
 * <h3>Example Implementation:</h3>
 * <pre>{@code
 * public class SlackClientImpl implements SlackClient {
 *     private final String webhookUrl;
 *     private final HttpClient httpClient;
 *
 *     public void sendMessage(String channel, String message) throws Exception {
 *         // POST to Slack webhook URL
 *         // {
 *         //   "channel": channel,
 *         //   "text": message,
 *         //   "username": "Kafka Observability"
 *         // }
 *     }
 * }
 * }</pre>
 *
 * @see AlertActor
 * @since 1.0.0
 */
public interface SlackClient {

    /**
     * Sends a message to a Slack channel.
     *
     * @param channel  Channel name (e.g., "#kafka-alerts") or webhook-configured default
     * @param message  Message text (supports Slack markdown formatting)
     * @throws java.lang.Exception if message fails (network error, API error, etc.)
     */
    void sendMessage(String channel, String message) throws java.lang.Exception;
}
