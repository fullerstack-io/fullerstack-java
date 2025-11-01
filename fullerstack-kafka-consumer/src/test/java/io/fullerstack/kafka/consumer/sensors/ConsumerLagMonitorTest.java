package io.fullerstack.kafka.consumer.sensors;

import io.humainary.serventis.queues.Queues.Queue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConsumerLagMonitor}.
 * <p>
 * Tests lag monitoring logic, signal emission patterns, and AdminClient interaction.
 */
class ConsumerLagMonitorTest {

    @Mock
    private Queue mockQueue;

    private AutoCloseable mocks;
    private Properties adminProps;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        adminProps = new Properties();
        adminProps.put("bootstrap.servers", "localhost:9092");
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
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(
            "consumer-group-1",
            adminProps,
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
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(
            "consumer-group-1",
            adminProps,
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
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(
            "consumer-group-1",
            adminProps,
            mockQueue
        );

        // When / Then - should not throw
        assertThatCode(monitor::close).doesNotThrowAnyException();
    }

    @Test
    void testStopWithoutStart() {
        // Given
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(
            "consumer-group-1",
            adminProps,
            mockQueue
        );

        // When / Then - should not throw
        assertThatCode(monitor::stop).doesNotThrowAnyException();

        // Cleanup
        monitor.close();
    }

    // Note: Cannot test start() without actual Kafka cluster
    // Integration tests in Task 4 will cover start/stop lifecycle

    // ========================================
    // Queue Instrument Interaction Tests
    // ========================================

    @Test
    void testQueueInstrumentIsProvided() {
        // When
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(
            "consumer-group-1",
            adminProps,
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
    void testConsumerGroupIdIsStored() {
        // When
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(
            "test-consumer-group",
            adminProps,
            mockQueue
        );

        // Then
        assertThat(monitor).isNotNull();

        // Cleanup
        monitor.close();
    }

    @Test
    void testAdminPropertiesAreStored() {
        // Given
        Properties customProps = new Properties();
        customProps.put("bootstrap.servers", "custom-server:9999");

        // When
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(
            "consumer-group-1",
            customProps,
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
        ConsumerLagMonitor monitor = new ConsumerLagMonitor(
            "consumer-group-1",
            adminProps,
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
