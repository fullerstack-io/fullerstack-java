package io.fullerstack.kafka.producer.models;

import io.humainary.modules.serventis.services.api.Services;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ProducerEventMetrics}.
 * <p>
 * Verifies the rich contextual data record pattern that replaces Services.Service.
 */
class ProducerEventMetricsTest {

    @Test
    void call_createsMetricsWithCallSignal() {
        // When: Creating CALL metrics
        ProducerEventMetrics metrics = ProducerEventMetrics.call(
            "producer-1",
            "test-topic",
            2
        );

        // Then: Should have CALL signal with context
        assertThat(metrics.producerId()).isEqualTo("producer-1");
        assertThat(metrics.topic()).isEqualTo("test-topic");
        assertThat(metrics.partition()).isEqualTo(2);
        assertThat(metrics.offset()).isEqualTo(-1L);  // Not yet assigned
        assertThat(metrics.signal()).isEqualTo(Services.Signal.CALL);
        assertThat(metrics.signal().sign()).isEqualTo(Services.Sign.CALL);
        assertThat(metrics.signal().orientation()).isEqualTo(Services.Orientation.RELEASE);
        assertThat(metrics.exception()).isNull();
        assertThat(metrics.latencyMs()).isEqualTo(0L);
        assertThat(metrics.timestamp()).isNotNull();
        assertThat(metrics.metadata()).isEmpty();
    }

    @Test
    void call_withUnknownPartition_usesNegativeOne() {
        // When: Partition not yet determined
        ProducerEventMetrics metrics = ProducerEventMetrics.call(
            "producer-1",
            "test-topic",
            -1
        );

        // Then: Partition should be -1
        assertThat(metrics.partition()).isEqualTo(-1);
    }

    @Test
    void succeeded_createsMetricsWithSucceededSignal() {
        // Given: Record metadata
        RecordMetadata metadata = createMetadata("test-topic", 2, 123L, 100, 200, 1234567890L);

        // When: Creating SUCCEEDED metrics
        ProducerEventMetrics metrics = ProducerEventMetrics.succeeded(
            "producer-1",
            metadata,
            150L
        );

        // Then: Should have SUCCEEDED signal with full context
        assertThat(metrics.producerId()).isEqualTo("producer-1");
        assertThat(metrics.topic()).isEqualTo("test-topic");
        assertThat(metrics.partition()).isEqualTo(2);
        assertThat(metrics.offset()).isEqualTo(123L);
        assertThat(metrics.signal()).isEqualTo(Services.Signal.SUCCEEDED);
        assertThat(metrics.signal().sign()).isEqualTo(Services.Sign.SUCCESS);
        assertThat(metrics.signal().orientation()).isEqualTo(Services.Orientation.RECEIPT);
        assertThat(metrics.exception()).isNull();
        assertThat(metrics.latencyMs()).isEqualTo(150L);
        assertThat(metrics.timestamp()).isNotNull();

        // Metadata should include broker response details
        assertThat(metrics.metadata()).containsEntry("serializedKeySize", 100);
        assertThat(metrics.metadata()).containsEntry("serializedValueSize", 200);
        assertThat(metrics.metadata()).containsEntry("brokerTimestamp", 1234567890L);
    }

    @Test
    void failed_createsMetricsWithFailedSignal() {
        // Given: Exception
        Exception exception = new RuntimeException("Network timeout");

        // When: Creating FAILED metrics
        ProducerEventMetrics metrics = ProducerEventMetrics.failed(
            "producer-1",
            "test-topic",
            2,
            exception,
            75L
        );

        // Then: Should have FAILED signal with exception details
        assertThat(metrics.producerId()).isEqualTo("producer-1");
        assertThat(metrics.topic()).isEqualTo("test-topic");
        assertThat(metrics.partition()).isEqualTo(2);
        assertThat(metrics.offset()).isEqualTo(-1L);  // Not assigned on failure
        assertThat(metrics.signal()).isEqualTo(Services.Signal.FAILED);
        assertThat(metrics.signal().sign()).isEqualTo(Services.Sign.FAIL);
        assertThat(metrics.signal().orientation()).isEqualTo(Services.Orientation.RECEIPT);
        assertThat(metrics.exception()).isEqualTo(exception);
        assertThat(metrics.latencyMs()).isEqualTo(75L);
        assertThat(metrics.timestamp()).isNotNull();

        // Metadata should include exception details
        assertThat(metrics.metadata()).containsEntry("errorType", "RuntimeException");
        assertThat(metrics.metadata()).containsEntry("errorMessage", "Network timeout");
    }

    @Test
    void failed_withNullExceptionMessage_handlesGracefully() {
        // Given: Exception with null message
        Exception exception = new RuntimeException();

        // When: Creating FAILED metrics
        ProducerEventMetrics metrics = ProducerEventMetrics.failed(
            "producer-1",
            "test-topic",
            0,
            exception,
            50L
        );

        // Then: Should handle null message gracefully
        assertThat(metrics.metadata()).containsEntry("errorType", "RuntimeException");
        assertThat(metrics.metadata()).containsEntry("errorMessage", "");
    }

    @Test
    void constructor_withNullProducerId_throwsException() {
        assertThatThrownBy(() ->
            new ProducerEventMetrics(
                null,
                "topic",
                0,
                0L,
                Services.Signal.CALL,
                null,
                0L,
                Instant.now(),
                Map.of()
            )
        ).isInstanceOf(NullPointerException.class)
            .hasMessageContaining("producerId");
    }

