package io.fullerstack.serventis.composers;

import io.fullerstack.substrates.CortexRuntime;
import io.humainary.modules.serventis.services.api.Services;
import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServicesComposer}.
 * <p>
 * Verifies that the ServicesComposer correctly implements the Humainary Services interface,
 * creating Services.Service instances whose default methods emit the corresponding
 * Services.Signal enum values.
 * <p>
 * Note: ServicesComposer implements Services which extends Composer<Services.Service, Services.Signal>.
 * Since Services.Service is already a Pipe (not Pipe<Services.Service>), we test it directly
 * by calling compose() with a mock Channel and verifying signal emissions.
 */
class ServicesComposerTest {

    @Test
    void compose_createsServicesServiceInstance() {
        // Given: ServicesComposer and a mock Channel
        ServicesComposer composer = new ServicesComposer();
        Channel<Services.Signal> mockChannel = createMockChannel(signal -> {});

        // When: Calling compose()
        Services.Service service = composer.compose(mockChannel);

        // Then: Should return a Services.Service instance
        assertThat(service).isNotNull();
        assertThat(service).isInstanceOf(Services.Service.class);
    }

    @Test
    void call_emitsCallSignal() {
        // Given: ServicesComposer and Services.Service
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When: Calling call() method
        service.call();

        // Then: Should emit Services.Signal.CALL
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.CALL);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.CALL);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);
    }

    @Test
    void called_emitsCalledSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.called();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.CALLED);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.CALL);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void succeeded_emitsSucceededSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.succeeded();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.SUCCESS);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void success_emitsSuccessSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.success();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.SUCCESS);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.SUCCESS);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);
    }

    @Test
    void failed_emitsFailedSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.failed();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.FAILED);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.FAIL);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void fail_emitsFailSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.fail();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.FAIL);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.FAIL);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);
    }

    @Test
    void retry_emitsRetrySignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.retry();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.RETRY);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.RETRY);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);
    }

    @Test
    void retried_emitsRetriedSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.retried();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.RETRIED);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.RETRY);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void start_emitsStartSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.start();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.START);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.START);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);
    }

    @Test
    void started_emitsStartedSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.started();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.STARTED);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.START);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void stop_emitsStopSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.stop();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.STOP);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.STOP);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);
    }

    @Test
    void stopped_emitsStoppedSignal() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When
        service.stopped();

        // Then
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.STOPPED);
        assertThat(signals.get(0).sign()).isEqualTo(Services.Sign.STOP);
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void multipleMethodCalls_emitsSequenceOfSignals() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When: Calling multiple methods
        service.call();
        service.succeeded();
        service.call();
        service.failed();

        // Then: Should emit all signals in order
        assertThat(signals).hasSize(4);
        assertThat(signals.get(0)).isEqualTo(Services.Signal.CALL);
        assertThat(signals.get(1)).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(signals.get(2)).isEqualTo(Services.Signal.CALL);
        assertThat(signals.get(3)).isEqualTo(Services.Signal.FAILED);
    }

    @Test
    void producerLifecyclePattern_emitsCorrectSequence() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When: Simulating producer send/ack/fail lifecycle
        service.call();      // Producer sends
        service.succeeded(); // Broker acks
        service.call();      // Producer sends again
        service.failed();    // Broker rejects
        service.retry();     // Producer retries
        service.succeeded(); // Broker acks retry

        // Then: Should emit complete lifecycle sequence
        assertThat(signals).hasSize(6);
        assertThat(signals).containsExactly(
            Services.Signal.CALL,
            Services.Signal.SUCCEEDED,
            Services.Signal.CALL,
            Services.Signal.FAILED,
            Services.Signal.RETRY,
            Services.Signal.SUCCEEDED
        );
    }

    @Test
    void orientationSemantics_releaseVsReceipt() {
        // Given
        List<Services.Signal> signals = new ArrayList<>();
        ServicesComposer composer = new ServicesComposer();
        Services.Service service = composer.compose(createMockChannel(signals::add));

        // When: Calling present tense (RELEASE) and past participle (RECEIPT) methods
        service.call();      // Present tense → RELEASE
        service.called();    // Past participle → RECEIPT
        service.success();   // Present tense → RELEASE
        service.succeeded(); // Past participle → RECEIPT

        // Then: Orientations should match verb tense pattern
        assertThat(signals).hasSize(4);

        // CALL - present tense → RELEASE
        assertThat(signals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);

        // CALLED - past participle → RECEIPT
        assertThat(signals.get(1).orientation()).isEqualTo(Services.Orientation.RECEIPT);

        // SUCCESS - present tense → RELEASE
        assertThat(signals.get(2).orientation()).isEqualTo(Services.Orientation.RELEASE);

        // SUCCEEDED - past participle → RECEIPT
        assertThat(signals.get(3).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    // Helper methods

    /**
     * Creates a mock Channel that routes signals to the provided pipe.
     */
    @SuppressWarnings("unchecked")
    private Channel<Services.Signal> createMockChannel(Pipe<Services.Signal> outputPipe) {
        Channel<Services.Signal> mockChannel = mock(Channel.class);
        when(mockChannel.pipe()).thenReturn(outputPipe);
        return mockChannel;
    }
}
