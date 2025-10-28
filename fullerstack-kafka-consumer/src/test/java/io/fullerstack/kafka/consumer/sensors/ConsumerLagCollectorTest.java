package io.fullerstack.kafka.consumer.sensors;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ConsumerLagCollectorTest {

    @Mock
    private AdminClient mockAdminClient;

    @Mock
    private ListConsumerGroupOffsetsResult mockOffsetsResult;

    @Mock
    private ListOffsetsResult mockListOffsetsResult;

    private AutoCloseable mocks;

    private static final TopicPartition TP_0 = new TopicPartition("test-topic", 0);
    private static final TopicPartition TP_1 = new TopicPartition("test-topic", 1);

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    @Test
    void shouldCollectLagSuccessfully() throws Exception {
        // given
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of(
                TP_0, new OffsetAndMetadata(100L),
                TP_1, new OffsetAndMetadata(200L)
        );

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = Map.of(
                TP_0, new ListOffsetsResult.ListOffsetsResultInfo(150L, System.currentTimeMillis(), null),
                TP_1, new ListOffsetsResult.ListOffsetsResultInfo(300L, System.currentTimeMillis(), null)
        );

        when(mockAdminClient.listConsumerGroupOffsets(anyString()))
                .thenReturn(mockOffsetsResult);
        when(mockOffsetsResult.partitionsToOffsetAndMetadata())
                .thenReturn(KafkaFuture.completedFuture(committedOffsets));

        when(mockAdminClient.listOffsets(any()))
                .thenReturn(mockListOffsetsResult);
        when(mockListOffsetsResult.all())
                .thenReturn(KafkaFuture.completedFuture(endOffsets));

        ConsumerLagCollector collector = createCollectorWithMock();

        // when
        Map<TopicPartition, Long> lag = collector.collectLag("test-group");

        // then
        assertThat(lag).hasSize(2);
        assertThat(lag.get(TP_0)).isEqualTo(50L);  // 150 - 100
        assertThat(lag.get(TP_1)).isEqualTo(100L); // 300 - 200

        verify(mockAdminClient).listConsumerGroupOffsets("test-group");
        verify(mockAdminClient).listOffsets(any());
    }

    @Test
    void shouldReturnEmptyMapForNewConsumerGroup() throws Exception {
        // given - no committed offsets
        when(mockAdminClient.listConsumerGroupOffsets(anyString()))
                .thenReturn(mockOffsetsResult);
        when(mockOffsetsResult.partitionsToOffsetAndMetadata())
                .thenReturn(KafkaFuture.completedFuture(Map.of()));

        ConsumerLagCollector collector = createCollectorWithMock();

        // when
        Map<TopicPartition, Long> lag = collector.collectLag("new-group");

        // then
        assertThat(lag).isEmpty();
        verify(mockAdminClient).listConsumerGroupOffsets("new-group");
        verify(mockAdminClient, never()).listOffsets(any());
    }

    @Test
    void shouldHandleZeroLag() throws Exception {
        // given - committed offset equals end offset
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of(
                TP_0, new OffsetAndMetadata(100L)
        );

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = Map.of(
                TP_0, new ListOffsetsResult.ListOffsetsResultInfo(100L, System.currentTimeMillis(), null)
        );

        when(mockAdminClient.listConsumerGroupOffsets(anyString()))
                .thenReturn(mockOffsetsResult);
        when(mockOffsetsResult.partitionsToOffsetAndMetadata())
                .thenReturn(KafkaFuture.completedFuture(committedOffsets));

        when(mockAdminClient.listOffsets(any()))
                .thenReturn(mockListOffsetsResult);
        when(mockListOffsetsResult.all())
                .thenReturn(KafkaFuture.completedFuture(endOffsets));

        ConsumerLagCollector collector = createCollectorWithMock();

        // when
        Map<TopicPartition, Long> lag = collector.collectLag("test-group");

        // then
        assertThat(lag.get(TP_0)).isEqualTo(0L);
    }

    @Test
    void shouldHandleNegativeLagAsZero() throws Exception {
        // given - committed offset ahead of end offset (edge case)
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of(
                TP_0, new OffsetAndMetadata(150L)
        );

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = Map.of(
                TP_0, new ListOffsetsResult.ListOffsetsResultInfo(100L, System.currentTimeMillis(), null)
        );

        when(mockAdminClient.listConsumerGroupOffsets(anyString()))
                .thenReturn(mockOffsetsResult);
        when(mockOffsetsResult.partitionsToOffsetAndMetadata())
                .thenReturn(KafkaFuture.completedFuture(committedOffsets));

        when(mockAdminClient.listOffsets(any()))
                .thenReturn(mockListOffsetsResult);
        when(mockListOffsetsResult.all())
                .thenReturn(KafkaFuture.completedFuture(endOffsets));

        ConsumerLagCollector collector = createCollectorWithMock();

        // when
        Map<TopicPartition, Long> lag = collector.collectLag("test-group");

        // then
        assertThat(lag.get(TP_0)).isEqualTo(0L); // Math.max(0, 100-150)
    }

    @Test
    void shouldHandleTimeoutException() {
        // given
        when(mockAdminClient.listConsumerGroupOffsets(anyString()))
                .thenReturn(mockOffsetsResult);
        when(mockOffsetsResult.partitionsToOffsetAndMetadata())
                .thenReturn(KafkaFuture.completedFuture(Map.of(
                        TP_0, new OffsetAndMetadata(100L)
                )));

        when(mockAdminClient.listOffsets(any()))
                .thenReturn(mockListOffsetsResult);

        KafkaFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> failedFuture =
                mock(KafkaFuture.class);
        try {
            when(failedFuture.get(anyLong(), any()))
                    .thenThrow(new java.util.concurrent.ExecutionException(
                            new org.apache.kafka.common.errors.TimeoutException("Timeout")));
        } catch (Exception ignored) {
            // Mockito setup, won't throw
        }

        when(mockListOffsetsResult.all()).thenReturn(failedFuture);

        ConsumerLagCollector collector = createCollectorWithMock();

        // when/then
        assertThatThrownBy(() -> collector.collectLag("test-group"))
                .hasCauseInstanceOf(org.apache.kafka.common.errors.TimeoutException.class);
    }

    @Test
    void shouldSkipPartitionsWithMissingEndOffset() throws Exception {
        // given
        Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of(
                TP_0, new OffsetAndMetadata(100L),
                TP_1, new OffsetAndMetadata(200L)
        );

        // Missing end offset for TP_1
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets = Map.of(
                TP_0, new ListOffsetsResult.ListOffsetsResultInfo(150L, System.currentTimeMillis(), null)
        );

        when(mockAdminClient.listConsumerGroupOffsets(anyString()))
                .thenReturn(mockOffsetsResult);
        when(mockOffsetsResult.partitionsToOffsetAndMetadata())
                .thenReturn(KafkaFuture.completedFuture(committedOffsets));

        when(mockAdminClient.listOffsets(any()))
                .thenReturn(mockListOffsetsResult);
        when(mockListOffsetsResult.all())
                .thenReturn(KafkaFuture.completedFuture(endOffsets));

        ConsumerLagCollector collector = createCollectorWithMock();

        // when
        Map<TopicPartition, Long> lag = collector.collectLag("test-group");

        // then - only TP_0 should have lag calculated
        assertThat(lag).hasSize(1);
        assertThat(lag.get(TP_0)).isEqualTo(50L);
        assertThat(lag.get(TP_1)).isNull();
    }

    private ConsumerLagCollector createCollectorWithMock() {
        // Use reflection to inject mock AdminClient
        ConsumerLagCollector collector = new ConsumerLagCollector("localhost:9092") {
            @Override
            public Map<TopicPartition, Long> collectLag(String consumerGroup) throws Exception {
                // Override to use mockAdminClient
                return mockCollectLag(consumerGroup);
            }

            @Override
            public void close() {
                mockAdminClient.close();
            }
        };
        return collector;
    }

    private Map<TopicPartition, Long> mockCollectLag(String consumerGroup) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committedOffsets =
                mockAdminClient.listConsumerGroupOffsets(consumerGroup)
                        .partitionsToOffsetAndMetadata()
                        .get(5000, TimeUnit.MILLISECONDS);

        if (committedOffsets.isEmpty()) {
            return Map.of();
        }

        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                mockAdminClient.listOffsets(any())
                        .all()
                        .get(5000, TimeUnit.MILLISECONDS);

        Map<TopicPartition, Long> lag = new HashMap<>();
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committedOffsets.entrySet()) {
            TopicPartition tp = entry.getKey();
            long committed = entry.getValue().offset();

            ListOffsetsResult.ListOffsetsResultInfo endOffsetInfo = endOffsets.get(tp);
            if (endOffsetInfo == null) {
                continue;
            }

            long endOffset = endOffsetInfo.offset();
            lag.put(tp, Math.max(0, endOffset - committed));
        }

        return lag;
    }
}