    @Test
    void constructor_withNullTopic_throwsException() {
        assertThatThrownBy(() ->
            new ProducerEventMetrics(
                "producer-1",
                null,
                0,
                0L,
                Services.Signal.CALL,
                null,
                0L,
                Instant.now(),
                Map.of()
            )
        ).isInstanceOf(NullPointerException.class)
            .hasMessageContaining("topic");
    }

    @Test
    void constructor_withNullSignal_throwsException() {
        assertThatThrownBy(() ->
            new ProducerEventMetrics(
                "producer-1",
                "topic",
                0,
                0L,
                null,
                null,
                0L,
                Instant.now(),
                Map.of()
            )
        ).isInstanceOf(NullPointerException.class)
            .hasMessageContaining("signal");
    }

    @Test
    void constructor_withNullTimestamp_throwsException() {
        assertThatThrownBy(() ->
            new ProducerEventMetrics(
                "producer-1",
                "topic",
                0,
                0L,
                Services.Signal.CALL,
                null,
                0L,
                null,
                Map.of()
            )
        ).isInstanceOf(NullPointerException.class)
            .hasMessageContaining("timestamp");
    }

    @Test
    void constructor_withNullMetadata_usesEmptyMap() {
        // When: Passing null metadata
        ProducerEventMetrics metrics = new ProducerEventMetrics(
            "producer-1",
            "topic",
            0,
            0L,
            Services.Signal.CALL,
            null,
            0L,
            Instant.now(),
            null
        );

        // Then: Should use empty map
        assertThat(metrics.metadata()).isNotNull();
        assertThat(metrics.metadata()).isEmpty();
    }

    @Test
    void metadata_isImmutable() {
        // Given: Metrics with metadata
        ProducerEventMetrics metrics = ProducerEventMetrics.call("producer-1", "topic", 0);

        // When/Then: Attempting to modify metadata should fail
        assertThatThrownBy(() -> metrics.metadata().put("foo", "bar"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void isCall_returnsTrueForCallSignal() {
        ProducerEventMetrics metrics = ProducerEventMetrics.call("producer-1", "topic", 0);
        assertThat(metrics.isCall()).isTrue();
        assertThat(metrics.isSucceeded()).isFalse();
        assertThat(metrics.isFailed()).isFalse();
    }

    @Test
    void isSucceeded_returnsTrueForSucceededSignal() {
        RecordMetadata metadata = createMetadata("topic", 0, 100L, 10, 20, 1000L);
        ProducerEventMetrics metrics = ProducerEventMetrics.succeeded("producer-1", metadata, 50L);

        assertThat(metrics.isSucceeded()).isTrue();
        assertThat(metrics.isCall()).isFalse();
        assertThat(metrics.isFailed()).isFalse();
    }

    @Test
    void isFailed_returnsTrueForFailedSignal() {
        ProducerEventMetrics metrics = ProducerEventMetrics.failed(
            "producer-1", "topic", 0, new RuntimeException(), 50L
        );

        assertThat(metrics.isFailed()).isTrue();
        assertThat(metrics.isCall()).isFalse();
        assertThat(metrics.isSucceeded()).isFalse();
    }

    @Test
    void isSlowRequest_detectsHighLatency() {
        // Given: High latency metrics
        ProducerEventMetrics metrics = new ProducerEventMetrics(
            "producer-1",
            "topic",
            0,
            0L,
            Services.Signal.CALL,
            null,
            500L,  // 500ms latency
            Instant.now(),
            Map.of()
        );

        // Then: Should detect slow request
        assertThat(metrics.isSlowRequest(100L)).isTrue();   // Above 100ms threshold
        assertThat(metrics.isSlowRequest(500L)).isFalse();  // At threshold
        assertThat(metrics.isSlowRequest(1000L)).isFalse(); // Below threshold
    }

    @Test
    void describe_generatesHumanReadableString() {
        // Given: CALL metrics
        ProducerEventMetrics callMetrics = ProducerEventMetrics.call("producer-1", "test-topic", 2);
        assertThat(callMetrics.describe())
            .contains("producer-1")
            .contains("CALL")
            .contains("test-topic")
            .contains("partition=2");

        // Given: SUCCEEDED metrics
        RecordMetadata metadata = createMetadata("test-topic", 2, 123L, 100, 200, 1000L);
        ProducerEventMetrics succeededMetrics = ProducerEventMetrics.succeeded("producer-1", metadata, 150L);
        assertThat(succeededMetrics.describe())
            .contains("producer-1")
            .contains("SUCCEEDED")
            .contains("test-topic")
            .contains("partition=2")
            .contains("offset=123")
            .contains("latency=150ms");

        // Given: FAILED metrics
        Exception exception = new RuntimeException("Connection lost");
        ProducerEventMetrics failedMetrics = ProducerEventMetrics.failed(
            "producer-1", "test-topic", 2, exception, 75L
        );
        assertThat(failedMetrics.describe())
            .contains("producer-1")
            .contains("FAILED")
            .contains("test-topic")
            .contains("partition=2")
            .contains("latency=75ms")
            .contains("error=RuntimeException")
            .contains("Connection lost");
    }

    @Test
    void describe_handlesUnknownPartition() {
        // Given: Metrics with unknown partition
        ProducerEventMetrics metrics = ProducerEventMetrics.call("producer-1", "test-topic", -1);

        // Then: Description should omit partition
        String description = metrics.describe();
        assertThat(description)
            .contains("producer-1")
            .contains("test-topic")
            .doesNotContain("partition=");
    }

    // Helper methods

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
            0,  // batch index
            timestamp,
            keySize,
            valueSize
        );
    }
}
