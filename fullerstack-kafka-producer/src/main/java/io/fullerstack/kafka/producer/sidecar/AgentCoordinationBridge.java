package io.fullerstack.kafka.producer.sidecar;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Actors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Agent Coordination Bridge: Promise Theory + Speech Act Theory for Distributed Coordination.
 * <p>
 * This class implements the <b>agent-to-agent coordination pattern</b> where sidecars are autonomous
 * agents that make promises about self-regulation, and request help from central agents using
 * speech acts when they cannot fulfill their promises.
 *
 * <h3>Promise Theory (Mark Burgess) - Agents API:</h3>
 * <ul>
 *   <li><b>Sidecar Agent PROMISES</b> to self-regulate (throttle when buffer degraded)</li>
 *   <li><b>Agent FULFILLS</b> promise → No help needed (99% of cases)</li>
 *   <li><b>Agent BREACHES</b> promise → Request help from central (1% of cases)</li>
 * </ul>
 *
 * <h3>Speech Act Theory (John Searle) - Actors API:</h3>
 * <ul>
 *   <li><b>DIRECTIVE</b>: Request/command for action ("Please help me scale replicas")</li>
 *   <li><b>COMMISSIVE</b>: Commitment to future action ("I will provide resources")</li>
 *   <li><b>ASSERTIVE</b>: Inform about state of affairs ("FYI: Buffer pressure resolved")</li>
 *   <li><b>DECLARATIVE</b>: Make something true ("I declare this partition leader")</li>
 *   <li><b>EXPRESSIVE</b>: Apologize/thank ("Sorry, cannot help - quota exceeded")</li>
 * </ul>
 *
 * <h3>Communication Pattern (NOT State Broadcasting):</h3>
 * <pre>
 * Sidecar Agent:
 *   promise(PROMISER) → "I promise to regulate buffer"
 *        ↓
 *   tryLocalThrottle()
 *        ↓
 *   if (success):
 *     fulfill(PROMISER) → "I kept my promise"
 *     ❌ NO communication to central (self-sufficient)
 *
 *   if (failure):
 *     breach(PROMISER) → "I broke my promise"
 *     ✅ Actor.directive("REQUEST: Scale partition replicas")
 *              ↓
 *         Speech Act (action request, not state broadcast)
 *              ↓
 *     Central Agent receives directive
 *              ↓
 *     Central Agent promises to help
 *              ↓
 *     Central Agent fulfills or breaches promise
 *              ↓
 *     Central Actor responds:
 *       - directive("INFORM: Replicas scaled")
 *       - OR directive("APOLOGIZE: Cannot scale (quota)")
 * </pre>
 *
 * <h3>Why This is Correct (vs State Broadcasting):</h3>
 * <table border="1">
 * <tr><th>Wrong (State)</th><th>Right (Speech Acts)</th></tr>
 * <tr>
 *   <td>❌ "I'm in CRITICAL state"</td>
 *   <td>✅ "REQUEST: Scale partition replicas"</td>
 * </tr>
 * <tr>
 *   <td>❌ "Buffer utilization = 95%"</td>
 *   <td>✅ "REQUEST: Help with buffer pressure"</td>
 * </tr>
 * <tr>
 *   <td>❌ Passive reporting (unclear what to do)</td>
 *   <td>✅ Active request (clear action needed)</td>
 * </tr>
 * <tr>
 *   <td>❌ Requires state synchronization</td>
 *   <td>✅ No state sync (just requests &amp; responses)</td>
 * </tr>
 * </table>
 *
 * <h3>Three Levels of Autonomy:</h3>
 * <table border="1">
 * <tr><th>Level</th><th>Agent Outcome</th><th>Communication</th><th>Frequency</th></tr>
 * <tr>
 *   <td>Level 1</td>
 *   <td>Agent.FULFILLED</td>
 *   <td>❌ None (silent self-healing)</td>
 *   <td>99%</td>
 * </tr>
 * <tr>
 *   <td>Level 2</td>
 *   <td>Agent.FULFILLED + notable</td>
 *   <td>✅ Actor.directive("INFORM: Handled pressure")</td>
 *   <td>0.9%</td>
 * </tr>
 * <tr>
 *   <td>Level 3</td>
 *   <td>Agent.BREACHED</td>
 *   <td>✅ Actor.directive("REQUEST: Need help")</td>
 *   <td>0.1%</td>
 * </tr>
 * </table>
 *
 * @see io.fullerstack.kafka.producer.agents.ProducerSelfRegulator
 * @see io.humainary.substrates.ext.serventis.ext.Agents
 * @see io.humainary.substrates.ext.serventis.ext.Actors
 * @since 1.0.0
 */
