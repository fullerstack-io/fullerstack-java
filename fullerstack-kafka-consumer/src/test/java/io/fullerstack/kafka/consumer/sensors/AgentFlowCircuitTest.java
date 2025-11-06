package io.fullerstack.kafka.consumer.sensors;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Agents.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AgentFlowCircuit}.
 * <p>
 * Validates RC6 Agents API reference implementation and pattern demonstrations.
 */
@DisplayName("AgentFlowCircuit - RC6 Agents API Reference Implementation")
class AgentFlowCircuitTest {

    private AgentFlowCircuit circuit;
    private final List<String> capturedTimeline = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        circuit = new AgentFlowCircuit("test-circuit");

        // Subscribe to capture signal timeline
        circuit.agents().subscribe(cortex().subscriber(
            cortex().name("timeline-capture"),
            (Subject<Channel<Agents.Signal>> subject, Registrar<Agents.Signal> registrar) -> {
                registrar.register(signal -> {
                    String event = subject.name() + ":" + signal.sign() + ":" + signal.dimension();
                    capturedTimeline.add(event);
                });
            }
        ));
    }

    @AfterEach
    void tearDown() {
        capturedTimeline.clear();

        if (circuit != null) {
            circuit.close();
        }
    }

    // ========================================
    // Constructor Tests
    // ========================================

    @Test
    @DisplayName("Creates circuit with default name")
    void shouldCreateCircuitWithDefaultName() {
        // When
        try (AgentFlowCircuit c = new AgentFlowCircuit()) {
            // Then
            assertThat(c).isNotNull();
            assertThat(c.agents()).isNotNull();
        }
    }

    @Test
    @DisplayName("Creates circuit with custom name")
    void shouldCreateCircuitWithCustomName() {
        // When
        try (AgentFlowCircuit c = new AgentFlowCircuit("custom-circuit")) {
            // Then
            assertThat(c).isNotNull();
            assertThat(c.agents()).isNotNull();
        }
    }

    // ========================================
    // Agent Creation Tests
    // ========================================

    @Test
    @DisplayName("Creates consumer agent")
    void shouldCreateConsumerAgent() {
        // When
        Agent consumer = circuit.consumer("consumer-1");

        // Then
        assertThat(consumer).isNotNull();
    }

    @Test
    @DisplayName("Creates coordinator agent")
    void shouldCreateCoordinatorAgent() {
        // When
        Agent coordinator = circuit.coordinator("test-group");

        // Then
        assertThat(coordinator).isNotNull();
    }

    @Test
    @DisplayName("Creates generic agent")
    void shouldCreateGenericAgent() {
        // When
        Agent agent = circuit.agent("generic-agent");

        // Then
        assertThat(agent).isNotNull();
    }

    @Test
    @DisplayName("Multiple calls to consumer() return same agent")
    void shouldReturnSameConsumerAgent() {
        // When
        Agent consumer1 = circuit.consumer("consumer-1");
        Agent consumer2 = circuit.consumer("consumer-1");

        // Then - same instance (from conduit cache)
        assertThat(consumer1).isSameAs(consumer2);
    }

    // ========================================
    // Signal Emission Tests
    // ========================================

    @Test
    @DisplayName("Emits signals when agent methods are called")
    void shouldEmitSignalsWhenAgentMethodsCalled() {
        // Given
        Agent consumer = circuit.consumer("consumer-1");

        // When
        consumer.inquire();
        circuit.awaitSignals();

        // Then
        assertThat(capturedTimeline).hasSize(1);
        assertThat(capturedTimeline.get(0)).contains("INQUIRE").contains("OUTBOUND");
    }

    @Test
    @DisplayName("awaitSignals() ensures signal processing")
    void shouldEnsureSignalProcessingWithAwait() {
        // Given
        Agent consumer = circuit.consumer("consumer-1");

        // When - emit multiple signals
        consumer.inquire();
        consumer.promise();
        consumer.fulfill();
        circuit.awaitSignals();

        // Then - all signals processed
        assertThat(capturedTimeline).hasSize(3);
    }

    // ========================================
    // Rebalance Lifecycle Demo Tests
    // ========================================

    @Test
    @DisplayName("demonstrateRebalanceLifecycle() emits complete sequence")
    void shouldDemonstrateCompleteRebalanceLifecycle() {
        // When
        circuit.demonstrateRebalanceLifecycle();

        // Then - verify complete lifecycle signals
        assertThat(capturedTimeline).containsExactly(
            "consumer-1:INQUIRE:OUTBOUND",
            "coordinator-test-group:OFFER:INBOUND",
            "consumer-1:PROMISE:OUTBOUND",
            "coordinator-test-group:ACCEPT:INBOUND",
            "consumer-1:FULFILL:OUTBOUND"
        );
    }

    @Test
    @DisplayName("Rebalance lifecycle follows correct order")
    void shouldFollowCorrectRebalanceOrder() {
        // When
        circuit.demonstrateRebalanceLifecycle();

        // Then - verify order
        List<String> signs = capturedTimeline.stream()
            .map(event -> event.split(":")[1])
            .toList();

        assertThat(signs).containsExactly(
            "INQUIRE",
            "OFFER",
            "PROMISE",
            "ACCEPT",
            "FULFILL"
        );
    }

    // ========================================
    // Promise Breach Demo Tests
    // ========================================

    @Test
    @DisplayName("demonstratePromiseBreach() emits breach signal")
    void shouldDemonstratePromiseBreach() {
        // When
        circuit.demonstratePromiseBreach();

        // Then - verify breach sequence
        assertThat(capturedTimeline)
            .contains("consumer-2:BREACH:OUTBOUND")
            .hasSize(5);  // INQUIRE, OFFERED, PROMISE, ACCEPTED, BREACH
    }

    @Test
    @DisplayName("Breach occurs after promise")
    void shouldEmitBreachAfterPromise() {
        // When
        circuit.demonstratePromiseBreach();

        // Then
        List<String> signs = capturedTimeline.stream()
            .map(event -> event.split(":")[1])
            .toList();

        int promiseIndex = signs.indexOf("PROMISE");
        int breachIndex = signs.indexOf("BREACH");

        assertThat(breachIndex).isGreaterThan(promiseIndex);
    }

    // ========================================
    // Multi-Consumer Demo Tests
    // ========================================

    @Test
    @DisplayName("demonstrateMultiConsumerRebalance() coordinates multiple consumers")
    void shouldDemonstrateMultiConsumerRebalance() {
        // When
        circuit.demonstrateMultiConsumerRebalance();

        // Then - verify multiple consumers participated
        List<String> consumerSignals = capturedTimeline.stream()
            .filter(event -> event.startsWith("consumer-"))
            .toList();

        assertThat(consumerSignals).hasSizeGreaterThan(4);  // At least 2 consumers × 2+ signals each

        // Verify both consumers present
        assertThat(capturedTimeline.stream().anyMatch(e -> e.contains("consumer-1"))).isTrue();
        assertThat(capturedTimeline.stream().anyMatch(e -> e.contains("consumer-2"))).isTrue();
    }

    @Test
    @DisplayName("Multi-consumer rebalance shows both fulfill and breach")
    void shouldShowMixedOutcomesInMultiConsumer() {
        // When
        circuit.demonstrateMultiConsumerRebalance();

        // Then
        List<String> signs = capturedTimeline.stream()
            .map(event -> event.split(":")[1])
            .toList();

        assertThat(signs).contains("FULFILL");
        assertThat(signs).contains("BREACH");
    }

    // ========================================
    // Aspect Tests
    // ========================================

    @Test
    @DisplayName("Consumer signals are OUTBOUND")
    void shouldEmitOutboundSignalsForConsumer() {
        // Given
        Agent consumer = circuit.consumer("test-consumer");

        // When
        consumer.inquire();
        consumer.promise();
        consumer.fulfill();
        circuit.awaitSignals();

        // Then - all consumer signals are OUTBOUND
        List<String> consumerEvents = capturedTimeline.stream()
            .filter(e -> e.startsWith("test-consumer"))
            .toList();

        consumerEvents.forEach(event -> {
            assertThat(event).endsWith("OUTBOUND");
        });
    }

    @Test
    @DisplayName("Coordinator signals are INBOUND")
    void shouldEmitInboundSignalsForCoordinator() {
        // Given
        Agent coordinator = circuit.coordinator("test-group");

        // When
        coordinator.offered();
        coordinator.accepted();
        circuit.awaitSignals();

        // Then - coordinator signals are INBOUND (observed by consumer)
        List<String> coordinatorEvents = capturedTimeline.stream()
            .filter(e -> e.contains("coordinator"))
            .toList();

        coordinatorEvents.forEach(event -> {
            assertThat(event).endsWith("INBOUND");
        });
    }

    // ========================================
    // Resource Cleanup Tests
    // ========================================

    @Test
    @DisplayName("Close releases circuit resources")
    void shouldReleaseResourcesOnClose() {
        // Given
        Agent consumer = circuit.consumer("consumer-1");
        consumer.inquire();
        circuit.awaitSignals();

        // When
        circuit.close();

        // Then - no exceptions, circuit closed
        // (Further signal emissions would fail, but we don't test that)
    }

    @Test
    @DisplayName("Can create and close multiple circuits")
    void shouldHandleMultipleCircuitLifecycles() {
        // When
        for (int i = 0; i < 3; i++) {
            try (AgentFlowCircuit c = new AgentFlowCircuit("circuit-" + i)) {
                Agent agent = c.consumer("consumer");
                agent.inquire();
                c.awaitSignals();
            }
        }

        // Then - no resource leaks, no exceptions
    }

    // ========================================
    // Promise Theory Semantics Tests
    // ========================================

    @Test
    @DisplayName("All 10 promise theory signs are available")
    void shouldSupportAll10PromiseTheorySigns() {
        // Given
        Agent agent = circuit.agent("test-agent");

        // When - emit all sign types (outbound versions)
        agent.inquire();
        agent.offer();
        agent.promise();
        agent.accept();
        agent.depend();
        agent.observe();
        agent.validate();
        agent.fulfill();
        agent.breach();
        agent.retract();
        circuit.awaitSignals();

        // Then - all signs captured
        List<String> signs = capturedTimeline.stream()
            .map(event -> event.split(":")[1])
            .distinct()
            .toList();

        assertThat(signs).containsExactlyInAnyOrder(
            "INQUIRE",
            "OFFER",
            "PROMISE",
            "ACCEPT",
            "DEPEND",
            "OBSERVE",
            "VALIDATE",
            "FULFILL",
            "BREACH",
            "RETRACT"
        );
    }

    @Test
    @DisplayName("Both directions are available for each sign")
    void shouldSupportBothAspectsForEachSign() {
        // Given
        Agent agent = circuit.agent("test-agent");

        // When - emit both directions for PROMISE
        agent.promise();   // OUTBOUND
        agent.promised();  // INBOUND
        circuit.awaitSignals();

        // Then
        assertThat(capturedTimeline).containsExactly(
            "test-agent:PROMISE:OUTBOUND",
            "test-agent:PROMISE:INBOUND"
        );
    }

    // ========================================
    // Integration Tests
    // ========================================

    @Test
    @DisplayName("Complete consumer rebalance scenario with timing")
    void shouldSimulateRealisticRebalanceScenario() {
        // Given - multiple consumers
        Agent consumer1 = circuit.consumer("consumer-1");
        Agent consumer2 = circuit.consumer("consumer-2");
        Agent coordinator = circuit.coordinator("my-group");

        // When - simulate rebalance with one consumer joining

        // Existing consumer gets revoked
        consumer1.inquire();

        // New consumer joins
        consumer2.inquire();

        circuit.awaitSignals();

        // Coordinator offers new assignments
        coordinator.offered();
        circuit.awaitSignals();

        // Both consumers promise
        consumer1.promise();
        consumer2.promise();
        circuit.awaitSignals();

        // Coordinator accepts
        coordinator.accepted();
        circuit.awaitSignals();

        // Both consumers fulfill
        consumer1.fulfill();
        consumer2.fulfill();
        circuit.awaitSignals();

        // Then - complete scenario captured (8 signals: 2 INQUIRE + 1 OFFER + 2 PROMISE + 1 ACCEPT + 2 FULFILL)
        assertThat(capturedTimeline).hasSize(8);

        // Verify both consumers participated
        assertThat(capturedTimeline.stream().anyMatch(e -> e.contains("consumer-1"))).isTrue();
        assertThat(capturedTimeline.stream().anyMatch(e -> e.contains("consumer-2"))).isTrue();
    }
}
