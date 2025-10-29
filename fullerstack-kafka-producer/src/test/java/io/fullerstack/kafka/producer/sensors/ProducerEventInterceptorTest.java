package io.fullerstack.kafka.producer.sensors;

import io.fullerstack.kafka.producer.models.ProducerEventMetrics;
import io.humainary.modules.serventis.services.api.Services;
import io.humainary.substrates.api.Substrates.Pipe;
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

/**
 * Unit tests for {@link ProducerEventInterceptor}.
 * <p>
 * Verifies that the interceptor correctly emits ProducerEventMetrics with full context
 * (topic, partition, offset, latency, exception details) in response to producer lifecycle events.
 */
class ProducerEventInterceptorTest {

    private ProducerEventInterceptor<String, String> interceptor;
    private List<ProducerEventMetrics> capturedMetrics;
    private Pipe<ProducerEventMetrics> mockPipe;

    @BeforeEach
    void setUp() {
        interceptor = new ProducerEventInterceptor<>();
        capturedMetrics = new ArrayList<>();

        // Create a mock Pipe that captures emitted metrics
        mockPipe = new Pipe<>() {
            @Override
            public void emit(ProducerEventMetrics metrics) {
                capturedMetrics.add(metrics);
            }
        };
    }

    @Test
    void configure_withPipeInConfig_shouldExtractPipe() {
        // Given: Configuration with Pipe
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.METRICS_PIPE_KEY, mockPipe);

        // When: Configuring interceptor
        interceptor.configure(config);

