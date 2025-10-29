package io.fullerstack.kafka.producer.composers;

import io.fullerstack.kafka.producer.models.ProducerEventMetrics;
import io.fullerstack.serventis.signals.ServiceSignal;
import io.fullerstack.substrates.CortexRuntime;
import io.humainary.modules.serventis.services.api.Services;
import io.humainary.substrates.api.Substrates.*;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProducerEventComposer}.
 * <p>
 * Verifies that ProducerEventMetrics is correctly transformed into enriched ServiceSignal.
 */
class ProducerEventComposerTest {

    @Test
    void compose_createsInputPipe() {
        // Given: ProducerEventComposer and mock Channel
        ProducerEventComposer composer = new ProducerEventComposer();
        Channel<ServiceSignal> mockChannel = createMockChannel(signal -> {});

        // When: Calling compose()
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(mockChannel);

        // Then: Should return a Pipe that accepts ProducerEventMetrics
        assertThat(inputPipe).isNotNull();
        assertThat(inputPipe).isInstanceOf(Pipe.class);
    }

    @Test
    void emit_callMetrics_transformsToServiceSignal() {
        // Given: Composer with collecting output
        List<ServiceSignal> signals = new ArrayList<>();
        ProducerEventComposer composer = new ProducerEventComposer();
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting CALL metrics
        ProducerEventMetrics metrics = ProducerEventMetrics.call("producer-1", "test-topic", 2);
        inputPipe.emit(metrics);

        // Then: Should create ServiceSignal with CALL
        assertThat(signals).hasSize(1);
        ServiceSignal signal = signals.get(0);

        assertThat(signal.signal()).isEqualTo(Services.Signal.CALL);
        assertThat(signal.signal().sign()).isEqualTo(Services.Sign.CALL);
        assertThat(signal.signal().orientation()).isEqualTo(Services.Orientation.RELEASE);
        assertThat(signal.id()).isNotNull();
        assertThat((Object) signal.subject()).isNotNull();
        assertThat(signal.payload()).isNotEmpty();
    }

