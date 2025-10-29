package io.fullerstack.kafka.producer.sensors;

import io.humainary.modules.serventis.services.api.Services;
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
 * Verifies that the interceptor correctly calls Services.Service methods
 * in response to producer lifecycle events (send, acknowledgement, failure).
 */
class ProducerEventInterceptorTest {

    private ProducerEventInterceptor<String, String> interceptor;
    private Services.Service mockService;
    private List<Services.Signal> emittedSignals;

    @BeforeEach
    void setUp() {
        interceptor = new ProducerEventInterceptor<>();
        emittedSignals = new ArrayList<>();

        // Create a mock Services.Service that tracks emitted signals
        mockService = createMockService();
    }

    @Test
    void configure_withServiceInConfig_shouldExtractService() {
        // Given: Configuration with Services.Service
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.SERVICE_CONFIG_KEY, mockService);

        // When: Configuring interceptor
        interceptor.configure(config);

        // Then: Service should be extracted (verified by subsequent behavior)
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        // Verify service.call() was invoked
        assertThat(emittedSignals).hasSize(1);
        assertThat(emittedSignals.get(0)).isEqualTo(Services.Signal.CALL);
    }

    @Test
    void configure_withoutService_shouldHandleGracefully() {
        // Given: Configuration without Services.Service
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");

        // When: Configuring interceptor
        interceptor.configure(config);

        // Then: Should not fail, but onSend should not emit signals
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        ProducerRecord<String, String> result = interceptor.onSend(record);

        assertThat(result).isEqualTo(record);
        assertThat(emittedSignals).isEmpty();
    }

    @Test
    void configure_withWrongServiceType_shouldHandleGracefully() {
        // Given: Configuration with wrong type for service
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.SERVICE_CONFIG_KEY, "not-a-service");

        // When: Configuring interceptor
        interceptor.configure(config);

        // Then: Should not fail, signals should not be emitted
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        assertThat(emittedSignals).isEmpty();
    }

    @Test
    void configure_withoutClientId_shouldUseDefaultProducerId() {
        // Given: Configuration without client.id
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerEventInterceptor.SERVICE_CONFIG_KEY, mockService);

        // When: Configuring interceptor (should not fail)
        assertThatCode(() -> interceptor.configure(config)).doesNotThrowAnyException();

        // Then: Should still work with default producer ID
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        assertThat(emittedSignals).hasSize(1);
    }

    @Test
    void onSend_withService_shouldEmitCallSignal() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: onSend is called
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        ProducerRecord<String, String> result = interceptor.onSend(record);

        // Then: Should emit CALL signal and return original record
        assertThat(result).isEqualTo(record);
        assertThat(emittedSignals).hasSize(1);
        assertThat(emittedSignals.get(0)).isEqualTo(Services.Signal.CALL);
        assertThat(emittedSignals.get(0).sign()).isEqualTo(Services.Sign.CALL);
        assertThat(emittedSignals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);
    }

    @Test
    void onSend_withoutService_shouldPassThrough() {
        // Given: Interceptor without service
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        interceptor.configure(config);

        // When: onSend is called
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        ProducerRecord<String, String> result = interceptor.onSend(record);

        // Then: Should return record unchanged, no signals emitted
        assertThat(result).isEqualTo(record);
        assertThat(emittedSignals).isEmpty();
    }

    @Test
    void onSend_withException_shouldNotBreakProducer() {
        // Given: Service that throws exception
        Services.Service faultyService = new Services.Service() {
            @Override
            public void emit(Services.Signal signal) {
                throw new RuntimeException("Service failed");
            }
        };

        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.SERVICE_CONFIG_KEY, faultyService);
        interceptor.configure(config);

        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");

        // When/Then: Should not throw exception
        assertThatCode(() -> interceptor.onSend(record)).doesNotThrowAnyException();
    }

    @Test
    void onAcknowledgement_withSuccessfulAck_shouldEmitSucceededSignal() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");
        RecordMetadata metadata = createMetadata("test-topic", 0, 123L);

        // When: onAcknowledgement called with no exception (successful ack)
        interceptor.onAcknowledgement(metadata, null);

        // Then: Should emit SUCCEEDED signal
        assertThat(emittedSignals).hasSize(1);
        assertThat(emittedSignals.get(0)).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(emittedSignals.get(0).sign()).isEqualTo(Services.Sign.SUCCESS);
        assertThat(emittedSignals.get(0).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void onAcknowledgement_withFailure_shouldEmitFailedSignal() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");
        RecordMetadata metadata = createMetadata("test-topic", 0, -1L);
        Exception exception = new RuntimeException("Broker timeout");

        // When: onAcknowledgement called with exception (failure)
        interceptor.onAcknowledgement(metadata, exception);

        // Then: Should emit FAILED signal
        assertThat(emittedSignals).hasSize(1);
        assertThat(emittedSignals.get(0)).isEqualTo(Services.Signal.FAILED);
        assertThat(emittedSignals.get(0).sign()).isEqualTo(Services.Sign.FAIL);
        assertThat(emittedSignals.get(0).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void onAcknowledgement_withoutService_shouldHandleGracefully() {
        // Given: Interceptor without service
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        interceptor.configure(config);

        RecordMetadata metadata = createMetadata("test-topic", 0, 123L);

        // When: onAcknowledgement called
        interceptor.onAcknowledgement(metadata, null);

        // Then: Should not emit signals
        assertThat(emittedSignals).isEmpty();
    }

    @Test
    void onAcknowledgement_withException_shouldNotBreakProducer() {
        // Given: Service that throws exception
        Services.Service faultyService = new Services.Service() {
            @Override
            public void emit(Services.Signal signal) {
                throw new RuntimeException("Service failed");
            }
        };

        Map<String, Object> config = new HashMap<>();
        config.put("client.id", "test-producer");
        config.put(ProducerEventInterceptor.SERVICE_CONFIG_KEY, faultyService);
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
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        RecordMetadata metadata = createMetadata("test-topic", 0, 123L);
        interceptor.onAcknowledgement(metadata, null);

        // Then: Should emit CALL followed by SUCCEEDED
        assertThat(emittedSignals).hasSize(2);
        assertThat(emittedSignals.get(0)).isEqualTo(Services.Signal.CALL);
        assertThat(emittedSignals.get(1)).isEqualTo(Services.Signal.SUCCEEDED);
    }

    @Test
    void producerLifecycle_sendAndFail_shouldEmitCorrectSequence() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: Simulating full send/fail lifecycle
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        RecordMetadata metadata = createMetadata("test-topic", 0, -1L);
        Exception exception = new RuntimeException("Network error");
        interceptor.onAcknowledgement(metadata, exception);

        // Then: Should emit CALL followed by FAILED
        assertThat(emittedSignals).hasSize(2);
        assertThat(emittedSignals.get(0)).isEqualTo(Services.Signal.CALL);
        assertThat(emittedSignals.get(1)).isEqualTo(Services.Signal.FAILED);
    }

    @Test
    void producerLifecycle_multipleSends_shouldEmitMultipleCallSignals() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: Multiple sends
        ProducerRecord<String, String> record1 = new ProducerRecord<>("topic1", "key1", "value1");
        ProducerRecord<String, String> record2 = new ProducerRecord<>("topic2", "key2", "value2");
        ProducerRecord<String, String> record3 = new ProducerRecord<>("topic3", "key3", "value3");

        interceptor.onSend(record1);
        interceptor.onSend(record2);
        interceptor.onSend(record3);

        // Then: Should emit three CALL signals
        assertThat(emittedSignals).hasSize(3);
        assertThat(emittedSignals).allMatch(signal -> signal == Services.Signal.CALL);
    }

    @Test
    void producerLifecycle_sendSuccessSendFail_shouldEmitCorrectSequence() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: First send succeeds, second fails
        ProducerRecord<String, String> record1 = new ProducerRecord<>("topic1", "key1", "value1");
        interceptor.onSend(record1);
        interceptor.onAcknowledgement(createMetadata("topic1", 0, 100L), null);

        ProducerRecord<String, String> record2 = new ProducerRecord<>("topic2", "key2", "value2");
        interceptor.onSend(record2);
        interceptor.onAcknowledgement(createMetadata("topic2", 0, -1L), new RuntimeException("Error"));

        // Then: Should emit CALL, SUCCEEDED, CALL, FAILED
        assertThat(emittedSignals).hasSize(4);
        assertThat(emittedSignals.get(0)).isEqualTo(Services.Signal.CALL);
        assertThat(emittedSignals.get(1)).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(emittedSignals.get(2)).isEqualTo(Services.Signal.CALL);
        assertThat(emittedSignals.get(3)).isEqualTo(Services.Signal.FAILED);
    }

    @Test
    void close_shouldNotThrow() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When/Then: close() should not throw
        assertThatCode(() -> interceptor.close()).doesNotThrowAnyException();
    }

    @Test
    void orientationSemantics_callVsSucceeded_shouldUseCorrectOrientations() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: Send and acknowledge
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        RecordMetadata metadata = createMetadata("test-topic", 0, 123L);
        interceptor.onAcknowledgement(metadata, null);

        // Then: CALL should be RELEASE (present), SUCCEEDED should be RECEIPT (past)
        assertThat(emittedSignals).hasSize(2);

        // CALL - present tense → RELEASE orientation
        assertThat(emittedSignals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);

        // SUCCEEDED - past participle → RECEIPT orientation
        assertThat(emittedSignals.get(1).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    @Test
    void orientationSemantics_callVsFailed_shouldUseCorrectOrientations() {
        // Given: Configured interceptor
        configureInterceptor("test-producer");

        // When: Send and fail
        ProducerRecord<String, String> record = new ProducerRecord<>("test-topic", "key", "value");
        interceptor.onSend(record);

        RecordMetadata metadata = createMetadata("test-topic", 0, -1L);
        interceptor.onAcknowledgement(metadata, new RuntimeException("Error"));

        // Then: CALL should be RELEASE, FAILED should be RECEIPT
        assertThat(emittedSignals).hasSize(2);

        // CALL - present tense → RELEASE orientation
        assertThat(emittedSignals.get(0).orientation()).isEqualTo(Services.Orientation.RELEASE);

        // FAILED - past participle → RECEIPT orientation
        assertThat(emittedSignals.get(1).orientation()).isEqualTo(Services.Orientation.RECEIPT);
    }

    // Helper methods

    /**
     * Configure the interceptor with a test producer ID and mock service.
     */
    private void configureInterceptor(String producerId) {
        Map<String, Object> config = new HashMap<>();
        config.put("client.id", producerId);
        config.put(ProducerEventInterceptor.SERVICE_CONFIG_KEY, mockService);
        interceptor.configure(config);
    }

    /**
     * Create a mock Services.Service that tracks emitted signals.
     */
    private Services.Service createMockService() {
        return new Services.Service() {
            @Override
            public void emit(Services.Signal signal) {
                emittedSignals.add(signal);
            }
        };
    }

    /**
     * Create RecordMetadata for testing.
     */
    private RecordMetadata createMetadata(String topic, int partition, long offset) {
        TopicPartition topicPartition = new TopicPartition(topic, partition);
        return new RecordMetadata(topicPartition, offset, 0, System.currentTimeMillis(), 0, 0);
    }
}
