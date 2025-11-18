package io.fullerstack.kafka.coordination.central;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Actors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewPartitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Handles incoming speech act requests from sidecars.
 * <p>
 * Implements distributed coordination using:
 * - <b>Agents API</b>: Make promises about taking action
 * - <b>Actors API</b>: Send speech act responses (ACKNOWLEDGE, PROMISE, DELIVER, DENY)
 * - <b>Kafka AdminClient</b>: Perform actual cluster operations
 *
 * @since 1.0.0
 */
public class RequestHandler {
    private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);

    private final Conduit<Agents.Agent, Agents.Signal> agents;
    private final Conduit<Actors.Actor, Actors.Sign> actors;
    private final AdminClient adminClient;
    private final ResponseSender responseSender;

    /**
     * Creates a new request handler.
     *
     * @param agents central agent conduit (for making promises)
     * @param actors central actor conduit (for speech act responses)
     * @param adminClient Kafka admin client for cluster operations
     * @param responseSender sender for responses back to sidecars
     */
    public RequestHandler(
        Conduit<Agents.Agent, Agents.Signal> agents,
        Conduit<Actors.Actor, Actors.Sign> actors,
        AdminClient adminClient,
        ResponseSender responseSender
    ) {
        this.agents = agents;
        this.actors = actors;
        this.adminClient = adminClient;
        this.responseSender = responseSender;
    }

    /**
     * Handles REQUEST speech act from sidecar.
     * <p>
     * Flow:
     * 1. ACKNOWLEDGE - "I received your request"
     * 2. PROMISE - "I will try to help"
     * 3. DELIVER/DENY - "Here's the result"
     *
     * @param message the request message
     */
    public void handleRequest(Map<String, Object> message) {
        String source = (String) message.get("source");
        String requestType = (String) message.get("requestType");
        String description = (String) message.get("description");

        @SuppressWarnings("unchecked")
        List<String> suggestedActions = (List<String>) message.get("suggestedActions");

        logger.info("[REQUEST] From {}: {} - {}", source, requestType, description);
        logger.debug("[REQUEST] Suggested actions: {}", suggestedActions);

        // Get central actor for responding
        Actors.Actor responder = actors.percept(cortex().name("central.responder." + source));

        // ACKNOWLEDGE the request
        responder.acknowledge();
        responseSender.send(source, "ACKNOWLEDGE", "Request received, processing...");

        // Process based on request type
        switch (requestType) {
            case "SCALE_RESOURCES" -> handleScaleResources(source, message, responder);
            case "INVESTIGATE_BROKER" -> handleInvestigateBroker(source, message, responder);
            case "REBALANCE_PARTITIONS" -> handleRebalancePartitions(source, message, responder);
            default -> {
                logger.warn("[REQUEST] Unknown request type: {} from {}", requestType, source);
                responder.deny();
                responseSender.send(source, "DENY", "Unknown request type: " + requestType);
            }
        }
    }

    private void handleScaleResources(String source, Map<String, Object> message, Actors.Actor responder) {
        // Get central agent for making promise
        Agents.Agent scaler = agents.percept(cortex().name("central.partition-scaler." + source));

        // PROMISE to help (Agents API - Promise Theory)
        scaler.promise(Agents.Dimension.PROMISER);

        // PROMISE speech act (Actors API - Speech Act Theory)
        responder.promise();
        responseSender.send(source, "PROMISE", "Committed to scaling partition replicas");

        // Try to scale (example: scale "orders" topic to 5 partitions)
        String topic = "orders"; // In real system, extract from message
        int newPartitionCount = 5;
        boolean success = scalePartitionReplicas(topic, newPartitionCount);

        if (success) {
            // Agent fulfills promise
            scaler.fulfill(Agents.Dimension.PROMISER);

            // Actor delivers result
            responder.deliver();
            responder.report();
            responseSender.send(source, "DELIVER",
                String.format("Partition replicas scaled: %s now has %d partitions", topic, newPartitionCount));
        } else {
            // Agent breaches promise
            scaler.breach(Agents.Dimension.PROMISER);

            // Actor denies and explains
            responder.deny();
            responder.explain();
            responseSender.send(source, "DENY", "Cannot scale replicas - quota exceeded or topic not found");
        }
    }

    private void handleInvestigateBroker(String source, Map<String, Object> message, Actors.Actor responder) {
        // PROMISE to investigate
        responder.promise();
        responseSender.send(source, "PROMISE", "Will investigate broker health");

        // For now, just acknowledge and explain not implemented
        responder.acknowledge();
        responder.explain();
        responseSender.send(source, "EXPLAIN", "Broker investigation not yet implemented - monitoring in progress");
    }

    private void handleRebalancePartitions(String source, Map<String, Object> message, Actors.Actor responder) {
        // PROMISE to rebalance
        responder.promise();
        responseSender.send(source, "PROMISE", "Will attempt partition rebalancing");

        // For now, acknowledge and explain
        responder.deny();
        responder.explain();
        responseSender.send(source, "EXPLAIN", "Partition rebalancing requires manual approval - escalating to operator");
    }

    private boolean scalePartitionReplicas(String topic, int newPartitionCount) {
        try {
            logger.info("[ACTION] Scaling topic '{}' to {} partitions", topic, newPartitionCount);

            adminClient.createPartitions(Map.of(
                topic, NewPartitions.increaseTo(newPartitionCount)
            )).all().get(30, TimeUnit.SECONDS);

            logger.info("[ACTION] ✅ Successfully scaled '{}' to {} partitions", topic, newPartitionCount);
            return true;
        } catch (java.lang.Exception e) {
            logger.error("[ACTION] ❌ Failed to scale partitions for topic '{}'", topic, e);
            return false;
        }
    }

    /**
     * Handles REPORT speech act from sidecar.
     * <p>
     * This is informational only (audit trail) - no action required.
     *
     * @param message the report message
     */
    public void handleReport(Map<String, Object> message) {
        String source = (String) message.get("source");
        String information = (String) message.get("information");

        // Filter heartbeats to TRACE level (avoid log spam)
        if ("HEARTBEAT".equals(information)) {
            logger.trace("[HEARTBEAT] From {}", source);
            // Registry already updated by SidecarDiscoveryListener
            return;
        }

        // Log other reports at INFO level
        logger.info("[REPORT] From {}: {}", source, information);

        // Just log for now (could store in database, send to monitoring, etc.)
        // No response needed for REPORT (it's informational, not a request)
    }
}
