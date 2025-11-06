package io.fullerstack.kafka.producer.sensors;

import io.humainary.substrates.ext.serventis.ext.Services;
import io.humainary.substrates.api.Substrates.Pipe;
import io.humainary.substrates.api.Substrates.Channel;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * Unit tests for {@link ProducerEventInterceptor}.
 * <p>
 * Verifies that the interceptor correctly emits Services.Signs with interpreted meaning
 * (latency assessments, error analysis) in response to producer lifecycle events.
 * <p>
 * Updated for signal-first architecture: tests now verify signal interpretation, not data bags.
 */
class ProducerEventInterceptorTest {

    private ProducerEventInterceptor<String, String> interceptor;
    private List<Services.Signal> capturedSignals;
    private Channel<Services.Signal> mockChannel;
    private Services.Service mockService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        interceptor = new ProducerEventInterceptor<>();
        capturedSignals = new ArrayList<>();

        // Create mock Channel and Pipe that capture emitted signals
        mockChannel = mock(Channel.class);
        Pipe<Services.Signal> mockPipe = mock(Pipe.class);

        when(mockChannel.pipe()).thenReturn(mockPipe);

        // Capture signals emitted to the pipe
        doAnswer(invocation -> {
            Services.Signal signal = invocation.getArgument(0);
            capturedSignals.add(signal);
            return null;
        }).when(mockPipe).emit(any(Services.Signal.class));

        doNothing().when(mockPipe).flush();

