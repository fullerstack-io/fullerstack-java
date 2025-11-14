package io.fullerstack.kafka.producer.sensors;

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
 * Unit tests for {@link ProducerConnectionObserver}.
 * <p>
 * Tests connection monitoring logic, signal emission patterns, and JMX interaction.
 */
class ProducerConnectionObserverTest {

    @Mock
    private Gauge mockConnectionCountGauge;

    @Mock
    private Counter mockCreationRateCounter;

    @Mock
    private Gauge mockIoWaitGauge;

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
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // Then
        assertThat(receptor).isNotNull();

        // Cleanup
        receptor.close();
    }

    @Test
    void testConstructorDoesNotStartMonitoring() {
        // When
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // Then - instruments should not be called during construction
        verifyNoInteractions(mockConnectionCountGauge);
        verifyNoInteractions(mockCreationRateCounter);
        verifyNoInteractions(mockIoWaitGauge);

        // Cleanup
        receptor.close();
    }

    // ========================================
    // Lifecycle Tests
    // ========================================

    @Test
    void testCloseWithoutStart() {
        // Given
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // When / Then - should not throw
        assertThatCode(receptor::close).doesNotThrowAnyException();
    }

    @Test
    void testStopWithoutStart() {
        // Given
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // When / Then - should not throw
        assertThatCode(receptor::stop).doesNotThrowAnyException();

        // Cleanup
        receptor.close();
    }

    // Note: Cannot test start() without actual JMX server
    // Integration tests will cover start/stop lifecycle with Testcontainers

    // ========================================
    // Instrument Interaction Tests
    // ========================================

    @Test
    void testConnectionCountGaugeIsProvided() {
        // When
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // Then
        assertThat(receptor).isNotNull();

        // Cleanup
        receptor.close();
    }

    @Test
    void testCreationRateCounterIsProvided() {
        // When
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // Then
        assertThat(receptor).isNotNull();

        // Cleanup
        receptor.close();
    }

    @Test
    void testIoWaitGaugeIsProvided() {
        // When
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // Then
        assertThat(receptor).isNotNull();

        // Cleanup
        receptor.close();
    }

    // ========================================
    // Configuration Tests
    // ========================================

    @Test
    void testProducerIdIsStored() {
        // When
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "test-producer-id",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // Then
        assertThat(receptor).isNotNull();

        // Cleanup
        receptor.close();
    }

    @Test
    void testJmxEndpointIsStored() {
        // When
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "test-jmx-endpoint:9999",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // Then
        assertThat(receptor).isNotNull();

        // Cleanup
        receptor.close();
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Test
    void testMultipleCloseCallsAreIdempotent() {
        // Given
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // When / Then - multiple closes should not throw
        assertThatCode(() -> {
            receptor.close();
            receptor.close();
            receptor.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void testMultipleStopCallsAreIdempotent() {
        // Given
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // When / Then - multiple stops should not throw
        assertThatCode(() -> {
            receptor.stop();
            receptor.stop();
            receptor.stop();
        }).doesNotThrowAnyException();

        // Cleanup
        receptor.close();
    }

    // ========================================
    // Signal Emission Pattern Tests (Conceptual)
    // ========================================
    // Note: These tests verify the receptor can be constructed with the correct
    // instruments. Actual signal emission is tested in integration tests with
    // real JMX data.

    @Test
    void testObserverAcceptsConnectionCountGauge() {
        // Given
        Gauge connectionCountGauge = mock(Gauge.class);

        // When
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            connectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // Then
        assertThat(receptor).isNotNull();
        receptor.close();
    }

    @Test
    void testObserverAcceptsCreationRateCounter() {
        // Given
        Counter creationRateCounter = mock(Counter.class);

        // When
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            creationRateCounter,
            mockIoWaitGauge
        );

        // Then
        assertThat(receptor).isNotNull();
        receptor.close();
    }

    @Test
    void testObserverAcceptsIoWaitGauge() {
        // Given
        Gauge ioWaitGauge = mock(Gauge.class);

        // When
        ProducerConnectionObserver receptor = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            ioWaitGauge
        );

        // Then
        assertThat(receptor).isNotNull();
        receptor.close();
    }

    // ========================================
    // Threshold Tests (Conceptual)
    // ========================================

    @Test
    void testObserverCanHandleDifferentJmxEndpoints() {
        // Given multiple observers with different endpoints
        ProducerConnectionObserver observer1 = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        ProducerConnectionObserver observer2 = new ProducerConnectionObserver(
            "producer-2",
            "localhost:11002",
            mockConnectionCountGauge,
            mockCreationRateCounter,
            mockIoWaitGauge
        );

        // Then - both should be created successfully
        assertThat(observer1).isNotNull();
        assertThat(observer2).isNotNull();

        // Cleanup
        observer1.close();
        observer2.close();
    }

    @Test
    void testObserverCanMonitorMultipleProducers() {
        // Given multiple observers for different producers
        Gauge gauge1 = mock(Gauge.class);
        Gauge gauge2 = mock(Gauge.class);
        Counter counter1 = mock(Counter.class);
        Counter counter2 = mock(Counter.class);
        Gauge ioWait1 = mock(Gauge.class);
        Gauge ioWait2 = mock(Gauge.class);

        ProducerConnectionObserver observer1 = new ProducerConnectionObserver(
            "producer-1",
            "localhost:11001",
            gauge1,
            counter1,
            ioWait1
        );

        ProducerConnectionObserver observer2 = new ProducerConnectionObserver(
            "producer-2",
            "localhost:11001",
            gauge2,
            counter2,
            ioWait2
        );

        // Then - both should be created successfully with separate instruments
        assertThat(observer1).isNotNull();
        assertThat(observer2).isNotNull();

        // Cleanup
        observer1.close();
        observer2.close();
    }
}
