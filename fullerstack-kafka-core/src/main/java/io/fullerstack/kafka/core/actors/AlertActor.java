package io.fullerstack.kafka.core.actors;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Actor that sends PagerDuty, Slack, and Microsoft Teams alerts for critical cluster health issues.
 *
 * <p>AlertActor extends {@link BaseActor} to provide automated alerting when
 * the ClusterHealthReporter emits CRITICAL urgency signs. This implements the
 * ACT phase of the OODA loop for incident notification.
 *
 * <h3>Percept-Based Architecture:</h3>
 * <pre>
 * ClusterHealthReporter (Percept) → emits Reporters.Sign.CRITICAL
 *                                 ↓
 *                     AlertActor subscribes & filters
 *                                 ↓
 *                  Send PagerDuty + Slack + Teams
 *                                 ↓
 *                Actor (Percept) → emits Actors.Sign.DELIVER or DENY
 * </pre>
 *
 * <h3>Alert Targets:</h3>
 * <ul>
 *   <li><b>PagerDuty</b>: Primary alerting (creates incident, pages on-call)</li>
 *   <li><b>Slack</b>: Secondary notification (team visibility, best-effort)</li>
 *   <li><b>Microsoft Teams</b>: Secondary notification (enterprise teams, best-effort)</li>
 * </ul>
 *
 * <h3>Rate Limiting:</h3>
 * Maximum 1 alert per 5 minutes (300 seconds) to prevent alert fatigue during
 * sustained outages. Subsequent CRITICAL signs within the rate limit window
 * will emit DENY speech acts.
 *
 * <h3>Error Handling:</h3>
 * <ul>
 *   <li>PagerDuty failure → Throws exception, emits DENY sign</li>
 *   <li>Slack failure → Logs warning, continues (Slack is secondary)</li>
 *   <li>Teams failure → Logs warning, continues (Teams is secondary)</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Create circuits
 * Circuit reporterCircuit = cortex().circuit(cortex().name("reporters"));
 * Conduit<Reporters.Reporter, Reporters.Sign> reporters = reporterCircuit.conduit(
 *     cortex().name("reporters"),
 *     Reporters::composer
 * );
 *
 * Circuit actorCircuit = cortex().circuit(cortex().name("actors"));
 * Conduit<Actors.Actor, Actors.Sign> actors = actorCircuit.conduit(
 *     cortex().name("actors"),
 *     Actors::composer
 * );
 *
 * // Create clients (implementation-specific)
 * PagerDutyClient pagerDuty = new PagerDutyClientImpl(apiKey);
 * SlackClient slack = new SlackClientImpl(slackWebhookUrl);
 * TeamsClient teams = new TeamsClientImpl(teamsWebhookUrl);
 *
 * // Create AlertActor
 * AlertActor alertActor = new AlertActor(
 *     reporters,
 *     actors,
 *     pagerDuty,
 *     slack,
 *     teams,
 *     "prod-kafka-cluster"
 * );
 *
 * // Actor automatically subscribes to "cluster-health" reporter
 * // and sends alerts on CRITICAL signs
 * }</pre>
 *
 * @see BaseActor
 * @see PagerDutyClient
 * @see SlackClient
 * @see TeamsClient
 * @since 1.0.0
 */
public class AlertActor extends BaseActor {

    private static final Logger logger = LoggerFactory.getLogger(AlertActor.class);

    /**
     * Rate limit: 1 alert per 5 minutes (300 seconds).
     * Prevents alert storms during sustained outages.
     */
    private static final long RATE_LIMIT_MS = 300_000; // 5 minutes

    private final PagerDutyClient pagerDutyClient;
    private final SlackClient slackClient;
    private final TeamsClient teamsClient;
    private final String clusterName;
    private final Subscription subscription;