    @Test
    void emit_succeededMetrics_transformsToServiceSignal() {
        // Given: Composer with collecting output
        List<ServiceSignal> signals = new ArrayList<>();
        ProducerEventComposer composer = new ProducerEventComposer();
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting SUCCEEDED metrics
        RecordMetadata metadata = createMetadata("test-topic", 2, 123L, 100, 200, 1234567890L);
        ProducerEventMetrics metrics = ProducerEventMetrics.succeeded("producer-1", metadata, 150L);
        inputPipe.emit(metrics);

        // Then: Should create ServiceSignal with SUCCEEDED
        assertThat(signals).hasSize(1);
        ServiceSignal signal = signals.get(0);

        assertThat(signal.signal()).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(signal.signal().sign()).isEqualTo(Services.Sign.SUCCESS);
        assertThat(signal.signal().orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void emit_failedMetrics_transformsToServiceSignal() {
        // Given: Composer with collecting output
        List<ServiceSignal> signals = new ArrayList<>();
        ProducerEventComposer composer = new ProducerEventComposer();
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting FAILED metrics
        Exception exception = new RuntimeException("Network timeout");
        ProducerEventMetrics metrics = ProducerEventMetrics.failed(
            "producer-1", "test-topic", 2, exception, 75L
        );
        inputPipe.emit(metrics);

        // Then: Should create ServiceSignal with FAILED
        assertThat(signals).hasSize(1);
        ServiceSignal signal = signals.get(0);

        assertThat(signal.signal()).isEqualTo(Services.Signal.FAILED);
        assertThat(signal.signal().sign()).isEqualTo(Services.Sign.FAIL);
        assertThat(signal.signal().orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void emit_preservesContextInPayload() {
        // Given: Composer with collecting output
        List<ServiceSignal> signals = new ArrayList<>();
        ProducerEventComposer composer = new ProducerEventComposer();
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting SUCCEEDED metrics with full context
        RecordMetadata metadata = createMetadata("orders", 5, 999L, 150, 300, 1700000000L);
        ProducerEventMetrics metrics = ProducerEventMetrics.succeeded("producer-42", metadata, 250L);
        inputPipe.emit(metrics);

        // Then: ServiceSignal payload should preserve all context
        assertThat(signals).hasSize(1);
        ServiceSignal signal = signals.get(0);

        assertThat(signal.payload()).containsEntry("producerId", "producer-42");
        assertThat(signal.payload()).containsEntry("topic", "orders");
        assertThat(signal.payload()).containsEntry("partition", "5");
        assertThat(signal.payload()).containsEntry("offset", "999");
        assertThat(signal.payload()).containsEntry("latencyMs", "250");
        assertThat(signal.payload()).containsKey("timestamp");

        // Metadata from RecordMetadata
        assertThat(signal.payload()).containsEntry("serializedKeySize", "150");
        assertThat(signal.payload()).containsEntry("serializedValueSize", "300");
        assertThat(signal.payload()).containsEntry("brokerTimestamp", "1700000000");
    }

    @Test
    void emit_failedMetrics_preservesExceptionDetails() {
        // Given: Composer with collecting output
        List<ServiceSignal> signals = new ArrayList<>();
        ProducerEventComposer composer = new ProducerEventComposer();
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting FAILED metrics with exception
        Exception cause = new IllegalStateException("Broker unreachable");
        Exception exception = new RuntimeException("Send failed", cause);
        ProducerEventMetrics metrics = ProducerEventMetrics.failed(
            "producer-1", "test-topic", 2, exception, 75L
        );
        inputPipe.emit(metrics);

        // Then: ServiceSignal should preserve exception details
        assertThat(signals).hasSize(1);
        ServiceSignal signal = signals.get(0);

        assertThat(signal.payload()).containsEntry("exceptionType", "java.lang.RuntimeException");
        assertThat(signal.payload()).containsEntry("exceptionMessage", "Send failed");
        assertThat(signal.payload()).containsKey("exceptionCause");
        assertThat(signal.payload().get("exceptionCause").toString())
            .contains("IllegalStateException")
            .contains("Broker unreachable");

        // Also includes error type/message from metrics metadata
        assertThat(signal.payload()).containsEntry("errorType", "RuntimeException");
        assertThat(signal.payload()).containsEntry("errorMessage", "Send failed");
    }

    @Test
    void emit_generatesUniqueIds() {
        // Given: Composer with collecting output
        List<ServiceSignal> signals = new ArrayList<>();
        ProducerEventComposer composer = new ProducerEventComposer();
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting multiple metrics
        inputPipe.emit(ProducerEventMetrics.call("producer-1", "topic-1", 0));
        inputPipe.emit(ProducerEventMetrics.call("producer-1", "topic-2", 1));
        inputPipe.emit(ProducerEventMetrics.call("producer-1", "topic-3", 2));

        // Then: Each ServiceSignal should have unique ID
        assertThat(signals).hasSize(3);
        assertThat(signals.get(0).id()).isNotNull();
        assertThat(signals.get(1).id()).isNotNull();
        assertThat(signals.get(2).id()).isNotNull();

        assertThat(signals.get(0).id()).isNotEqualTo(signals.get(1).id());
        assertThat(signals.get(1).id()).isNotEqualTo(signals.get(2).id());
        assertThat(signals.get(0).id()).isNotEqualTo(signals.get(2).id());
    }

    @Test
    void emit_propagatesSubjectFromChannel() {
        // Given: Mock channel with test subject
        Cortex cortex = CortexRuntime.cortex();
        Name channelName = cortex.name("producer-cell");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Subject testSubject = mock(Subject.class);
        when(testSubject.name()).thenReturn(channelName);

        List<ServiceSignal> signals = new ArrayList<>();
        Channel<ServiceSignal> mockChannel = createMockChannel(signals::add, testSubject);

        ProducerEventComposer composer = new ProducerEventComposer();
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(mockChannel);

        // When: Emitting metrics
        inputPipe.emit(ProducerEventMetrics.call("producer-1", "test-topic", 0));

        // Then: ServiceSignal should use Subject from Channel
        assertThat(signals).hasSize(1);
        assertThat((Object) signals.get(0).subject()).isEqualTo((Object) testSubject);
    }

    @Test
    void emit_producerLifecycleSequence_preservesContextAcrossSignals() {
        // Given: Composer with collecting output
        List<ServiceSignal> signals = new ArrayList<>();
        ProducerEventComposer composer = new ProducerEventComposer();
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting full producer lifecycle (CALL → SUCCEEDED)
        inputPipe.emit(ProducerEventMetrics.call("producer-1", "orders", 2));

        RecordMetadata metadata = createMetadata("orders", 2, 500L, 100, 200, 1234567890L);
        inputPipe.emit(ProducerEventMetrics.succeeded("producer-1", metadata, 120L));

        // Then: Both signals should preserve context
        assertThat(signals).hasSize(2);

        // CALL signal
        ServiceSignal callSignal = signals.get(0);
        assertThat(callSignal.signal()).isEqualTo(Services.Signal.CALL);
        assertThat(callSignal.payload().get("topic")).isEqualTo("orders");
        assertThat(callSignal.payload().get("partition")).isEqualTo("2");
        assertThat(callSignal.payload().get("offset")).isEqualTo("-1");

        // SUCCEEDED signal
        ServiceSignal succeededSignal = signals.get(1);
        assertThat(succeededSignal.signal()).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(succeededSignal.payload().get("topic")).isEqualTo("orders");
        assertThat(succeededSignal.payload().get("partition")).isEqualTo("2");
        assertThat(succeededSignal.payload().get("offset")).isEqualTo("500");
        assertThat(succeededSignal.payload().get("latencyMs")).isEqualTo("120");
    }

    @Test
    void emit_initializesVectorClock() {
        // Given: Composer with collecting output
        List<ServiceSignal> signals = new ArrayList<>();
        ProducerEventComposer composer = new ProducerEventComposer();
        Pipe<ProducerEventMetrics> inputPipe = composer.compose(createMockChannel(signals::add));

        // When: Emitting metrics
        inputPipe.emit(ProducerEventMetrics.call("producer-1", "test-topic", 0));

        // Then: ServiceSignal should have VectorClock (empty initially)
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).vectorClock()).isNotNull();
        // VectorClock doesn't have isEmpty(), just check it's not null
    }

    // Helper methods

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Channel<ServiceSignal> createMockChannel(Pipe<ServiceSignal> outputPipe) {
        Cortex cortex = CortexRuntime.cortex();
        Subject testSubject = mock(Subject.class);
        when(testSubject.name()).thenReturn(cortex.name("test-channel"));
        return createMockChannel(outputPipe, testSubject);
    }

    @SuppressWarnings("unchecked")
    private Channel<ServiceSignal> createMockChannel(Pipe<ServiceSignal> outputPipe, Subject customSubject) {
        Channel<ServiceSignal> mockChannel = mock(Channel.class);
        when(mockChannel.pipe()).thenReturn(outputPipe);
        when(mockChannel.subject()).thenReturn((Subject<Channel<ServiceSignal>>) (Subject) customSubject);
        return mockChannel;
    }

    private RecordMetadata createMetadata(
        String topic,
        int partition,
        long offset,
        int keySize,
        int valueSize,
        long timestamp
    ) {
        TopicPartition topicPartition = new TopicPartition(topic, partition);
        return new RecordMetadata(
            topicPartition,
            offset,
            0,
            timestamp,
            keySize,
            valueSize
        );
    }
}