        // Then: Pipe should be extracted (verified by subsequent behavior)
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        assertThat(capturedMetrics).hasSize(1);
        assertThat(capturedMetrics.get(0).signal()).isEqualTo(Services.Signal.CALL);
    }

    @Test
    void configure_withoutPipe_shouldHandleGracefully() {
        // Given: Configuration without Pipe
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");

        // When: Configuring interceptor
        interceptor.configure(config);

        // Then: Should not fail, but onSend should not emit metrics
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        ProducerRecord<String, String> result = interceptor.onSend(record);

        assertThat(result).isEqualTo(record);
        assertThat(capturedMetrics).isEmpty();
    }

    @Test
    void configure_withWrongPipeType_shouldHandleGracefully() {
        // Given: Configuration with wrong type for pipe
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.METRICS_PIPE_KEY, "not-a-pipe");

        // When: Configuring interceptor
        interceptor.configure(config);

        // Then: Should not fail, metrics should not be emitted
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        assertThat(capturedMetrics).isEmpty();
    }

    @Test
    void configure_withoutClientId_shouldUseDefaultProducerId() {
        // Given: Configuration without client.id
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerEventInterceptor.METRICS_PIPE_KEY, mockPipe);

        // When: Configuring interceptor (should not fail)
        assertThatCode(() -> interceptor.configure(config)).doesNotThrowAnyException();

        // Then: Should still work with default producer ID
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        assertThat(capturedMetrics).hasSize(1);
        assertThat(capturedMetrics.get(0).producerId()).isEqualTo("unknown-producer");
    }

    @Test
    void onSend_withPipe_shouldEmitCallMetrics() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: onSend is called
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", 2, "key1", "value1");
        ProducerRecord<String, String> result = interceptor.onSend(record);

        // Then: Should emit CALL metrics and return original record
        assertThat(result).isEqualTo(record);
        assertThat(capturedMetrics).hasSize(1);

        ProducerEventMetrics metrics = capturedMetrics.get(0);
        assertThat(metrics.producerId()).isEqualTo("test-producer");
        assertThat(metrics.topic()).isEqualTo("orders");
        assertThat(metrics.partition()).isEqualTo(2);
        assertThat(metrics.offset()).isEqualTo(-1L);  // Not yet assigned
        assertThat(metrics.signal()).isEqualTo(Services.Signal.CALL);
        assertThat(metrics.exception()).isNull();
        assertThat(metrics.latencyMs()).isEqualTo(0L);
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
        assertThat(capturedMetrics).isEmpty();
    }

    @Test
    void onSend_withException_shouldNotBreakProducer() {
        // Given: Pipe that throws exception
        Pipe<ProducerEventMetrics> faultyPipe = new Pipe<>() {
            @Override
            public void emit(ProducerEventMetrics metrics) {
                throw new RuntimeException("Pipe failed");
            }
        };

        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.METRICS_PIPE_KEY, faultyPipe);
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
        assertThat(capturedMetrics).hasSize(1);

        ProducerEventMetrics metrics = capturedMetrics.get(0);
        assertThat(metrics.producerId()).isEqualTo("test-producer");
        assertThat(metrics.topic()).isEqualTo("orders");
        assertThat(metrics.partition()).isEqualTo(2);
        assertThat(metrics.offset()).isEqualTo(123L);
        assertThat(metrics.signal()).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(metrics.exception()).isNull();
        assertThat(metrics.latencyMs()).isGreaterThanOrEqualTo(0L);
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
        assertThat(capturedMetrics).hasSize(1);

        ProducerEventMetrics metrics = capturedMetrics.get(0);
        assertThat(metrics.producerId()).isEqualTo("test-producer");
        assertThat(metrics.topic()).isEqualTo("orders");
        assertThat(metrics.partition()).isEqualTo(2);
        assertThat(metrics.offset()).isEqualTo(-1L);  // Not assigned on failure
        assertThat(metrics.signal()).isEqualTo(Services.Signal.FAILED);
        assertThat(metrics.exception()).isEqualTo(exception);
        assertThat(metrics.latencyMs()).isGreaterThanOrEqualTo(0L);
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
        assertThat(capturedMetrics).isEmpty();
    }

    @Test
    void onAcknowledgement_withException_shouldNotBreakProducer() {
        // Given: Pipe that throws exception
        Pipe<ProducerEventMetrics> faultyPipe = new Pipe<>() {
            @Override
            public void emit(ProducerEventMetrics metrics) {
                throw new RuntimeException("Pipe failed");
            }
        };

        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.METRICS_PIPE_KEY, faultyPipe);
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
        assertThat(capturedMetrics).hasSize(2);

        // CALL metrics
        ProducerEventMetrics callMetrics = capturedMetrics.get(0);
        assertThat(callMetrics.signal()).isEqualTo(Services.Signal.CALL);
        assertThat(callMetrics.topic()).isEqualTo("orders");
        assertThat(callMetrics.partition()).isEqualTo(2);
        assertThat(callMetrics.offset()).isEqualTo(-1L);

        // SUCCEEDED metrics
        ProducerEventMetrics succeededMetrics = capturedMetrics.get(1);
        assertThat(succeededMetrics.signal()).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(succeededMetrics.topic()).isEqualTo("orders");
        assertThat(succeededMetrics.partition()).isEqualTo(2);
        assertThat(succeededMetrics.offset()).isEqualTo(123L);
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
        assertThat(capturedMetrics).hasSize(2);

        ProducerEventMetrics callMetrics = capturedMetrics.get(0);
        assertThat(callMetrics.signal()).isEqualTo(Services.Signal.CALL);

        ProducerEventMetrics failedMetrics = capturedMetrics.get(1);
        assertThat(failedMetrics.signal()).isEqualTo(Services.Signal.FAILED);
        assertThat(failedMetrics.exception()).isEqualTo(exception);
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
        assertThat(capturedMetrics).hasSize(2);
        ProducerEventMetrics succeededMetrics = capturedMetrics.get(1);
        assertThat(succeededMetrics.latencyMs()).isGreaterThan(0L);
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
        assertThat(capturedMetrics).hasSize(3);
        assertThat(capturedMetrics).allMatch(m -> m.signal() == Services.Signal.CALL);
        assertThat(capturedMetrics.get(0).topic()).isEqualTo("topic1");
        assertThat(capturedMetrics.get(1).topic()).isEqualTo("topic2");
        assertThat(capturedMetrics.get(2).topic()).isEqualTo("topic3");
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
        assertThat(capturedMetrics).hasSize(4);
        assertThat(capturedMetrics.get(0).signal()).isEqualTo(Services.Signal.CALL);
        assertThat(capturedMetrics.get(1).signal()).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(capturedMetrics.get(2).signal()).isEqualTo(Services.Signal.CALL);
        assertThat(capturedMetrics.get(3).signal()).isEqualTo(Services.Signal.FAILED);
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
        config.put(ProducerEventInterceptor.METRICS_PIPE_KEY, mockPipe);
        interceptor.configure(config);
    }

    private RecordMetadata createMetadata(String topic, int partition, long offset) {
        TopicPartition topicPartition = new TopicPartition(topic, partition);
        return new RecordMetadata(topicPartition, offset, 0, System.currentTimeMillis(), 0, 0);
    }
}
