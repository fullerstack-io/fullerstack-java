package io.fullerstack.kafka.consumer.sensors;

import io.humainary.substrates.ext.serventis.Services;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConsumerEventInterceptor}.
 * <p>
 * Validates RC1 Serventis pattern: simple enum signal emissions, no metadata, no VectorClock.
 */
class ConsumerEventInterceptorTest {

    private ConsumerEventInterceptor<String, String> interceptor;
    private Services.Service mockService;

    @BeforeEach
    void setUp() {
        interceptor = new ConsumerEventInterceptor<>();
        mockService = Mockito.mock(Services.Service.class);
    }

    // ========================================
    // Configuration Tests
    // ========================================

    @Test
    void testConfigureWithService() {
        // Given
        Map<String, Object> config = new HashMap<>();
        config.put("group.id", "test-group");
        config.put("client.id", "test-consumer");
        config.put(ConsumerEventInterceptor.SERVICE_KEY, mockService);

        // When
        interceptor.configure(config);

        // Then - no exception, logging confirms configuration
        assertThat(interceptor).isNotNull();
    }

    @Test
    void testConfigureWithoutService() {
        // Given
        Map<String, Object> config = new HashMap<>();
        config.put("group.id", "test-group");
        config.put("client.id", "test-consumer");
        // No SERVICE_KEY

        // When
        assertThatCode(() -> interceptor.configure(config))
            .doesNotThrowAnyException();

        // Then - interceptor works but doesn't emit signals (warning logged)
    }

    @Test
    void testConfigureWithMissingConsumerIds() {
        // Given
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerEventInterceptor.SERVICE_KEY, mockService);
        // No group.id, no client.id

        // When
        interceptor.configure(config);

