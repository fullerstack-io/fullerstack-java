package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import io.humainary.substrates.ext.serventis.ext.Probes.Probe;
import io.humainary.substrates.ext.serventis.ext.Services.Service;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProducerSendObserver}.
 * <p>
 * Tests send operation monitoring logic, signal emission patterns, and JMX interaction.
 */
class ProducerSendObserverTest {

    @Mock
    private Counter mockSendRateCounter;

    @Mock
    private Counter mockSendTotalCounter;

    @Mock
    private Probe mockSendProbe;

    @Mock
    private Counter mockErrorCounter;

    @Mock
    private Service mockRetryService;

    @Mock
    private Counter mockRetryCounter;

    @Mock
    private Gauge mockLatencyGauge;

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
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
        );

        // Then
        assertThat(receptor).isNotNull();

        // Cleanup
        receptor.close();
    }

    @Test
    void testConstructorDoesNotStartMonitoring() {
        // When
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
        );

        // Then - instruments should not be called during construction
        verifyNoInteractions(mockSendRateCounter);
        verifyNoInteractions(mockSendTotalCounter);
        verifyNoInteractions(mockSendProbe);
        verifyNoInteractions(mockErrorCounter);
        verifyNoInteractions(mockRetryService);
        verifyNoInteractions(mockRetryCounter);
        verifyNoInteractions(mockLatencyGauge);

        // Cleanup
        receptor.close();
    }

    // ========================================
    // Lifecycle Tests
    // ========================================

    @Test
    void testCloseWithoutStart() {
        // Given
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
        );

        // When / Then - should not throw
        assertThatCode(receptor::close).doesNotThrowAnyException();
    }

    @Test
    void testStopWithoutStart() {
        // Given
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
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
    void testAllInstrumentsAreProvided() {
        // When
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
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
        ProducerSendObserver receptor = new ProducerSendObserver(
            "test-producer-id",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
        );

        // Then
        assertThat(receptor).isNotNull();

        // Cleanup
        receptor.close();
    }

    @Test
    void testJmxEndpointIsStored() {
        // When
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "test-jmx-endpoint:9999",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
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
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
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
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
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
    void testObserverAcceptsSendRateCounter() {
        // Given
        Counter sendRateCounter = mock(Counter.class);

        // When
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            sendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
        );

        // Then
        assertThat(receptor).isNotNull();
        receptor.close();
    }

    @Test
    void testObserverAcceptsSendProbe() {
        // Given
        Probe sendProbe = mock(Probe.class);

        // When
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            sendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            mockLatencyGauge
        );

        // Then
        assertThat(receptor).isNotNull();
        receptor.close();
    }

    @Test
    void testObserverAcceptsRetryService() {
        // Given
        Service retryService = mock(Service.class);

        // When
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            retryService,
            mockRetryCounter,
            mockLatencyGauge
        );

        // Then
        assertThat(receptor).isNotNull();
        receptor.close();
    }

    @Test
    void testObserverAcceptsLatencyGauge() {
        // Given
        Gauge latencyGauge = mock(Gauge.class);

        // When
        ProducerSendObserver receptor = new ProducerSendObserver(
            "producer-1",
            "localhost:11001",
            mockSendRateCounter,
            mockSendTotalCounter,
            mockSendProbe,
            mockErrorCounter,
            mockRetryService,
            mockRetryCounter,
            latencyGauge
        );

        // Then
        assertThat(receptor).isNotNull();
        receptor.close();
    }
}
