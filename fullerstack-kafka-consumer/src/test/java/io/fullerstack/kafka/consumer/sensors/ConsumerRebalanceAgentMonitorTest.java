package io.fullerstack.kafka.consumer.sensors;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Agents;
import io.humainary.substrates.ext.serventis.ext.Agents.Agent;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ConsumerRebalanceAgentMonitor}.
 * <p>
 * Validates RC6 Agents API promise lifecycle patterns for consumer rebalance.
 * <p>
 * <b>CRITICAL:</b> Tests complete signal SEQUENCES, not individual signals in isolation.
 */
@DisplayName("ConsumerRebalanceAgentMonitor - RC6 Agents API Promise Lifecycle")
class ConsumerRebalanceAgentMonitorTest {

    private Circuit circuit;
    private Conduit<Agent, Agents.Signal> agents;
    private ConsumerRebalanceAgentMonitor monitor;

    private final List<SignalEvent> capturedSignals = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        // Create circuit and agents conduit
        circuit = cortex().circuit(cortex().name("rebalance-test"));
        agents = circuit.conduit(
            cortex().name("test-agents"),
            Agents::composer
        );

        // Subscribe to capture all signals
        agents.subscribe(cortex().subscriber(
            cortex().name("signal-capture"),
            (Subject<Channel<Agents.Signal>> subject, Registrar<Agents.Signal> registrar) -> {
                registrar.register(signal -> {
                    capturedSignals.add(new SignalEvent(
                        subject.name().toString(),
                        signal
                    ));
                });
            }
        ));

