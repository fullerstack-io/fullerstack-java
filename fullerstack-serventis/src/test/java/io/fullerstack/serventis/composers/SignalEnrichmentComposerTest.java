package io.fullerstack.serventis.composers;

import io.fullerstack.serventis.signals.ServiceSignal;
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
 * Unit tests for {@link SignalEnrichmentComposer}.
 * <p>
 * Verifies that the SignalEnrichmentComposer correctly enriches bare Services.Signal enum values
 * with Substrates metadata (Subject, VectorClock, UUID, timestamp) to create full ServiceSignal records.
 */
class SignalEnrichmentComposerTest {

    @Test
    void compose_createsEnrichmentPipe() {
        // Given: SignalEnrichmentComposer and a mock Channel
        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Channel<ServiceSignal> mockChannel = createMockChannel(signal -> {});

        // When: Calling compose()
        Pipe<Services.Signal> inputPipe = composer.compose(mockChannel);

        // Then: Should return a Pipe that accepts Services.Signal
        assertThat(inputPipe).isNotNull();
        assertThat(inputPipe).isInstanceOf(Pipe.class);
    }

    @Test
    void emit_enrichesSignalWithSubject() {
        // Given: SignalEnrichmentComposer and collecting output
        List<ServiceSignal> signals = new ArrayList<>();
        Cortex cortex = CortexRuntime.cortex();
        Name channelName = cortex.name("producer-1");
        // Create a mock Subject for testing
        @SuppressWarnings({"unchecked", "rawtypes"})
        Subject testSubject = mock(Subject.class);
        when(testSubject.name()).thenReturn(channelName);

        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Pipe<Services.Signal> inputPipe = composer.compose(createMockChannel(signals::add, testSubject));

        // When: Emitting a Services.Signal
        inputPipe.emit(Services.Signal.CALL);

        // Then: Should create ServiceSignal with Subject
        assertThat(signals.size()).isEqualTo(1);
        ServiceSignal enriched = signals.get(0);
        assertThat((Object) enriched.subject()).isEqualTo((Object) testSubject);
    }

    @Test
    void emit_preservesServicesSignal() {
        // Given
        List<ServiceSignal> signals = new ArrayList<>();
        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Pipe<Services.Signal> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting CALL signal
        inputPipe.emit(Services.Signal.CALL);

        // Then: ServiceSignal should preserve the original Services.Signal
        assertThat(signals).hasSize(1);
        ServiceSignal enriched = signals.get(0);
        assertThat(enriched.signal()).isEqualTo(Services.Signal.CALL);
        assertThat(enriched.signal().sign()).isEqualTo(Services.Sign.CALL);
        assertThat(enriched.signal().orientation()).isEqualTo(Services.Orientation.RELEASE);
    }

