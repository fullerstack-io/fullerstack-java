package io.fullerstack.kafka.consumer.sensors;

import io.humainary.substrates.api.Substrates;
import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Agents.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Reference implementation of Agents API (RC6) pattern for consumer rebalance monitoring.
 * <p>
 * Demonstrates complete Promise Theory lifecycle using Substrates RC7 API:
 * <pre>
 * INQUIRE  → "Can I participate?"
 * OFFER    → "Here's what's available"
 * PROMISE  → "I commit to this"
 * ACCEPT   → "Commitment confirmed"
 * FULFILL  → "I kept my promise"
 * BREACH   → "I failed to keep my promise"
 * </pre>
 *
 * <h3>RC7 Pattern:</h3>
 * <ul>
 *   <li>Static cortex() access (no CortexRuntime.create())</li>
 *   <li>Circuit → Conduit → Agent hierarchy</li>
 *   <li>Method reference composers (Agents::composer)</li>
 *   <li>circuit.await() for synchronization</li>
 * </ul>
 *
 * <h3>RC6 Agents API:</h3>
 * <ul>
 *   <li><b>OUTBOUND signals:</b> Self-reporting (agent.promise())</li>
 *   <li><b>INBOUND signals:</b> Observing others (agent.promised())</li>
 *   <li><b>20 total signals:</b> 10 signs × 2 directions</li>
 *   <li><b>Promise autonomy:</b> Agents control only what they can deliver</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * try (AgentFlowCircuit circuit = new AgentFlowCircuit()) {
 *     Agent consumer = circuit.consumer("consumer-1");
 *     Agent coordinator = circuit.coordinator("my-group");
 *
 *     // Consumer rebalance lifecycle
 *     consumer.inquire();      // "Can I join?"
 *     coordinator.offered();   // "Coordinator offered partitions"
 *     consumer.promise();      // "I commit to consume"
 *     coordinator.accepted();  // "Coordinator accepted"
 *     circuit.awaitSignals();
 *
 *     // Later: fulfill or breach
 *     consumer.fulfill();      // "I kept my promise"
 *     circuit.awaitSignals();
 * }
 * }</pre>
 *
 * @author Fullerstack
 * @see Agents
 * @see ConsumerRebalanceAgentMonitor
 */