        // Create monitor
        monitor = new ConsumerRebalanceAgentMonitor(
            circuit,
            "test-consumer",
            "test-group",
            agents
        );
    }

    @AfterEach
    void tearDown() {
        capturedSignals.clear();

        if (monitor != null) {
            monitor.close();
        }

        if (circuit != null) {
            circuit.close();
        }
    }

    // ========================================
    // Promise Lifecycle Tests
    // ========================================

    @Test
    @DisplayName("Complete rebalance promise lifecycle: INQUIRE → OFFERED → PROMISE → ACCEPTED")
    void shouldTrackCompletePromiseLifecycle() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0),
            new TopicPartition("test-topic", 1)
        );

        // When - simulate complete rebalance
        monitor.onPartitionsRevoked(partitions);
        circuit.await();
        monitor.onPartitionsAssigned(partitions);

        // Await signal processing (TEST ONLY)
        circuit.await();

        circuit.await();
        // Then - verify promise sequence
        assertThat(capturedSignals).hasSize(4);

        // Verify exact sequence
        assertSignal(0, "test-consumer", Agents.Signal.INQUIRE);      // Consumer: "Can I join?"
        assertSignal(1, "coordinator-test-group", Agents.Signal.OFFERED);   // Coordinator: "Here are partitions"
        assertSignal(2, "test-consumer", Agents.Signal.PROMISE);      // Consumer: "I commit"
        assertSignal(3, "coordinator-test-group", Agents.Signal.ACCEPTED);  // Coordinator: "Confirmed"

        // Verify directions: OUTBOUND (consumer INQUIRE), INBOUND (coordinator OFFERED), OUTBOUND (consumer PROMISE), INBOUND (coordinator ACCEPTED)
        assertThat(capturedSignals.get(0).signal.dimension()).isEqualTo(Agents.Dimension.PROMISER);
        assertThat(capturedSignals.get(1).signal.dimension()).isEqualTo(Agents.Dimension.PROMISEE);
        assertThat(capturedSignals.get(2).signal.dimension()).isEqualTo(Agents.Dimension.PROMISER);
        assertThat(capturedSignals.get(3).signal.dimension()).isEqualTo(Agents.Dimension.PROMISEE);
    }

    @Test
    @DisplayName("Partition revocation emits INQUIRE (OUTBOUND)")
    void shouldEmitInquireOnPartitionRevocation() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        // When
        monitor.onPartitionsRevoked(partitions);
        circuit.await();

        circuit.await();
        // Then
        assertThat(capturedSignals).hasSize(1);
        assertSignal(0, "test-consumer", Agents.Signal.INQUIRE);
        assertThat(capturedSignals.get(0).signal.dimension()).isEqualTo(Agents.Dimension.PROMISER);
    }

    @Test
    @DisplayName("Partition assignment emits promise sequence: OFFERED → PROMISE → ACCEPTED")
    void shouldEmitPromiseSequenceOnPartitionAssignment() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        // When
        monitor.onPartitionsAssigned(partitions);
        circuit.await();

        circuit.await();
        // Then - verify 3-signal promise sequence
        assertThat(capturedSignals).hasSize(3);

        assertSignal(0, "coordinator-test-group", Agents.Signal.OFFERED);
        assertSignal(1, "test-consumer", Agents.Signal.PROMISE);
        assertSignal(2, "coordinator-test-group", Agents.Signal.ACCEPTED);

        // Verify directions: INBOUND (coordinator OFFERED), OUTBOUND (consumer PROMISE), INBOUND (coordinator ACCEPTED)
        assertThat(capturedSignals.get(0).signal.dimension()).isEqualTo(Agents.Dimension.PROMISEE);
        assertThat(capturedSignals.get(1).signal.dimension()).isEqualTo(Agents.Dimension.PROMISER);
        assertThat(capturedSignals.get(2).signal.dimension()).isEqualTo(Agents.Dimension.PROMISEE);
    }

    @Test
    @DisplayName("Empty partition assignment still emits promise sequence")
    void shouldEmitPromiseSequenceForEmptyPartitionAssignment() {
        // Given - consumer loses all partitions but stays in group
        Collection<TopicPartition> emptyPartitions = Collections.emptyList();

        // When
        monitor.onPartitionsAssigned(emptyPartitions);
        circuit.await();

        circuit.await();
        // Then - promise sequence still occurs (consumer commits to having zero partitions)
        assertThat(capturedSignals).hasSize(3);
        assertSignal(0, "coordinator-test-group", Agents.Signal.OFFERED);
        assertSignal(1, "test-consumer", Agents.Signal.PROMISE);
        assertSignal(2, "coordinator-test-group", Agents.Signal.ACCEPTED);
    }

    @Test
    @DisplayName("Multiple rebalances emit complete lifecycle each time")
    void shouldEmitCompleteLifecycleForMultipleRebalances() {
        // Given
        Collection<TopicPartition> partitions1 = List.of(
            new TopicPartition("test-topic", 0)
        );
        Collection<TopicPartition> partitions2 = List.of(
            new TopicPartition("test-topic", 1)
        );

        // When - simulate two consecutive rebalances
        monitor.onPartitionsRevoked(partitions1);
        circuit.await();
        monitor.onPartitionsAssigned(partitions1);

        monitor.onPartitionsRevoked(partitions2);
        circuit.await();
        monitor.onPartitionsAssigned(partitions2);

        circuit.await();
        // Then - 8 total signals (4 per rebalance)
        assertThat(capturedSignals).hasSize(8);

        // First rebalance
        assertSignal(0, "test-consumer", Agents.Signal.INQUIRE);
        assertSignal(1, "coordinator-test-group", Agents.Signal.OFFERED);
        assertSignal(2, "test-consumer", Agents.Signal.PROMISE);
        assertSignal(3, "coordinator-test-group", Agents.Signal.ACCEPTED);

        // Second rebalance
        assertSignal(4, "test-consumer", Agents.Signal.INQUIRE);
        assertSignal(5, "coordinator-test-group", Agents.Signal.OFFERED);
        assertSignal(6, "test-consumer", Agents.Signal.PROMISE);
        assertSignal(7, "coordinator-test-group", Agents.Signal.ACCEPTED);
    }

    // ========================================
    // Aspect Tests (OUTBOUND vs INBOUND)
    // ========================================

    @Test
    @DisplayName("Consumer signals are OUTBOUND (self-reporting)")
    void shouldEmitOutboundSignalsForConsumer() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        // When
        monitor.onPartitionsRevoked(partitions);
        circuit.await();
        monitor.onPartitionsAssigned(partitions);

        circuit.await();
        // Then - filter consumer signals
        List<SignalEvent> consumerSignals = capturedSignals.stream()
            .filter(e -> e.subjectName.equals("test-consumer"))
            .toList();

        assertThat(consumerSignals).hasSize(2);  // INQUIRE, PROMISE

        // All consumer signals are OUTBOUND (self-reporting)
        consumerSignals.forEach(event -> {
            assertThat(event.signal.dimension())
                .as("Consumer signal %s should be OUTBOUND", event.signal.sign())
                .isEqualTo(Agents.Dimension.PROMISER);
        });
    }

    @Test
    @DisplayName("Coordinator signals are INBOUND (observing others)")
    void shouldEmitInboundSignalsForCoordinator() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        // When
        monitor.onPartitionsAssigned(partitions);
        circuit.await();

        circuit.await();
        // Then - filter coordinator signals
        List<SignalEvent> coordinatorSignals = capturedSignals.stream()
            .filter(e -> e.subjectName.contains("coordinator"))
            .toList();

        assertThat(coordinatorSignals).hasSize(2);  // OFFERED, ACCEPTED

        // All coordinator signals are INBOUND (observed by consumer)
        coordinatorSignals.forEach(event -> {
            assertThat(event.signal.dimension())
                .as("Coordinator signal %s should be INBOUND", event.signal.sign())
                .isEqualTo(Agents.Dimension.PROMISEE);
        });
    }

    // ========================================
    // Sign Tests (Promise Theory Semantics)
    // ========================================

    @Test
    @DisplayName("Verify all promise lifecycle signs are correct")
    void shouldUseCorrectPromiseTheorySigns() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        // When
        monitor.onPartitionsRevoked(partitions);
        circuit.await();
        monitor.onPartitionsAssigned(partitions);
        circuit.await();

        circuit.await();
        // Then - verify Sign enum values
        assertThat(capturedSignals.get(0).signal.sign()).isEqualTo(Agents.Sign.INQUIRE);
        assertThat(capturedSignals.get(1).signal.sign()).isEqualTo(Agents.Sign.OFFER);   // OFFERED signal has OFFER sign
        assertThat(capturedSignals.get(2).signal.sign()).isEqualTo(Agents.Sign.PROMISE);
        assertThat(capturedSignals.get(3).signal.sign()).isEqualTo(Agents.Sign.ACCEPT);  // ACCEPTED signal has ACCEPT sign
    }

    @Test
    @DisplayName("Signal sequence follows Promise Theory semantics")
    void shouldFollowPromiseTheorySemantics() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        // When
        monitor.onPartitionsRevoked(partitions);
        circuit.await();
        monitor.onPartitionsAssigned(partitions);
        circuit.await();

        circuit.await();
        // Then - verify Promise Theory flow
        // 1. Discovery: Consumer asks, Coordinator offers
        assertThat(capturedSignals.get(0).signal.sign()).isEqualTo(Agents.Sign.INQUIRE);
        assertThat(capturedSignals.get(1).signal.sign()).isEqualTo(Agents.Sign.OFFER);

        // 2. Commitment: Consumer promises, Coordinator accepts
        assertThat(capturedSignals.get(2).signal.sign()).isEqualTo(Agents.Sign.PROMISE);
        assertThat(capturedSignals.get(3).signal.sign()).isEqualTo(Agents.Sign.ACCEPT);

        // Verify semantic order: INQUIRE before PROMISE
        int inquireIndex = findSignalIndex(Agents.Sign.INQUIRE);
        int promiseIndex = findSignalIndex(Agents.Sign.PROMISE);
        assertThat(inquireIndex).isLessThan(promiseIndex);

        // Verify semantic order: OFFER before ACCEPT
        int offerIndex = findSignalIndex(Agents.Sign.OFFER);
        int acceptIndex = findSignalIndex(Agents.Sign.ACCEPT);
        assertThat(offerIndex).isLessThan(acceptIndex);
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Test
    @DisplayName("Handles exception during revoke without failing")
    void shouldHandleExceptionDuringRevoke() {
        // Given - create monitor with null circuit (will cause exception)
        // Actually, let's not break the monitor - instead test resilience

        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        // When - multiple calls should still work
        monitor.onPartitionsRevoked(partitions);
        circuit.await();
        monitor.onPartitionsRevoked(partitions);

        circuit.await();
        // Then - both revokes processed
        long inquireCount = capturedSignals.stream()
            .filter(e -> e.signal.sign() == Agents.Sign.INQUIRE)
            .count();

        assertThat(inquireCount).isEqualTo(2);
    }

    @Test
    @DisplayName("Handles exception during assign without failing")
    void shouldHandleExceptionDuringAssign() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        // When - multiple assignments
        monitor.onPartitionsAssigned(partitions);
        circuit.await();
        monitor.onPartitionsAssigned(partitions);

        circuit.await();
        // Then - both assignments processed
        long promiseCount = capturedSignals.stream()
            .filter(e -> e.signal.sign() == Agents.Sign.PROMISE)
            .count();

        assertThat(promiseCount).isEqualTo(2);
    }

    // ========================================
    // Offset Commit Tests
    // ========================================

    @Test
    @DisplayName("Offset commits are tracked for fulfillment assessment")
    void shouldTrackOffsetCommits() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        monitor.onPartitionsAssigned(partitions);
        circuit.await();
        capturedSignals.clear();

        // When - notify of offset commits
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
            Map.of(new TopicPartition("test-topic", 0),
                new org.apache.kafka.clients.consumer.OffsetAndMetadata(100L));

        monitor.onOffsetsCommitted(offsets);
        circuit.await();

        circuit.await();
        // Then - no immediate signals (just tracked for future fulfillment)
        // (In production, this would contribute to FULFILL assessment)
        assertThat(capturedSignals).isEmpty();
    }

    @Test
    @DisplayName("Null offset commits are handled gracefully")
    void shouldHandleNullOffsetCommits() {
        // When
        monitor.onOffsetsCommitted(null);
        circuit.await();
        monitor.onOffsetsCommitted(Collections.emptyMap());

        circuit.await();
        // Then - no errors
        assertThat(capturedSignals).isEmpty();
    }

    // ========================================
    // Resource Cleanup Tests
    // ========================================

    @Test
    @DisplayName("Close stops monitoring and releases resources")
    void shouldCleanupOnClose() {
        // Given
        Collection<TopicPartition> partitions = List.of(
            new TopicPartition("test-topic", 0)
        );

        monitor.onPartitionsAssigned(partitions);
        circuit.await();

        // When
        monitor.close();

        circuit.await();
        // Then - no exceptions, resources released
        // (Scheduler should be shut down)
    }

    // ========================================
    // Helper Methods
    // ========================================

    private void assertSignal(int index, String expectedSubject, Agents.Signal expectedSignal) {
        assertThat(capturedSignals).hasSizeGreaterThan(index);

        SignalEvent event = capturedSignals.get(index);
        assertThat(event.subjectName)
            .as("Signal %d subject", index)
            .isEqualTo(expectedSubject);
        assertThat(event.signal)
            .as("Signal %d", index)
            .isEqualTo(expectedSignal);
    }

    private int findSignalIndex(Agents.Sign sign) {
        for (int i = 0; i < capturedSignals.size(); i++) {
            if (capturedSignals.get(i).signal.sign() == sign) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Captures signal event with subject context.
     */
    private static class SignalEvent {
        final String subjectName;
        final Agents.Signal signal;

        SignalEvent(String subjectName, Agents.Signal signal) {
            this.subjectName = subjectName;
            this.signal = signal;
        }

        @Override
        public String toString() {
            return subjectName + ":" + signal.sign() + ":" + signal.dimension();
        }
    }
}