    @Test
    void emit_addsUniqueId() {
        // Given
        List<ServiceSignal> signals = new ArrayList<>();
        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Pipe<Services.Signal> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting multiple signals
        inputPipe.emit(Services.Signal.CALL);
        inputPipe.emit(Services.Signal.SUCCEEDED);

        // Then: Each ServiceSignal should have a unique ID
        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).id()).isNotNull();
        assertThat(signals.get(1).id()).isNotNull();
        assertThat(signals.get(0).id()).isNotEqualTo(signals.get(1).id());
    }

    @Test
    void emit_addsTimestamp() {
        // Given
        List<ServiceSignal> signals = new ArrayList<>();
        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Pipe<Services.Signal> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting a signal
        inputPipe.emit(Services.Signal.CALL);

        // Then: ServiceSignal should have a timestamp
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).timestamp()).isNotNull();
    }

    @Test
    void emit_initializesVectorClock() {
        // Given
        List<ServiceSignal> signals = new ArrayList<>();
        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Pipe<Services.Signal> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting a signal
        inputPipe.emit(Services.Signal.CALL);

        // Then: ServiceSignal should have a VectorClock (initially empty)
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).vectorClock()).isNotNull();
    }

    @Test
    void emit_initializesPayload() {
        // Given
        List<ServiceSignal> signals = new ArrayList<>();
        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Pipe<Services.Signal> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting a signal
        inputPipe.emit(Services.Signal.CALL);

        // Then: ServiceSignal should have a payload map (may be empty)
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).payload()).isNotNull();
    }

    @Test
    void emit_transformsMultipleSignalTypes() {
        // Given
        List<ServiceSignal> signals = new ArrayList<>();
        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Pipe<Services.Signal> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting different signal types
        inputPipe.emit(Services.Signal.CALL);
        inputPipe.emit(Services.Signal.SUCCEEDED);
        inputPipe.emit(Services.Signal.FAILED);
        inputPipe.emit(Services.Signal.RETRY);

        // Then: All signals should be enriched correctly
        assertThat(signals).hasSize(4);
        assertThat(signals.get(0).signal()).isEqualTo(Services.Signal.CALL);
        assertThat(signals.get(1).signal()).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(signals.get(2).signal()).isEqualTo(Services.Signal.FAILED);
        assertThat(signals.get(3).signal()).isEqualTo(Services.Signal.RETRY);

        // All should have enrichment metadata - check each signal individually
        assertThat(signals.get(0).id()).isNotNull();
        assertThat((Object) signals.get(0).subject()).isNotNull();
        assertThat(signals.get(0).timestamp()).isNotNull();
        assertThat(signals.get(0).vectorClock()).isNotNull();
        assertThat(signals.get(0).payload()).isNotNull();

        assertThat(signals.get(1).id()).isNotNull();
        assertThat(signals.get(2).id()).isNotNull();
        assertThat(signals.get(3).id()).isNotNull();
    }

    @Test
    void emit_preservesOrientation() {
        // Given
        List<ServiceSignal> signals = new ArrayList<>();
        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Pipe<Services.Signal> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting RELEASE and RECEIPT oriented signals
        inputPipe.emit(Services.Signal.CALL);      // RELEASE
        inputPipe.emit(Services.Signal.CALLED);    // RECEIPT
        inputPipe.emit(Services.Signal.SUCCESS);   // RELEASE
        inputPipe.emit(Services.Signal.SUCCEEDED); // RECEIPT

        // Then: Orientations should be preserved
        assertThat(signals).hasSize(4);
        assertThat(signals.get(0).signal().orientation()).isEqualTo(Services.Orientation.RELEASE);
        assertThat(signals.get(1).signal().orientation()).isEqualTo(Services.Orientation.RECEIPT);
        assertThat(signals.get(2).signal().orientation()).isEqualTo(Services.Orientation.RELEASE);
        assertThat(signals.get(3).signal().orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void emit_producerLifecycleEnrichment() {
        // Given
        List<ServiceSignal> signals = new ArrayList<>();
        SignalEnrichmentComposer composer = new SignalEnrichmentComposer();
        Pipe<Services.Signal> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting producer lifecycle signals
        inputPipe.emit(Services.Signal.CALL);      // Producer sends
        inputPipe.emit(Services.Signal.SUCCEEDED); // Broker acks
        inputPipe.emit(Services.Signal.CALL);      // Producer sends again
        inputPipe.emit(Services.Signal.FAILED);    // Broker rejects

        // Then: All lifecycle signals should be enriched
        assertThat(signals).hasSize(4);

        // Verify sequence
        assertThat(signals.get(0).signal()).isEqualTo(Services.Signal.CALL);
        assertThat(signals.get(1).signal()).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(signals.get(2).signal()).isEqualTo(Services.Signal.CALL);
        assertThat(signals.get(3).signal()).isEqualTo(Services.Signal.FAILED);

        // Each should have unique ID but share subject
        Subject firstSubject = signals.get(0).subject();
        assertThat((Object) signals.get(0).subject()).isEqualTo((Object) firstSubject);
        assertThat((Object) signals.get(1).subject()).isEqualTo((Object) firstSubject);
        assertThat((Object) signals.get(2).subject()).isEqualTo((Object) firstSubject);
        assertThat((Object) signals.get(3).subject()).isEqualTo((Object) firstSubject);

        assertThat(signals.get(0).id()).isNotNull();
        assertThat(signals.get(1).id()).isNotNull();
        assertThat(signals.get(2).id()).isNotNull();
        assertThat(signals.get(3).id()).isNotNull();

        // IDs should all be unique
        assertThat(signals.get(0).id()).isNotEqualTo(signals.get(1).id());
        assertThat(signals.get(1).id()).isNotEqualTo(signals.get(2).id());
        assertThat(signals.get(2).id()).isNotEqualTo(signals.get(3).id());
    }

    // Helper methods

    /**
     * Creates a mock Channel with default test Subject that routes signals to the provided pipe.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Channel<ServiceSignal> createMockChannel(Pipe<ServiceSignal> outputPipe) {
        Cortex cortex = CortexRuntime.cortex();
        // Create a simple mock Subject for testing
        Subject testSubject = mock(Subject.class);
        when(testSubject.name()).thenReturn(cortex.name("test-channel"));
        return createMockChannel(outputPipe, testSubject);
    }

    /**
     * Creates a mock Channel with custom Subject that routes signals to the provided pipe.
     */
    @SuppressWarnings("unchecked")
    private Channel<ServiceSignal> createMockChannel(Pipe<ServiceSignal> outputPipe, Subject customSubject) {
        Channel<ServiceSignal> mockChannel = mock(Channel.class);
        when(mockChannel.pipe()).thenReturn(outputPipe);
        when(mockChannel.subject()).thenReturn((Subject<Channel<ServiceSignal>>) (Subject) customSubject);
        return mockChannel;
    }
}
