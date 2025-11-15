package io.fullerstack.kafka.broker.monitors;

import io.humainary.substrates.api.Substrates.Circuit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListPartitionReassignmentsResult;
import org.apache.kafka.clients.admin.PartitionReassignment;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.management.MBeanServerConnection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Tests for PartitionReassignmentMonitor (RC6 Routers API Pattern).
 * <p>
 * Tests validate:
 * <ul>
 *   <li>Reassignment lifecycle state tracking</li>
 *   <li>AdminClient integration</li>
 *   <li>JMX metrics integration</li>
 *   <li>Error handling</li>
 *   <li>Monitor lifecycle</li>
 * </ul>
 *
 * <b>Note</b>: Signal emission is tested indirectly through state tracking.
 * Router signals (FRAGMENT, SEND, RECEIVE, REASSEMBLE, DROP) are emitted
 * by the monitor but not directly asserted in these tests due to the complexity
 * of capturing signals across conduits. Integration tests with real Kafka would
 * validate end-to-end signal flow.
 */
@DisplayName("PartitionReassignmentMonitor (RC6 Routers API)")
class PartitionReassignmentMonitorTest {

    @Mock
    private AdminClient adminClient;

    @Mock
    private MBeanServerConnection mbsc;

    @Mock
    private ListPartitionReassignmentsResult reassignmentsResult;

    @Mock
    private KafkaFuture<Map<TopicPartition, PartitionReassignment>> reassignmentsFuture;

    private Circuit circuit;
    private PartitionReassignmentMonitor monitor;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        circuit = cortex().circuit(cortex().name("test-reassignment-simple"));

        when(adminClient.listPartitionReassignments()).thenReturn(reassignmentsResult);
        when(reassignmentsResult.reassignments()).thenReturn(reassignmentsFuture);

        monitor = new PartitionReassignmentMonitor(adminClient, mbsc, circuit);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (monitor != null) {
            monitor.close();
        }
        if (circuit != null) {
            circuit.close();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    // ========================================================================
    // Constructor Tests
    // ========================================================================

    @Test
    @DisplayName("Should create monitor with valid parameters")
    void testConstructor_ValidParameters() {
        assertThatCode(() ->
            new PartitionReassignmentMonitor(adminClient, mbsc, circuit)
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should reject null AdminClient")
    void testConstructor_NullAdminClient() {
        assertThatThrownBy(() ->
            new PartitionReassignmentMonitor(null, mbsc, circuit)
        ).isInstanceOf(NullPointerException.class)
         .hasMessageContaining("adminClient cannot be null");
    }

    @Test
    @DisplayName("Should reject null MBeanServerConnection")
    void testConstructor_NullMBeanServer() {
        assertThatThrownBy(() ->
            new PartitionReassignmentMonitor(adminClient, null, circuit)
        ).isInstanceOf(NullPointerException.class)
         .hasMessageContaining("mbsc cannot be null");
    }

    @Test
    @DisplayName("Should reject null Circuit")
    void testConstructor_NullCircuit() {
        assertThatThrownBy(() ->
            new PartitionReassignmentMonitor(adminClient, mbsc, null)
        ).isInstanceOf(NullPointerException.class)
         .hasMessageContaining("circuit cannot be null");
    }

    // ========================================================================
    // State Tracking Tests
    // ========================================================================

    @Test
    @DisplayName("Should track reassignment state when started")
    void testStateTracking_ReassignmentStarted() throws Exception {
        // Given: New reassignment
        TopicPartition tp = new TopicPartition("test-topic", 0);
        PartitionReassignment reassignment = new PartitionReassignment(
            List.of(1, 2, 3),
            List.of(3),
            List.of()
        );

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp, reassignment));

        // When
        monitor.pollReassignments();

        // Then: Should track state
        assertThat(monitor.getActiveReassignments())
            .containsKey(tp)
            .hasSize(1);

        PartitionReassignmentMonitor.ReassignmentState state =
            monitor.getActiveReassignments().get(tp);

        assertThat(state.targetReplicas()).containsExactly(1, 2, 3);
        assertThat(state.addingReplicas()).containsExactly(3);
        assertThat(state.removingReplicas()).isEmpty();
    }

    @Test
    @DisplayName("Should cleanup state when reassignment completes")
    void testStateTracking_ReassignmentCompleted() throws Exception {
        // Given: Ongoing reassignment
        TopicPartition tp = new TopicPartition("test-topic", 0);
        PartitionReassignment reassignment = new PartitionReassignment(
            List.of(1, 2, 3),
            List.of(3),
            List.of()
        );

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp, reassignment));
        monitor.pollReassignments();

