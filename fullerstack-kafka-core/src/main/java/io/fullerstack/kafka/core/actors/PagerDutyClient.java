package io.fullerstack.kafka.core.actors;

/**
 * PagerDuty API client for sending alerts.
 *
 * <p>This interface abstracts PagerDuty Event API v2 integration, allowing
 * AlertActor to send incident alerts without direct API coupling.
 *
 * <h3>Implementation Notes:</h3>
 * <ul>
 *   <li>Use PagerDuty Event API v2 (https://v2.developer.pagerduty.com/docs/events-api-v2)</li>
 *   <li>Requires integration key (routing_key) per service</li>
 *   <li>Supports severity levels: critical, error, warning, info</li>
 *   <li>Should implement retry logic for transient failures</li>
 * </ul>
 *
 * <h3>Example Implementation:</h3>
 * <pre>{@code
 * public class PagerDutyClientImpl implements PagerDutyClient {
 *     private final String apiUrl = "https://events.pagerduty.com/v2/enqueue";
 *     private final HttpClient httpClient;
 *
 *     public void sendAlert(String routingKey, String summary, String severity) throws Exception {
 *         // POST to PagerDuty Events API
 *         // {
 *         //   "routing_key": routingKey,
 *         //   "event_action": "trigger",
 *         //   "payload": {
 *         //     "summary": summary,
 *         //     "severity": severity,
 *         //     "source": "kafka-obs"
 *         //   }
 *         // }
 *     }
 * }
 * }</pre>
 *
 * @see AlertActor
 * @since 1.0.0
 */
public interface PagerDutyClient {

    /**
     * Sends a PagerDuty alert incident.
     *
     * @param routingKey  PagerDuty integration key (identifies destination service)
     * @param summary     Alert summary text (will appear in incident title)
     * @param severity    Alert severity level (critical, error, warning, info)
     * @throws java.lang.Exception if alert fails (network error, API error, etc.)
     */
    void sendAlert(String routingKey, String summary, String severity) throws java.lang.Exception;
}