        // Then - uses defaults "unknown-group", "unknown-consumer"
        assertThat(interceptor).isNotNull();
    }

    // ========================================
    // onConsume() Tests - Poll Success
    // ========================================

    @Test
    void testOnConsumeWithRecords_EmitsCallAndSuccess() {
        // Given
        Map<String, Object> config = configWithService();
        interceptor.configure(config);

        ConsumerRecords<String, String> records = createRecords(10);

        // When
        ConsumerRecords<String, String> result = interceptor.onConsume(records);

        // Then
        assertThat(result).isSameAs(records);  // Interceptor doesn't modify records
        verify(mockService, times(1)).call();     // "I am polling"
        verify(mockService, times(1)).success();  // "I succeeded"
        verify(mockService, never()).fail();
    }

    @Test
    void testOnConsumeWithEmptyRecords_EmitsCallAndSuccess() {
        // Given
        Map<String, Object> config = configWithService();
        interceptor.configure(config);

        ConsumerRecords<String, String> emptyRecords = createEmptyRecords();

        // When
        ConsumerRecords<String, String> result = interceptor.onConsume(emptyRecords);

        // Then - empty poll within reasonable time is still SUCCESS
        assertThat(result).isSameAs(emptyRecords);
        verify(mockService, times(1)).call();
        verify(mockService, times(1)).success();  // Quick empty poll = success
        verify(mockService, never()).fail();
    }

    // ========================================
    // onConsume() Tests - Poll Failure (Timeout)
    // ========================================

    @Test
    void testOnConsumeWithTimeout_EmitsCallAndFail() throws InterruptedException {
        // Given
        Map<String, Object> config = configWithService();
        interceptor.configure(config);

        ConsumerRecords<String, String> emptyRecords = createEmptyRecords();

        // First poll establishes baseline time
        interceptor.onConsume(emptyRecords);
        reset(mockService);  // Clear first poll signals

        // Simulate long delay (>5 seconds = timeout)
        Thread.sleep(5100);

        // When - second poll after long delay
        ConsumerRecords<String, String> result = interceptor.onConsume(emptyRecords);

        // Then - timeout detected
        assertThat(result).isSameAs(emptyRecords);
        verify(mockService, times(1)).call();
        verify(mockService, times(1)).fail();    // Timeout = failure
        verify(mockService, never()).success();
    }

    // ========================================
    // onConsume() Tests - Without Service
    // ========================================

    @Test
    void testOnConsumeWithoutService_NoSignalsEmitted() {
        // Given - configured without service
        Map<String, Object> config = new HashMap<>();
        config.put("group.id", "test-group");
        config.put("client.id", "test-consumer");
        interceptor.configure(config);

        ConsumerRecords<String, String> records = createRecords(5);

        // When
        ConsumerRecords<String, String> result = interceptor.onConsume(records);

        // Then - no signals emitted (no NPE)
        assertThat(result).isSameAs(records);
        verify(mockService, never()).call();
        verify(mockService, never()).success();
        verify(mockService, never()).fail();
    }

    // ========================================
    // onCommit() Tests
    // ========================================

    @Test
    void testOnCommit_EmitsSuccess() {
        // Given
        Map<String, Object> config = configWithService();
        interceptor.configure(config);

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(new TopicPartition("test-topic", 0), new OffsetAndMetadata(100L));
        offsets.put(new TopicPartition("test-topic", 1), new OffsetAndMetadata(200L));

        // When
        interceptor.onCommit(offsets);

        // Then
        verify(mockService, times(1)).success();  // Commit succeeded
        verify(mockService, never()).call();
        verify(mockService, never()).fail();
    }

    @Test
    void testOnCommitWithEmptyOffsets_EmitsSuccess() {
        // Given
        Map<String, Object> config = configWithService();
        interceptor.configure(config);

        Map<TopicPartition, OffsetAndMetadata> emptyOffsets = Collections.emptyMap();

        // When
        interceptor.onCommit(emptyOffsets);

        // Then - still emits success (commit API doesn't fail for empty)
        verify(mockService, times(1)).success();
    }

    @Test
    void testOnCommitWithoutService_NoSignalsEmitted() {
        // Given - configured without service
        Map<String, Object> config = new HashMap<>();
        config.put("group.id", "test-group");
        interceptor.configure(config);

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(new TopicPartition("test-topic", 0), new OffsetAndMetadata(100L));

        // When
        interceptor.onCommit(offsets);

        // Then - no signals emitted (no NPE)
        verify(mockService, never()).success();
    }

    // ========================================
    // Error Handling Tests
    // ========================================

    @Test
    void testOnConsumeWithException_DoesNotPropagateError() {
        // Given - service that throws exception
        Services.Service faultyService = mock(Services.Service.class);
        doThrow(new RuntimeException("Test exception")).when(faultyService).call();

        Map<String, Object> config = new HashMap<>();
        config.put("group.id", "test-group");
        config.put("client.id", "test-consumer");
        config.put(ConsumerEventInterceptor.SERVICE_KEY, faultyService);

        interceptor.configure(config);
        ConsumerRecords<String, String> records = createRecords(5);

        // When
        ConsumerRecords<String, String> result = interceptor.onConsume(records);

        // Then - exception caught, records still returned
        assertThat(result).isSameAs(records);
        verify(faultyService, times(1)).call();  // Exception thrown here, but caught
    }

    @Test
    void testOnCommitWithException_DoesNotPropagateError() {
        // Given - service that throws exception
        Services.Service faultyService = mock(Services.Service.class);
        doThrow(new RuntimeException("Test exception")).when(faultyService).success();

        Map<String, Object> config = new HashMap<>();
        config.put("group.id", "test-group");
        config.put(ConsumerEventInterceptor.SERVICE_KEY, faultyService);

        interceptor.configure(config);

        Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
        offsets.put(new TopicPartition("test-topic", 0), new OffsetAndMetadata(100L));

        // When - should not throw exception
        assertThatCode(() -> interceptor.onCommit(offsets))
            .doesNotThrowAnyException();

        // Then
        verify(faultyService, times(1)).success();  // Exception thrown but caught
    }

    // ========================================
    // close() Tests
    // ========================================

    @Test
    void testClose_NoErrors() {
        // Given
        Map<String, Object> config = configWithService();
        interceptor.configure(config);

        // When
        assertThatCode(() -> interceptor.close())
            .doesNotThrowAnyException();

        // Then - logged close message
    }

    // ========================================
    // Helper Methods
    // ========================================

    private Map<String, Object> configWithService() {
        Map<String, Object> config = new HashMap<>();
        config.put("group.id", "test-group");
        config.put("client.id", "test-consumer");
        config.put(ConsumerEventInterceptor.SERVICE_KEY, mockService);
        return config;
    }

    private ConsumerRecords<String, String> createRecords(int count) {
        TopicPartition partition = new TopicPartition("test-topic", 0);
        List<ConsumerRecord<String, String>> recordList = new java.util.ArrayList<>();

        for (int i = 0; i < count; i++) {
            recordList.add(new ConsumerRecord<>(
                "test-topic", 0, i, "key-" + i, "value-" + i
            ));
        }

        Map<TopicPartition, List<ConsumerRecord<String, String>>> recordsMap = new HashMap<>();
        recordsMap.put(partition, recordList);

        return new ConsumerRecords<>(recordsMap);
    }

    private ConsumerRecords<String, String> createEmptyRecords() {
        return new ConsumerRecords<>(Collections.emptyMap());
    }
}
