package io.fullerstack.kafka.core.actors;

/**
 * Microsoft Teams API client for sending messages.
 *
 * <p>This interface abstracts Microsoft Teams Incoming Webhooks integration, allowing
 * AlertActor to send channel messages without direct API coupling.
 *
 * <h3>Implementation Notes:</h3>
 * <ul>
 *   <li>Use Teams Incoming Webhooks (https://docs.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook)</li>
 *   <li>Requires webhook URL per channel/team</li>
 *   <li>Supports Adaptive Cards for rich formatting</li>
 *   <li>Supports MessageCard format (legacy) for simple messages</li>
 *   <li>Should implement retry logic for transient failures</li>
 * </ul>
 *
 * <h3>Example Implementation:</h3>
 * <pre>{@code
 * public class TeamsClientImpl implements TeamsClient {
 *     private final String webhookUrl;
 *     private final HttpClient httpClient;
 *
 *     public void sendMessage(String channel, String message) throws Exception {
 *         // POST to Teams webhook URL
 *         // MessageCard format (simple):
 *         // {
 *         //   "@type": "MessageCard",
 *         //   "@context": "http://schema.org/extensions",
 *         //   "summary": "Kafka Alert",
 *         //   "themeColor": "FF0000",
 *         //   "title": "Kafka Cluster Alert",
 *         //   "text": message
 *         // }
 *         //
 *         // Adaptive Card format (rich):
 *         // {
 *         //   "type": "message",
 *         //   "attachments": [{
 *         //     "contentType": "application/vnd.microsoft.card.adaptive",
 *         //     "content": {
 *         //       "type": "AdaptiveCard",
 *         //       "body": [{
 *         //         "type": "TextBlock",
 *         //         "text": message,
 *         //         "color": "attention"
 *         //       }]
 *         //     }
 *         //   }]
 *         // }
 *     }
 * }
 * }</pre>
 *
 * @see AlertActor
 * @since 1.0.0
 */
public interface TeamsClient {

    /**
     * Sends a message to a Microsoft Teams channel.
     *
     * @param channel  Channel name (for logging/context, actual target determined by webhook URL)
     * @param message  Message text (will be formatted as MessageCard or Adaptive Card)
     * @throws java.lang.Exception if message fails (network error, API error, etc.)
     */
    void sendMessage(String channel, String message) throws java.lang.Exception;
}