    /**
     * Creates a new AlertActor.
     *
     * @param reporters        Reporters conduit to subscribe to
     * @param actors           Actors conduit for emitting speech acts
     * @param pagerDutyClient  PagerDuty API client
     * @param slackClient      Slack API client
     * @param teamsClient      Microsoft Teams API client
     * @param clusterName      Name of Kafka cluster (included in alerts)
     */
    public AlertActor(
        Conduit<Reporters.Reporter, Reporters.Sign> reporters,
        Conduit<Actors.Actor, Actors.Sign> actors,
        PagerDutyClient pagerDutyClient,
        SlackClient slackClient,
        TeamsClient teamsClient,
        String clusterName
    ) {
        super(actors, "alert-actor", RATE_LIMIT_MS);

        this.pagerDutyClient = pagerDutyClient;
        this.slackClient = slackClient;
        this.teamsClient = teamsClient;
        this.clusterName = clusterName;

        // Subscribe to cluster health reporter
        // Hierarchical pattern: "cluster.health"
        Name clusterHealthName = cortex().name("cluster.health");

        this.subscription = reporters.subscribe(cortex().subscriber(
            cortex().name("alert-actor-subscriber"),
            (Subject<Channel<Reporters.Sign>> subject, Registrar<Reporters.Sign> registrar) -> {
                // Filter: Only register pipe for cluster.health channel
                if (subject.name().equals(clusterHealthName)) {
                    registrar.register(sign -> {
                        if (sign == Reporters.Sign.CRITICAL) {
                            handleClusterHealthCritical(subject.name());
                        }
                    });
                }
            }
        ));

        logger.info("AlertActor initialized for cluster '{}' with 5-minute rate limit", clusterName);
    }

    /**
     * Handles CRITICAL signs from ClusterHealthReporter.
     *
     * @param reporterName Name of the reporter that emitted CRITICAL (cluster-health)
     */
    private void handleClusterHealthCritical(Name reporterName) {
        String actionKey = "alert:" + reporterName.path();

        executeWithProtection(actionKey, () -> {
            String timestamp = Instant.now().toString();
            String message = String.format(
                "🚨 CRITICAL: Kafka cluster '%s' health degraded at %s",
                clusterName,
                timestamp
            );

            logger.warn("Cluster '{}' CRITICAL condition detected, sending alerts", clusterName);

            // Send PagerDuty alert (primary, must succeed)
            try {
                pagerDutyClient.sendAlert(
                    "kafka-cluster-health",
                    message,
                    "critical"
                );
                logger.info("PagerDuty alert sent for cluster '{}' at {}", clusterName, timestamp);
            } catch (java.lang.Exception e) {
                logger.error("FAILED to send PagerDuty alert for cluster '{}': {}",
                    clusterName, e.getMessage(), e);
                // Propagate exception to trigger DENY sign
                throw e;
            }

            // Send Slack alert (secondary, best-effort)
            try {
                slackClient.sendMessage(
                    "#kafka-alerts",
                    message
                );
                logger.info("Slack alert sent for cluster '{}' at {}", clusterName, timestamp);
            } catch (java.lang.Exception e) {
                // Slack failure is non-fatal - log warning and continue
                logger.warn("Failed to send Slack alert for cluster '{}': {} (continuing)",
                    clusterName, e.getMessage());
                // Do NOT throw - PagerDuty is primary, Slack is secondary
            }

            // Send Teams alert (secondary, best-effort)
            try {
                teamsClient.sendMessage(
                    "Kafka Alerts",
                    message
                );
                logger.info("Teams alert sent for cluster '{}' at {}", clusterName, timestamp);
            } catch (java.lang.Exception e) {
                // Teams failure is non-fatal - log warning and continue
                logger.warn("Failed to send Teams alert for cluster '{}': {} (continuing)",
                    clusterName, e.getMessage());
                // Do NOT throw - PagerDuty is primary, Teams is secondary
            }
        });
    }

    @Override
    public void close() {
        if (subscription != null) {
            subscription.close();
        }
        logger.info("AlertActor closed");
    }
}