        assertThat(monitor.getActiveReassignments()).hasSize(1);

        // When: Reassignment completes
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of());
        monitor.pollReassignments();

        // Then: Should cleanup state
        assertThat(monitor.getActiveReassignments()).isEmpty();
    }

    @Test
    @DisplayName("Should handle multiple concurrent reassignments")
    void testStateTracking_MultipleConcurrent() throws Exception {
        // Given: Multiple reassignments
        TopicPartition tp1 = new TopicPartition("topic-1", 0);
        TopicPartition tp2 = new TopicPartition("topic-2", 0);
        PartitionReassignment r1 = new PartitionReassignment(List.of(1, 2), List.of(3), List.of());
        PartitionReassignment r2 = new PartitionReassignment(List.of(2, 3), List.of(4), List.of());

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp1, r1, tp2, r2));

        // When
        monitor.pollReassignments();

        // Then: Should track both
        assertThat(monitor.getActiveReassignments()).hasSize(2);
        assertThat(monitor.getActiveReassignments()).containsKeys(tp1, tp2);
    }

    @Test
    @DisplayName("Should not re-track existing reassignment")
    void testStateTracking_NoReTracking() throws Exception {
        // Given: Ongoing reassignment
        TopicPartition tp = new TopicPartition("test-topic", 0);
        PartitionReassignment reassignment = new PartitionReassignment(
            List.of(1, 2, 3),
            List.of(3),
            List.of()
        );

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp, reassignment));

        // When: Poll twice
        monitor.pollReassignments();
        long firstStartTime = monitor.getActiveReassignments().get(tp).startTime();

        monitor.pollReassignments();
        long secondStartTime = monitor.getActiveReassignments().get(tp).startTime();

        // Then: Start time should not change (not re-tracked)
        assertThat(firstStartTime).isEqualTo(secondStartTime);
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Test
    @DisplayName("Should handle AdminClient timeouts")
    void testErrorHandling_AdminClientTimeout() throws Exception {
        // Given: AdminClient timeout
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenThrow(new java.util.concurrent.TimeoutException("Timeout"));

        // When/Then: Should not throw
        assertThatCode(() -> monitor.pollReassignments())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should handle AdminClient exceptions")
    void testErrorHandling_AdminClientException() throws Exception {
        // Given: AdminClient failure
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenThrow(new RuntimeException("Connection failed"));

        // When/Then: Should not throw
        assertThatCode(() -> monitor.pollReassignments())
            .doesNotThrowAnyException();
    }

    // ========================================================================
    // Lifecycle Tests
    // ========================================================================

    @Test
    @DisplayName("Should start monitoring")
    void testLifecycle_Start() {
        assertThatCode(() -> monitor.start(1))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should close without errors")
    void testLifecycle_Close() {
        assertThatCode(() -> monitor.close())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should be idempotent on close")
    void testLifecycle_MultipleClose() {
        assertThatCode(() -> {
            monitor.close();
            monitor.close();
        }).doesNotThrowAnyException();
    }

    // ========================================================================
    // Reassignment Progress Tests
    // ========================================================================

    @Test
    @DisplayName("Should update bytes moved on progress")
    void testProgress_BytesMoved() throws Exception {
        // Given: Ongoing reassignment
        TopicPartition tp = new TopicPartition("test-topic", 0);
        PartitionReassignment reassignment = new PartitionReassignment(
            List.of(1, 2, 3),
            List.of(3),
            List.of()
        );

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp, reassignment));
        monitor.pollReassignments();

        long initialBytes = monitor.getActiveReassignments().get(tp).bytesMoved();

        // When: Bytes moved (mock JMX)
        javax.management.ObjectName objectName =
            new javax.management.ObjectName("kafka.server:type=ReassignmentMetrics,name=BytesMovedPerSec");
        when(mbsc.getAttribute(objectName, "Count")).thenReturn(1024L);

        monitor.pollReassignments();

        // Then: Bytes should be updated
        long updatedBytes = monitor.getActiveReassignments().get(tp).bytesMoved();
        assertThat(updatedBytes).isGreaterThan(initialBytes);
    }

    @Test
    @DisplayName("Should handle JMX metric failures gracefully")
    void testProgress_JmxFailure() throws Exception {
        // Given: Ongoing reassignment
        TopicPartition tp = new TopicPartition("test-topic", 0);
        PartitionReassignment reassignment = new PartitionReassignment(
            List.of(1, 2, 3),
            List.of(3),
            List.of()
        );

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp, reassignment));
        monitor.pollReassignments();

        // When: JMX fails
        javax.management.ObjectName objectName =
            new javax.management.ObjectName("kafka.server:type=ReassignmentMetrics,name=BytesMovedPerSec");
        when(mbsc.getAttribute(objectName, "Count"))
            .thenThrow(new javax.management.AttributeNotFoundException("Not found"));

        // Then: Should not throw
        assertThatCode(() -> monitor.pollReassignments())
            .doesNotThrowAnyException();
    }

    // ========================================================================
    // Reassignment Completion Tests
    // ========================================================================

    @Test
    @DisplayName("Should complete single reassignment")
    void testCompletion_SingleReassignment() throws Exception {
        // Given: Ongoing reassignment
        TopicPartition tp = new TopicPartition("test-topic", 0);
        PartitionReassignment reassignment = new PartitionReassignment(
            List.of(1, 2, 3),
            List.of(3),
            List.of(1)
        );

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp, reassignment));
        monitor.pollReassignments();

        // When: Reassignment completes
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of());
        monitor.pollReassignments();

        // Then: State should be cleared
        assertThat(monitor.getActiveReassignments()).isEmpty();
    }

    @Test
    @DisplayName("Should complete only finished reassignments")
    void testCompletion_PartialCompletion() throws Exception {
        // Given: Two ongoing reassignments
        TopicPartition tp1 = new TopicPartition("topic-1", 0);
        TopicPartition tp2 = new TopicPartition("topic-2", 0);
        PartitionReassignment r1 = new PartitionReassignment(List.of(1, 2), List.of(3), List.of());
        PartitionReassignment r2 = new PartitionReassignment(List.of(2, 3), List.of(4), List.of());

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp1, r1, tp2, r2));
        monitor.pollReassignments();

        assertThat(monitor.getActiveReassignments()).hasSize(2);

        // When: Only tp1 completes
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp2, r2));
        monitor.pollReassignments();

        // Then: tp1 should be cleared, tp2 should remain
        assertThat(monitor.getActiveReassignments())
            .hasSize(1)
            .containsKey(tp2);
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Test
    @DisplayName("Should handle empty reassignment list")
    void testEdgeCase_EmptyReassignments() throws Exception {
        // Given: No reassignments
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of());

        // When/Then: Should not throw
        assertThatCode(() -> monitor.pollReassignments())
            .doesNotThrowAnyException();

        assertThat(monitor.getActiveReassignments()).isEmpty();
    }

    @Test
    @DisplayName("Should handle reassignment with no adding replicas")
    void testEdgeCase_NoAddingReplicas() throws Exception {
        // Given: Reassignment with only removing replicas
        TopicPartition tp = new TopicPartition("test-topic", 0);
        PartitionReassignment reassignment = new PartitionReassignment(
            List.of(1, 2),
            List.of(),  // No adding
            List.of(3)  // Removing
        );

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp, reassignment));

        // When/Then: Should track correctly
        monitor.pollReassignments();

        assertThat(monitor.getActiveReassignments()).containsKey(tp);
        assertThat(monitor.getActiveReassignments().get(tp).addingReplicas()).isEmpty();
        assertThat(monitor.getActiveReassignments().get(tp).removingReplicas()).containsExactly(3);
    }

    @Test
    @DisplayName("Should handle reassignment with no removing replicas")
    void testEdgeCase_NoRemovingReplicas() throws Exception {
        // Given: Reassignment with only adding replicas
        TopicPartition tp = new TopicPartition("test-topic", 0);
        PartitionReassignment reassignment = new PartitionReassignment(
            List.of(1, 2, 3),
            List.of(3),  // Adding
            List.of()    // No removing
        );

        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp, reassignment));

        // When/Then: Should track correctly
        monitor.pollReassignments();

        assertThat(monitor.getActiveReassignments()).containsKey(tp);
        assertThat(monitor.getActiveReassignments().get(tp).addingReplicas()).containsExactly(3);
        assertThat(monitor.getActiveReassignments().get(tp).removingReplicas()).isEmpty();
    }

    // ========================================================================
    // Integration Scenario Tests
    // ========================================================================

    @Test
    @DisplayName("Should handle complete reassignment lifecycle scenario")
    void testScenario_CompleteLifecycle() throws Exception {
        // Scenario: Complete reassignment lifecycle from start to finish
        TopicPartition tp = new TopicPartition("test-topic", 0);
        PartitionReassignment reassignment = new PartitionReassignment(
            List.of(1, 2, 3),
            List.of(3),
            List.of(1)
        );

        // Phase 1: Start reassignment
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp, reassignment));
        monitor.pollReassignments();

        assertThat(monitor.getActiveReassignments()).hasSize(1);

        // Phase 2: Progress (bytes moving)
        javax.management.ObjectName objectName =
            new javax.management.ObjectName("kafka.server:type=ReassignmentMetrics,name=BytesMovedPerSec");
        when(mbsc.getAttribute(objectName, "Count")).thenReturn(1024L);
        monitor.pollReassignments();

        assertThat(monitor.getActiveReassignments().get(tp).bytesMoved()).isEqualTo(1024L);

        // Phase 3: More progress
        when(mbsc.getAttribute(objectName, "Count")).thenReturn(2048L);
        monitor.pollReassignments();

        assertThat(monitor.getActiveReassignments().get(tp).bytesMoved()).isEqualTo(2048L);

        // Phase 4: Complete
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of());
        monitor.pollReassignments();

        assertThat(monitor.getActiveReassignments()).isEmpty();
    }

    @Test
    @DisplayName("Should handle rapid reassignment changes")
    void testScenario_RapidChanges() throws Exception {
        // Scenario: Rapid successive reassignment operations
        TopicPartition tp1 = new TopicPartition("topic-1", 0);
        TopicPartition tp2 = new TopicPartition("topic-2", 0);
        TopicPartition tp3 = new TopicPartition("topic-3", 0);

        PartitionReassignment r1 = new PartitionReassignment(List.of(1, 2), List.of(3), List.of());
        PartitionReassignment r2 = new PartitionReassignment(List.of(2, 3), List.of(4), List.of());
        PartitionReassignment r3 = new PartitionReassignment(List.of(3, 4), List.of(5), List.of());

        // Poll 1: tp1 starts
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp1, r1));
        monitor.pollReassignments();
        assertThat(monitor.getActiveReassignments()).containsKey(tp1);

        // Poll 2: tp2 starts, tp1 ongoing
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp1, r1, tp2, r2));
        monitor.pollReassignments();
        assertThat(monitor.getActiveReassignments()).containsKeys(tp1, tp2);

        // Poll 3: tp3 starts, tp1 completes, tp2 ongoing
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of(tp2, r2, tp3, r3));
        monitor.pollReassignments();
        assertThat(monitor.getActiveReassignments()).containsKeys(tp2, tp3);
        assertThat(monitor.getActiveReassignments()).doesNotContainKey(tp1);

        // Poll 4: All complete
        when(reassignmentsFuture.get(anyLong(), any(TimeUnit.class)))
            .thenReturn(Map.of());
        monitor.pollReassignments();
        assertThat(monitor.getActiveReassignments()).isEmpty();
    }
}