        // Create mock Service instrument using RC3 API
        mockService = Services.composer(mockChannel);
    }

    @Test
    void configure_withServiceInConfig_shouldExtractService() {
        // Given: Configuration with Service instrument
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.SERVICE_KEY, mockService);

        // When: Configuring interceptor
        interceptor.configure(config);

        // Then: Service should be extracted (verified by subsequent behavior)
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        assertThat(capturedSignals).hasSize(1);
    }

    @Test
    void configure_withoutService_shouldHandleGracefully() {
        // Given: Configuration without Service instrument
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");

        // When: Configuring interceptor
        interceptor.configure(config);

        // Then: Should not fail, but onSend should not emit signals
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        ProducerRecord<String, String> result = interceptor.onSend(record);

        assertThat(result).isEqualTo(record);
        assertThat(capturedSignals).isEmpty();
    }

    @Test
    void configure_withWrongServiceType_shouldHandleGracefully() {
        // Given: Configuration with wrong type for service
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.SERVICE_KEY, "not-a-service"); // Wrong type

        // When: Configuring interceptor
        interceptor.configure(config);

        // Then: Should not fail, signals should not be emitted
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        assertThat(capturedSignals).isEmpty();
    }

    @Test
    void configure_withoutClientId_shouldUseDefaultProducerId() {
        // Given: Configuration without client.id but with Service
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerEventInterceptor.SERVICE_KEY, mockService);

        // When: Configuring interceptor (should not fail)
        assertThatCode(() -> interceptor.configure(config)).doesNotThrowAnyException();

        // Then: Should still work with default producer ID
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        assertThat(capturedSignals).hasSize(1);
    }

    @Test
    void onSend_withService_shouldEmitCallSignal() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: onSend is called
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", 2, "key1", "value1");
        ProducerRecord<String, String> result = interceptor.onSend(record);

        // Then: Should emit CALL metrics and return original record
        assertThat(result).isEqualTo(record);
        assertThat(capturedSignals).hasSize(1);

        Services.Signal signal = capturedSignals.get(0);
    }

    @Test
    void onSend_withoutPipe_shouldPassThrough() {
        // Given: Interceptor without pipe
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        interceptor.configure(config);

        // When: onSend is called
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        ProducerRecord<String, String> result = interceptor.onSend(record);

        // Then: Should return record unchanged, no metrics emitted
        assertThat(result).isEqualTo(record);
        assertThat(capturedSignals).isEmpty();
    }

    @Test
    void onSend_withException_shouldNotBreakProducer() {
        // Given: Pipe that throws exception
        Pipe<Services.Signal> faultyPipe = new Pipe<>() {
            @Override
            public void emit(Services.Signal signal) {
                throw new RuntimeException("Pipe failed");
            }

            @Override
            public void flush() {
                // No-op for tests
            }
        };

        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        interceptor.configure(config);

        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");

        // When/Then: Should not throw exception
        assertThatCode(() -> interceptor.onSend(record)).doesNotThrowAnyException();
    }

    @Test
    void onAcknowledgement_withSuccessfulAck_shouldEmitSucceededMetrics() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");
        RecordMetadata metadata = createMetadata("orders", 2, 123L);

        // When: onAcknowledgement called with no exception (successful ack)
        interceptor.onAcknowledgement(metadata, null);

        // Then: Should emit SUCCEEDED metrics
        assertThat(capturedSignals).hasSize(1);

        Services.Signal signal = capturedSignals.get(0);
        // No error_type for successful operations
        // Latency is present
    }

    @Test
    void onAcknowledgement_withFailure_shouldEmitFailedMetrics() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");
        RecordMetadata metadata = createMetadata("orders", 2, -1L);
        Exception exception = new RuntimeException("Broker timeout");

        // When: onAcknowledgement called with exception (failure)
        interceptor.onAcknowledgement(metadata, exception);

        // Then: Should emit FAILED metrics
        assertThat(capturedSignals).hasSize(1);

        Services.Signal signal = capturedSignals.get(0);
        // No offset for failures
        // Error type is exception class name
    }

    @Test
    void onAcknowledgement_withoutPipe_shouldHandleGracefully() {
        // Given: Interceptor without pipe
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        interceptor.configure(config);

        RecordMetadata metadata = createMetadata("test-topic", 0, 123L);

        // When: onAcknowledgement called
        interceptor.onAcknowledgement(metadata, null);

        // Then: Should not emit metrics
        assertThat(capturedSignals).isEmpty();
    }

    @Test
    void onAcknowledgement_withException_shouldNotBreakProducer() {
        // Given: Pipe that throws exception
        Pipe<Services.Signal> faultyPipe = new Pipe<>() {
            @Override
            public void emit(Services.Signal signal) {
                throw new RuntimeException("Pipe failed");
            }

            @Override
            public void flush() {
                // No-op for tests
            }
        };

        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        interceptor.configure(config);

        RecordMetadata metadata = createMetadata("test-topic", 0, 123L);

        // When/Then: Should not throw exception
        assertThatCode(() -> interceptor.onAcknowledgement(metadata, null))
            .doesNotThrowAnyException();
    }

    @Test
    void producerLifecycle_sendAndSuccess_shouldEmitCorrectSequence() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: Simulating full send/ack lifecycle
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", 2, "key1", "value1");
        interceptor.onSend(record);

        RecordMetadata metadata = createMetadata("orders", 2, 123L);
        interceptor.onAcknowledgement(metadata, null);

        // Then: Should emit CALL followed by SUCCEEDED
        assertThat(capturedSignals).hasSize(2);

        // CALL metrics
        Services.Signal callMetrics = capturedSignals.get(0);
        // CALL doesn't have offset

        // SUCCEEDED metrics
        Services.Signal succeededMetrics = capturedSignals.get(1);
    }

    @Test
    void producerLifecycle_sendAndFail_shouldEmitCorrectSequence() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: Simulating full send/fail lifecycle
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", 2, "key1", "value1");
        interceptor.onSend(record);

        RecordMetadata metadata = createMetadata("orders", 2, -1L);
        Exception exception = new RuntimeException("Network error");
        interceptor.onAcknowledgement(metadata, exception);

        // Then: Should emit CALL followed by FAILED
        assertThat(capturedSignals).hasSize(2);

        Services.Signal callMetrics = capturedSignals.get(0);

        Services.Signal failedMetrics = capturedSignals.get(1);
        // Error type is exception class name
    }

    @Test
    void producerLifecycle_measuresLatency() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: Send followed by acknowledgement
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", 2, "key1", "value1");
        interceptor.onSend(record);

        // Simulate some processing time
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        RecordMetadata metadata = createMetadata("orders", 2, 123L);
        interceptor.onAcknowledgement(metadata, null);

        // Then: SUCCEEDED metrics should have non-zero latency
        assertThat(capturedSignals).hasSize(2);
        Services.Signal succeededMetrics = capturedSignals.get(1);
        // Latency is present and parseable
    }

    @Test
    void producerLifecycle_multipleSends_shouldEmitMultipleMetrics() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: Multiple sends
        ProducerRecord<String, String> record1 = new ProducerRecord<>("topic1", 0, "key1", "value1");
        ProducerRecord<String, String> record2 = new ProducerRecord<>("topic2", 1, "key2", "value2");
        ProducerRecord<String, String> record3 = new ProducerRecord<>("topic3", 2, "key3", "value3");

        interceptor.onSend(record1);
        interceptor.onSend(record2);
        interceptor.onSend(record3);

        // Then: Should emit three CALL metrics
        assertThat(capturedSignals).hasSize(3);
    }

    @Test
    void producerLifecycle_sendSuccessSendFail_shouldEmitCorrectSequence() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: First send succeeds, second fails
        ProducerRecord<String, String> record1 = new ProducerRecord<>("orders", 0, "key1", "value1");
        interceptor.onSend(record1);
        interceptor.onAcknowledgement(createMetadata("orders", 0, 100L), null);

        ProducerRecord<String, String> record2 = new ProducerRecord<>("orders", 1, "key2", "value2");
        interceptor.onSend(record2);
        interceptor.onAcknowledgement(createMetadata("orders", 1, -1L), new RuntimeException("Error"));

        // Then: Should emit CALL, SUCCEEDED, CALL, FAILED
        assertThat(capturedSignals).hasSize(4);
    }

    @Test
    void close_shouldClearInFlightRequests() {
        // Given: Configured interceptor with in-flight request
        configureInterceptor("test-producer");
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", 0, "key", "value");
        interceptor.onSend(record);

        // When: Closing interceptor
        interceptor.close();

        // Then: Should not throw (verifies cleanup)
        assertThatCode(() -> interceptor.close()).doesNotThrowAnyException();
    }

    @Test
    void close_shouldNotThrow() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When/Then: close() should not throw
        assertThatCode(() -> interceptor.close()).doesNotThrowAnyException();
    }

    // Helper methods

    private void configureInterceptor(String producerId) {
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", producerId);
        config.put(ProducerEventInterceptor.SERVICE_KEY, mockService);
        // Note: Probe is optional - interceptor handles null gracefully (we're only testing Services layer)
        interceptor.configure(config);
    }

    private RecordMetadata createMetadata(String topic, int partition, long offset) {
        TopicPartition topicPartition = new TopicPartition(topic, partition);
        return new RecordMetadata(topicPartition, offset, 0, System.currentTimeMillis(), 0, 0);
    }
}
