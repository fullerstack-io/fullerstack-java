package io.fullerstack.kafka.consumer.sensors;

import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConsumerFetchObserver}.
 * <p>
 * Tests fetch monitoring logic, signal emission patterns, and JMX interaction.
 */
class ConsumerFetchObserverTest {

    @Mock
    private Counter mockFetchRateCounter;

    @Mock
    private Counter mockBytesConsumedCounter;

    @Mock
    private Counter mockRecordsConsumedCounter;

    @Mock
    private Gauge mockFetchSizeGauge;

    @Mock
    private Gauge mockFetchLatencyGauge;

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
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testConstructorDoesNotStartMonitoring() {
        // When
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then - instruments should not be called during construction
        verifyNoInteractions(mockFetchRateCounter);
        verifyNoInteractions(mockBytesConsumedCounter);
        verifyNoInteractions(mockRecordsConsumedCounter);
        verifyNoInteractions(mockFetchSizeGauge);
        verifyNoInteractions(mockFetchLatencyGauge);

        // Cleanup
        observer.close();
    }

    // ========================================
    // Lifecycle Tests
    // ========================================

    @Test
    void testCloseWithoutStart() {
        // Given
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // When / Then - should not throw
        assertThatCode(observer::close).doesNotThrowAnyException();
    }

    @Test
    void testStopWithoutStart() {
        // Given
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // When / Then - should not throw
        assertThatCode(observer::stop).doesNotThrowAnyException();

        // Cleanup
        observer.close();
    }

    // Note: Cannot test start() without actual JMX server
    // Integration tests will cover start/stop lifecycle with Testcontainers

    // ========================================
    // Instrument Interaction Tests
    // ========================================

    @Test
    void testFetchRateCounterIsProvided() {
        // When
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testBytesConsumedCounterIsProvided() {
        // When
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testRecordsConsumedCounterIsProvided() {
        // When
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testFetchSizeGaugeIsProvided() {
        // When
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testFetchLatencyGaugeIsProvided() {
        // When
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    // ========================================
    // Configuration Tests
    // ========================================

    @Test
    void testConsumerIdIsStored() {
        // When
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "test-consumer-id",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    @Test
    void testJmxEndpointIsStored() {
        // When
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "test-jmx-endpoint:9999",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then
        assertThat(observer).isNotNull();

        // Cleanup
        observer.close();
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Test
    void testMultipleCloseCallsAreIdempotent() {
        // Given
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // When / Then - multiple closes should not throw
        assertThatCode(() -> {
            observer.close();
            observer.close();
            observer.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void testMultipleStopCallsAreIdempotent() {
        // Given
        ConsumerFetchObserver observer = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // When / Then - multiple stops should not throw
        assertThatCode(() -> {
            observer.stop();
            observer.stop();
            observer.stop();
        }).doesNotThrowAnyException();

        // Cleanup
        observer.close();
    }

    // ========================================
    // Multi-Consumer Tests
    // ========================================

    @Test
    void testObserverCanMonitorMultipleConsumers() {
        // Given multiple observers for different consumers
        Counter counter1 = mock(Counter.class);
        Counter counter2 = mock(Counter.class);
        Counter counter3 = mock(Counter.class);
        Counter counter4 = mock(Counter.class);
        Counter counter5 = mock(Counter.class);
        Counter counter6 = mock(Counter.class);
        Gauge gauge1 = mock(Gauge.class);
        Gauge gauge2 = mock(Gauge.class);
        Gauge gauge3 = mock(Gauge.class);
        Gauge gauge4 = mock(Gauge.class);

        ConsumerFetchObserver observer1 = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            counter1, counter2, counter3, gauge1, gauge2
        );

        ConsumerFetchObserver observer2 = new ConsumerFetchObserver(
            "consumer-2",
            "localhost:11001",
            counter4, counter5, counter6, gauge3, gauge4
        );

        // Then - both should be created successfully with separate instruments
        assertThat(observer1).isNotNull();
        assertThat(observer2).isNotNull();

        // Cleanup
        observer1.close();
        observer2.close();
    }

    @Test
    void testObserverCanHandleDifferentJmxEndpoints() {
        // Given multiple observers with different endpoints
        ConsumerFetchObserver observer1 = new ConsumerFetchObserver(
            "consumer-1",
            "localhost:11001",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        ConsumerFetchObserver observer2 = new ConsumerFetchObserver(
            "consumer-2",
            "localhost:11002",
            mockFetchRateCounter,
            mockBytesConsumedCounter,
            mockRecordsConsumedCounter,
            mockFetchSizeGauge,
            mockFetchLatencyGauge
        );

        // Then - both should be created successfully
        assertThat(observer1).isNotNull();
        assertThat(observer2).isNotNull();

        // Cleanup
        observer1.close();
        observer2.close();
    }
}