public class AgentFlowCircuit implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AgentFlowCircuit.class);

    private final Circuit circuit;
    private final Conduit<Agent, Agents.Signal> agents;

    /**
     * Creates agent flow circuit with default naming.
     */
    public AgentFlowCircuit() {
        this("agent-flow-circuit");
    }

    /**
     * Creates agent flow circuit with custom circuit name.
     *
     * @param circuitName Name for the circuit
     */
    public AgentFlowCircuit(String circuitName) {
        // RC7: Static cortex() access
        this.circuit = cortex().circuit(cortex().name(circuitName));

        // Create Agents conduit using method reference composer (RC6)
        this.agents = circuit.conduit(
            cortex().name("agents"),
            Agents::composer
        );

        logger.debug("Created AgentFlowCircuit: {}", circuitName);
    }

    /**
     * Gets or creates consumer agent.
     *
     * @param consumerId Consumer identifier
     * @return Agent representing the consumer
     */
    public Agent consumer(String consumerId) {
        return agents.get(cortex().name(consumerId));
    }

    /**
     * Gets or creates coordinator agent for a consumer group.
     *
     * @param consumerGroup Consumer group identifier
     * @return Agent representing the group coordinator
     */
    public Agent coordinator(String consumerGroup) {
        return agents.get(cortex().name("coordinator-" + consumerGroup));
    }

    /**
     * Gets or creates generic agent.
     *
     * @param agentName Agent identifier
     * @return Agent with the specified name
     */
    public Agent agent(String agentName) {
        return agents.get(cortex().name(agentName));
    }

    /**
     * Provides direct access to agents conduit for subscriptions.
     *
     * @return Agents conduit
     */
    public Conduit<Agent, Agents.Signal> agents() {
        return agents;
    }

    /**
     * Waits for all emitted signals to be processed.
     * <p>
     * Call this after emitting signals to ensure they are delivered before continuing.
     */
    public void awaitSignals() {
        circuit.await();
    }

    /**
     * Demonstrates complete consumer rebalance promise lifecycle.
     * <p>
     * This method shows the canonical pattern for consumer rebalance coordination
     * using Promise Theory:
     * <ol>
     *   <li>Consumer inquires about joining (OUTBOUND)</li>
     *   <li>Coordinator offers assignment (INBOUND observation)</li>
     *   <li>Consumer promises to consume (OUTBOUND)</li>
     *   <li>Coordinator accepts promise (INBOUND observation)</li>
     *   <li>Consumer fulfills promise (OUTBOUND) OR breaches (OUTBOUND)</li>
     * </ol>
     */
    public void demonstrateRebalanceLifecycle() {
        logger.info("=== Demonstrating Consumer Rebalance Promise Lifecycle ===");

        Agent consumer = consumer("consumer-1");
        Agent coordinator = coordinator("test-group");

        // Phase 1: Partition Revocation → Inquiry
        logger.info("Phase 1: Consumer inquires about joining new generation");
        consumer.inquire();  // OUTBOUND: "Can I join?"
        awaitSignals();

        // Phase 2: Partition Assignment → Promise
        logger.info("Phase 2: Coordinator offers partitions, consumer promises");
        coordinator.offered();   // INBOUND: "Coordinator offered partitions" (observed)
        consumer.promise();      // OUTBOUND: "I commit to consuming these partitions"
        coordinator.accepted();  // INBOUND: "Coordinator accepted my promise" (observed)
        awaitSignals();

        // Phase 3: Healthy Consumption → Fulfill
        logger.info("Phase 3: Consumer fulfills promise (healthy consumption)");
        consumer.fulfill();      // OUTBOUND: "I kept my promise"
        awaitSignals();

        logger.info("=== Rebalance Lifecycle Complete ===");
    }

    /**
     * Demonstrates promise breach scenario.
     * <p>
     * Shows what happens when a consumer fails to maintain its commitment
     * (e.g., excessive lag, timeout, crash).
     */
    public void demonstratePromiseBreach() {
        logger.info("=== Demonstrating Promise Breach ===");

        Agent consumer = consumer("consumer-2");
        Agent coordinator = coordinator("test-group");

        // Consumer makes promise
        logger.info("Consumer makes promise");
        consumer.inquire();
        coordinator.offered();
        consumer.promise();
        coordinator.accepted();
        awaitSignals();

        // Consumer fails to keep promise
        logger.info("Consumer breaches promise (e.g., excessive lag)");
        consumer.breach();  // OUTBOUND: "I failed to keep my promise"
        awaitSignals();

        logger.info("=== Breach Lifecycle Complete ===");
    }

    /**
     * Demonstrates multi-agent coordination.
     * <p>
     * Shows how multiple consumers coordinate with coordinator during rebalance.
     */
    public void demonstrateMultiConsumerRebalance() {
        logger.info("=== Demonstrating Multi-Consumer Rebalance ===");

        Agent consumer1 = consumer("consumer-1");
        Agent consumer2 = consumer("consumer-2");
        Agent coordinator = coordinator("test-group");

        // Both consumers inquire
        logger.info("Multiple consumers inquire");
        consumer1.inquire();
        consumer2.inquire();
        awaitSignals();

        // Coordinator offers to both
        logger.info("Coordinator offers assignments");
        coordinator.offered();
        awaitSignals();

        // Both consumers promise
        logger.info("Consumers make promises");
        consumer1.promise();
        consumer2.promise();
        awaitSignals();

        // Coordinator accepts both
        logger.info("Coordinator accepts promises");
        coordinator.accepted();
        awaitSignals();

        // One fulfills, one breaches
        logger.info("Mixed outcomes: one fulfill, one breach");
        consumer1.fulfill();  // Healthy consumer
        consumer2.breach();   // Struggling consumer
        awaitSignals();

        logger.info("=== Multi-Consumer Rebalance Complete ===");
    }

    @Override
    public void close() {
        if (circuit != null) {
            circuit.close();
            logger.debug("Closed AgentFlowCircuit");
        }
    }

    /**
     * Command-line demonstration of Agents API patterns.
     */
    public static void main(String[] args) {
        try (AgentFlowCircuit circuit = new AgentFlowCircuit("demo")) {
            circuit.demonstrateRebalanceLifecycle();
            System.out.println();

            circuit.demonstratePromiseBreach();
            System.out.println();

            circuit.demonstrateMultiConsumerRebalance();
        }
    }
}
