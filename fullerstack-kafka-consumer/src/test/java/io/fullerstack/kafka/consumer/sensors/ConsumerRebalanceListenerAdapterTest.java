package io.fullerstack.kafka.consumer.sensors;

import io.humainary.serventis.services.Services;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConsumerRebalanceListenerAdapter}.
 * <p>
 * Validates RC1 Serventis pattern: SUSPEND/RESUME signal emissions during rebalance.
 */
class ConsumerRebalanceListenerAdapterTest {

    private Services.Service mockService;
    private ConsumerRebalanceListenerAdapter listener;

    @BeforeEach
    void setUp() {
        mockService = Mockito.mock(Services.Service.class);
    }

    // ========================================
    // Constructor Tests
    // ========================================

    @Test
    void testConstructorWithService() {
        // When
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            mockService
        );

        // Then
        assertThat(listener).isNotNull();
    }

    @Test
    void testConstructorWithoutService() {
        // When
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            null  // No service
        );

        // Then - should not throw, but log warning
        assertThat(listener).isNotNull();
    }

    @Test
    void testConstructorWithNullIds() {
        // When
        listener = new ConsumerRebalanceListenerAdapter(
            null,  // null consumer ID
            null,  // null group
            mockService
        );

        // Then - uses default "unknown-consumer", "unknown-group"
        assertThat(listener).isNotNull();
    }

    // ========================================
    // onPartitionsRevoked() Tests - SUSPEND
    // ========================================

    @Test
    void testOnPartitionsRevoked_EmitsSuspendSignal() {
        // Given
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            mockService
        );

        Collection<TopicPartition> partitions = Arrays.asList(
            new TopicPartition("test-topic", 0),
            new TopicPartition("test-topic", 1),
            new TopicPartition("test-topic", 2)
        );

        // When
        listener.onPartitionsRevoked(partitions);

        // Then
        verify(mockService, times(1)).suspend();  // "I am suspending"
        verify(mockService, never()).resume();
        verify(mockService, never()).call();
        verify(mockService, never()).success();
        verify(mockService, never()).fail();
    }

    @Test
    void testOnPartitionsRevokedWithEmptyPartitions_EmitsSuspendSignal() {
        // Given
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            mockService
        );

        Collection<TopicPartition> emptyPartitions = Collections.emptyList();

        // When
        listener.onPartitionsRevoked(emptyPartitions);

        // Then - still emits suspend (rebalance happening even if no partitions)
        verify(mockService, times(1)).suspend();
    }

    @Test
    void testOnPartitionsRevokedWithoutService_NoSignalsEmitted() {
        // Given - listener without service
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            null  // No service
        );

        Collection<TopicPartition> partitions = Arrays.asList(
            new TopicPartition("test-topic", 0)
        );

        // When
        listener.onPartitionsRevoked(partitions);

        // Then - no signals emitted (no NPE)
        verify(mockService, never()).suspend();
    }

    @Test
    void testOnPartitionsRevokedWithException_DoesNotPropagateError() {
        // Given - service that throws exception
        Services.Service faultyService = mock(Services.Service.class);
        doThrow(new RuntimeException("Test exception")).when(faultyService).suspend();

        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            faultyService
        );

        Collection<TopicPartition> partitions = Arrays.asList(
            new TopicPartition("test-topic", 0)
        );

        // When - should not throw exception
        assertThatCode(() -> listener.onPartitionsRevoked(partitions))
            .doesNotThrowAnyException();

        // Then
        verify(faultyService, times(1)).suspend();  // Exception thrown but caught
    }

    // ========================================
    // onPartitionsAssigned() Tests - RESUME
    // ========================================

    @Test
    void testOnPartitionsAssigned_EmitsResumeSignal() {
        // Given
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            mockService
        );

        Collection<TopicPartition> partitions = Arrays.asList(
            new TopicPartition("test-topic", 0),
            new TopicPartition("test-topic", 1)
        );

        // When
        listener.onPartitionsAssigned(partitions);

        // Then
        verify(mockService, times(1)).resume();  // "I am resuming"
        verify(mockService, never()).suspend();
        verify(mockService, never()).call();
        verify(mockService, never()).success();
        verify(mockService, never()).fail();
    }

    @Test
    void testOnPartitionsAssignedWithEmptyPartitions_EmitsResumeSignal() {
        // Given
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            mockService
        );

        Collection<TopicPartition> emptyPartitions = Collections.emptyList();

        // When
        listener.onPartitionsAssigned(emptyPartitions);

        // Then - still emits resume (rebalance completed even if no partitions assigned)
        verify(mockService, times(1)).resume();
    }

    @Test
    void testOnPartitionsAssignedWithoutService_NoSignalsEmitted() {
        // Given - listener without service
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            null  // No service
        );

        Collection<TopicPartition> partitions = Arrays.asList(
            new TopicPartition("test-topic", 0)
        );

        // When
        listener.onPartitionsAssigned(partitions);

        // Then - no signals emitted (no NPE)
        verify(mockService, never()).resume();
    }

    @Test
    void testOnPartitionsAssignedWithException_DoesNotPropagateError() {
        // Given - service that throws exception
        Services.Service faultyService = mock(Services.Service.class);
        doThrow(new RuntimeException("Test exception")).when(faultyService).resume();

        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            faultyService
        );

        Collection<TopicPartition> partitions = Arrays.asList(
            new TopicPartition("test-topic", 0)
        );

        // When - should not throw exception
        assertThatCode(() -> listener.onPartitionsAssigned(partitions))
            .doesNotThrowAnyException();

        // Then
        verify(faultyService, times(1)).resume();  // Exception thrown but caught
    }

    // ========================================
    // Complete Rebalance Cycle Tests
    // ========================================

    @Test
    void testCompleteRebalanceCycle_EmitsSuspendThenResume() {
        // Given
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            mockService
        );

        Collection<TopicPartition> oldPartitions = Arrays.asList(
            new TopicPartition("test-topic", 0),
            new TopicPartition("test-topic", 1)
        );

        Collection<TopicPartition> newPartitions = Arrays.asList(
            new TopicPartition("test-topic", 0)  // Lost partition 1
        );

        // When - simulate complete rebalance
        listener.onPartitionsRevoked(oldPartitions);    // Step 1: Revoke
        listener.onPartitionsAssigned(newPartitions);   // Step 2: Assign

        // Then - both signals emitted in order
        verify(mockService, times(1)).suspend();
        verify(mockService, times(1)).resume();

        // Verify order: suspend() called before resume()
        var inOrder = inOrder(mockService);
        inOrder.verify(mockService).suspend();
        inOrder.verify(mockService).resume();
    }

    @Test
    void testMultipleRebalances_EmitsSignalsForEach() {
        // Given
        listener = new ConsumerRebalanceListenerAdapter(
            "test-consumer",
            "test-group",
            mockService
        );

        Collection<TopicPartition> partitions = Arrays.asList(
            new TopicPartition("test-topic", 0)
        );

        // When - multiple rebalances (e.g., consumer joining/leaving)
        listener.onPartitionsRevoked(partitions);
        listener.onPartitionsAssigned(partitions);

        listener.onPartitionsRevoked(partitions);
        listener.onPartitionsAssigned(partitions);

        listener.onPartitionsRevoked(partitions);
        listener.onPartitionsAssigned(partitions);

        // Then - signals emitted for each rebalance
        verify(mockService, times(3)).suspend();
        verify(mockService, times(3)).resume();
    }
}
