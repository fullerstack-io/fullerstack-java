package io.fullerstack.kafka.runtime.queue;

import io.humainary.substrates.ext.serventis.Queues.Queue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link QueueFlowCircuit}.
 * <p>
 * Validates RC1 Serventis pattern: Queue instrument with overflow/underflow/put/take method calls.
 */
class QueueFlowCircuitTest {

    private QueueFlowCircuit circuit;

    @BeforeEach
    void setUp() {
        circuit = new QueueFlowCircuit();
    }

    @AfterEach
    void tearDown() {
        if (circuit != null) {
            circuit.close();
        }
    }

    // ========================================
    // Circuit Creation Tests
    // ========================================

    @Test
    void testCircuitCreationSucceeds() {
        // When
        QueueFlowCircuit testCircuit = new QueueFlowCircuit();

        // Then
        assertThat(testCircuit).isNotNull();

        // Cleanup
        testCircuit.close();
    }

    @Test
    void testCircuitCloseDoesNotThrow() {
        // Given
        QueueFlowCircuit testCircuit = new QueueFlowCircuit();

        // When / Then
        assertThatCode(testCircuit::close).doesNotThrowAnyException();
    }

    // ========================================
    // Queue Instrument Tests
    // ========================================

    @Test
    void testQueueForReturnsValidQueue() {
        // When
        Queue queue = circuit.queueFor("producer-1.buffer");

        // Then
        assertThat(queue).isNotNull();
    }

    @Test
    void testQueueForDifferentEntitiesReturnsDifferentQueues() {
        // When
        Queue queue1 = circuit.queueFor("producer-1.buffer");
        Queue queue2 = circuit.queueFor("consumer-group-1.lag");

        // Then
        assertThat(queue1).isNotNull();
        assertThat(queue2).isNotNull();
        // Queues for different entities should be different instances
        assertThat(queue1).isNotSameAs(queue2);
    }

    // ========================================
    // Signal Emission Tests (RC1 Instrument Pattern)
    // ========================================

    @Test
    void testCanCallOverflowMethod() {
        // Given
        Queue queue = circuit.queueFor("test-entity");

        // When / Then - should not throw
        assertThatCode(() -> queue.overflow()).doesNotThrowAnyException();
    }

    @Test
    void testCanCallOverflowWithUnits() {
        // Given
        Queue queue = circuit.queueFor("test-entity");

        // When / Then - should not throw
        assertThatCode(() -> queue.overflow(95L)).doesNotThrowAnyException();
    }

    @Test
    void testCanCallPutMethod() {
        // Given
        Queue queue = circuit.queueFor("test-entity");

        // When / Then - should not throw
        assertThatCode(() -> queue.put()).doesNotThrowAnyException();
    }

    @Test
    void testCanCallPutWithUnits() {
        // Given
        Queue queue = circuit.queueFor("test-entity");

        // When / Then - should not throw
        assertThatCode(() -> queue.put(50L)).doesNotThrowAnyException();
    }

    @Test
    void testCanCallUnderflowMethod() {
        // Given
        Queue queue = circuit.queueFor("test-entity");

        // When / Then - should not throw
        assertThatCode(() -> queue.underflow()).doesNotThrowAnyException();
    }

    @Test
    void testCanCallTakeMethod() {
        // Given
        Queue queue = circuit.queueFor("test-entity");

        // When / Then - should not throw
        assertThatCode(() -> queue.take()).doesNotThrowAnyException();
    }

    @Test
    void testMultipleEmissionsToSameQueue() {
        // Given
        Queue queue = circuit.queueFor("producer-1.buffer");

        // When / Then - multiple calls should not throw
        assertThatCode(() -> {
            queue.put();
            queue.put(60L);
            queue.overflow(90L);
            queue.overflow(95L);
        }).doesNotThrowAnyException();
    }

    // ========================================
    // Entity Name Pattern Tests
    // ========================================

    @Test
    void testProducerBufferEntityNaming() {
        // When
        Queue queue1 = circuit.queueFor("producer-1.buffer");
        Queue queue2 = circuit.queueFor("producer-2.buffer");

        // Then
        assertThat(queue1).isNotNull();
        assertThat(queue2).isNotNull();
        assertThat(queue1).isNotSameAs(queue2);
    }

    @Test
    void testConsumerLagEntityNaming() {
        // When
        Queue queue1 = circuit.queueFor("consumer-group-1.lag");
        Queue queue2 = circuit.queueFor("consumer-group-2.lag");

        // Then
        assertThat(queue1).isNotNull();
        assertThat(queue2).isNotNull();
        assertThat(queue1).isNotSameAs(queue2);
    }

    // ========================================
    // RC1 Compliance Tests
    // ========================================

    @Test
    void testQueueIsInstrumentNotPipe() {
        // Given
        Queue queue = circuit.queueFor("test-entity");

        // Then - Queue instrument has specific methods
        assertThat(queue).isNotNull();
        assertThat(queue).isInstanceOf(Queue.class);

        // RC1 pattern: instruments have typed methods, not generic emit()
        assertThatCode(() -> {
            queue.overflow();
            queue.underflow();
            queue.put();
            queue.take();
        }).doesNotThrowAnyException();
    }
}