public class AgentCoordinationBridge implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(AgentCoordinationBridge.class);

    private final Conduit<Agents.Agent, Agents.Signal> localAgents;
    private final Conduit<Actors.Actor, Actors.Sign> actors;
    private final CentralCommunicator centralCommunicator;
    private final String sourceId;
    private final Subscription agentSubscription;
    private final Subscription actorSubscription;

    // Conversation state (request-response tracking)
    private final Map<String, Conversation> activeConversations = new ConcurrentHashMap<>();
    private final AtomicReference<Agents.Signal> lastAgentOutcome = new AtomicReference<>();
    private final Map<String, Instant> lastHelpRequest = new ConcurrentHashMap<>();

    // Configuration
    private static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(5);

    public AgentCoordinationBridge(
        Conduit<Agents.Agent, Agents.Signal> localAgents,
        Conduit<Actors.Actor, Actors.Sign> actors,
        CentralCommunicator centralCommunicator,
        String sourceId
    ) {
        this.localAgents = localAgents;
        this.actors = actors;
        this.centralCommunicator = centralCommunicator;
        this.sourceId = sourceId;

        // Subscribe to local agent promise outcomes (fulfill vs breach)
        this.agentSubscription = localAgents.subscribe(cortex().subscriber(
            cortex().name("agent-outcome-observer"),
            this::handleAgentOutcome
        ));

        // Subscribe to actor responses from central (inform, apologize, etc.)
        this.actorSubscription = actors.subscribe(cortex().subscriber(
            cortex().name("central-response-handler"),
            this::handleCentralResponse
        ));

        logger.info("AgentCoordinationBridge started for agent: {} (Promise Theory + Speech Acts)", sourceId);
    }

    private void handleAgentOutcome(
        Subject<Channel<Agents.Signal>> subject,
        Registrar<Agents.Signal> registrar
    ) {
        registrar.register(signal -> {
            String agentName = subject.name().toString();

            lastAgentOutcome.set(signal);

            logger.debug("[AGENT] Promise outcome: {} -> {}", agentName, signal);

            switch (signal.sign()) {
                case PROMISE -> logger.info("[AGENT] Promise made: {} (PROMISER)", agentName);
                case FULFILL -> handlePromiseFulfilled(agentName);
                case BREACH -> handlePromiseBreached(agentName);
            }
        });
    }

    private void handlePromiseFulfilled(String agentName) {
        logger.info("[AGENT] ✅ Promise fulfilled: {} - Self-regulation successful", agentName);

        // Level 1: Silent self-healing (99%) - don't communicate

        // Level 2: Optional inform (0.9%) - for notable events
        if (shouldInformCentral(agentName)) {
            sendInformSpeechAct(agentName, "Self-regulation successful - buffer pressure resolved");
        }
    }

    private void handlePromiseBreached(String agentName) {
        logger.error("[AGENT] ❌ Promise breached: {} - Local self-regulation failed", agentName);

        if (isWithinCooldown(agentName)) {
            logger.warn("[AGENT] Request cooldown active, suppressing duplicate help request");
            return;
        }

        HelpRequest helpRequest = analyzeFailureAndDetermineHelp(agentName);
        sendHelpRequest(agentName, helpRequest);
        lastHelpRequest.put(agentName, Instant.now());
    }

    private HelpRequest analyzeFailureAndDetermineHelp(String agentName) {
        if (agentName.contains("throttle")) {
            return new HelpRequest(
                RequestType.SCALE_RESOURCES,
                "Buffer exhausted despite throttling to 2 max-in-flight requests",
                List.of("increase partition replicas", "expand broker disk capacity"),
                Urgency.IMMEDIATE
            );
        } else {
            return new HelpRequest(
                RequestType.GENERAL_ASSISTANCE,
                "Agent promise breached - need investigation",
                List.of("analyze logs", "check metrics"),
                Urgency.EVENTUAL
            );
        }
    }

    private void sendHelpRequest(String agentName, HelpRequest helpRequest) {
        Actors.Actor helpRequester = actors.percept(cortex().name(sourceId + ".help-requester"));

        // REQUEST speech act (Actors API method)
        helpRequester.request();  // Emits Actors.Sign.REQUEST

        centralCommunicator.sendDirective(new DirectiveMessage(
            sourceId,
            agentName,
            helpRequest.type().toString(),
            helpRequest.description(),
            helpRequest.suggestedActions(),
            helpRequest.urgency().toString(),
            Instant.now().toEpochMilli()
        ));

        String conversationId = sourceId + ":" + agentName + ":" + Instant.now().toEpochMilli();
        activeConversations.put(conversationId, new Conversation(
            conversationId, helpRequest, Instant.now(), ConversationState.REQUESTED
        ));

        logger.error("[ACTOR] ✅ REQUEST sent: {} - {}", helpRequest.type(), helpRequest.description());
    }

    private void sendInformSpeechAct(String agentName, String information) {
        Actors.Actor informer = actors.percept(cortex().name(sourceId + ".informer"));

        // REPORT speech act (Actors API method)
        informer.report();  // Emits Actors.Sign.REPORT

        centralCommunicator.sendInform(new InformMessage(
            sourceId, agentName, information, Instant.now().toEpochMilli()
        ));

        logger.info("[ACTOR] ✅ REPORT sent: {}", information);
    }

    private void handleCentralResponse(
        Subject<Channel<Actors.Sign>> subject,
        Registrar<Actors.Sign> registrar
    ) {
        registrar.register(sign -> {
            String actorName = subject.name().toString();

            // Filter: Only process responses from central actors (not our local actors)
            if (!actorName.startsWith("central.")) {
                return;  // Ignore local actor signals
            }

            logger.info("[ACTOR] Response from central: {} -> {}", actorName, sign);

            switch (sign) {
                case Actors.Sign.PROMISE -> {
                    // Central promised to help
                    logger.info("[ACTOR] Central PROMISED to help");
                    updateConversationState(ConversationState.COMMITTED);
                }
                case Actors.Sign.DELIVER -> {
                    // Central delivered help (fulfilled promise)
                    logger.info("[ACTOR] Central DELIVERED help");
                    updateConversationState(ConversationState.RESOLVED);
                }
                case Actors.Sign.REPORT -> {
                    // Central reported outcome
                    logger.info("[ACTOR] Central REPORTED outcome");
                    updateConversationState(ConversationState.RESOLVED);
                }
                case Actors.Sign.DENY -> {
                    // Central cannot help
                    logger.warn("[ACTOR] Central DENIED request (cannot help)");
                    updateConversationState(ConversationState.REJECTED);
                }
                case Actors.Sign.EXPLAIN -> {
                    // Central explained why/how
                    logger.info("[ACTOR] Central EXPLAINED reasoning");
                }
                case Actors.Sign.ACKNOWLEDGE -> {
                    // Central acknowledged our request
                    logger.info("[ACTOR] Central ACKNOWLEDGED request");
                }
            }
        });
    }

    private boolean shouldInformCentral(String agentName) {
        return Math.random() < 0.01;  // 1% audit trail
    }

    private boolean isWithinCooldown(String agentName) {
        Instant lastTime = lastHelpRequest.get(agentName);
        if (lastTime == null) return false;
        return Duration.between(lastTime, Instant.now()).compareTo(REQUEST_COOLDOWN) < 0;
    }

    private void updateConversationState(ConversationState newState) {
        activeConversations.values().forEach(conv -> {
            if (conv.state() != ConversationState.RESOLVED && conv.state() != ConversationState.REJECTED) {
                logger.debug("[CONVERSATION] State transition: {} -> {}", conv.state(), newState);
            }
        });
    }

    @Override
    public void close() {
        logger.info("Shutting down agent coordination bridge for agent: {}", sourceId);
        if (agentSubscription != null) agentSubscription.close();
        if (actorSubscription != null) actorSubscription.close();
        activeConversations.clear();
    }

    // ===== Domain Model =====

    public record HelpRequest(
        RequestType type, String description, List<String> suggestedActions, Urgency urgency
    ) {
        String toDirective(String sourceId) {
            return "REQUEST[%s] from %s: %s - Suggested: %s (Urgency: %s)".formatted(
                type, sourceId, description, String.join(", ", suggestedActions), urgency
            );
        }
    }

    public enum RequestType {
        SCALE_RESOURCES, INVESTIGATE_BROKER, REBALANCE_PARTITIONS, PROVISION_DISK, GENERAL_ASSISTANCE
    }

    public enum Urgency { IMMEDIATE, SOON, EVENTUAL }

    private record Conversation(String id, HelpRequest request, Instant requestTime, ConversationState state) {}

    private enum ConversationState { REQUESTED, COMMITTED, RESOLVED, REJECTED }

}
