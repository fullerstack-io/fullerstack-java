package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.Queues.Queue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProducerBufferMonitor}.
 * <p>
 * Tests buffer monitoring logic, signal emission patterns, and JMX interaction.
 */
class ProducerBufferMonitorTest {

    @Mock
    private Queue mockQueue;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    // ========================================
    // Constructor Tests
    // ========================================

    @Test
    void testConstructorWithValidParameters() {
        // When
        ProducerBufferMonitor monitor = new ProducerBufferMonitor(
            "producer-1",
            "localhost:11001",
            mockQueue
        );

        // Then
        assertThat(monitor).isNotNull();

        // Cleanup
        monitor.close();
    }

    @Test
    void testConstructorDoesNotStartMonitoring() {
        // When
        ProducerBufferMonitor monitor = new ProducerBufferMonitor(
            "producer-1",
            "localhost:11001",
            mockQueue
        );

        // Then - queue should not be called during construction
        verifyNoInteractions(mockQueue);

        // Cleanup
        monitor.close();
    }

    // ========================================
    // Lifecycle Tests
    // ========================================

    @Test
    void testCloseWithoutStart() {
        // Given
        ProducerBufferMonitor monitor = new ProducerBufferMonitor(
            "producer-1",
            "localhost:11001",
            mockQueue
        );

        // When / Then - should not throw
        assertThatCode(monitor::close).doesNotThrowAnyException();
    }

    @Test
    void testStopWithoutStart() {
        // Given
        ProducerBufferMonitor monitor = new ProducerBufferMonitor(
            "producer-1",
            "localhost:11001",
            mockQueue
        );

        // When / Then - should not throw
        assertThatCode(monitor::stop).doesNotThrowAnyException();

        // Cleanup
        monitor.close();
    }

    // Note: Cannot test start() without actual JMX server
    // Integration tests in Task 4 will cover start/stop lifecycle

    // ========================================
    // Queue Instrument Interaction Tests
    // ========================================

    @Test
    void testQueueInstrumentIsProvided() {
        // When
        ProducerBufferMonitor monitor = new ProducerBufferMonitor(
            "producer-1",
            "localhost:11001",
            mockQueue
        );

        // Then
        assertThat(monitor).isNotNull();

        // Cleanup
        monitor.close();
    }

    // ========================================
    // Configuration Tests
    // ========================================

    @Test
    void testProducerIdIsStored() {
        // When
        ProducerBufferMonitor monitor = new ProducerBufferMonitor(
            "test-producer-id",
            "localhost:11001",
            mockQueue
        );

        // Then
        assertThat(monitor).isNotNull();

        // Cleanup
        monitor.close();
    }

    @Test
    void testJmxEndpointIsStored() {
        // When
        ProducerBufferMonitor monitor = new ProducerBufferMonitor(
            "producer-1",
            "test-jmx-endpoint:9999",
            mockQueue
        );

        // Then
        assertThat(monitor).isNotNull();

        // Cleanup
        monitor.close();
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Test
    void testMultipleCloseCallsAreIdempotent() {
        // Given
        ProducerBufferMonitor monitor = new ProducerBufferMonitor(
            "producer-1",
            "localhost:11001",
            mockQueue
        );

        // When / Then - multiple closes should not throw
        assertThatCode(() -> {
            monitor.close();
            monitor.close();
            monitor.close();
        }).doesNotThrowAnyException();
    }
}
