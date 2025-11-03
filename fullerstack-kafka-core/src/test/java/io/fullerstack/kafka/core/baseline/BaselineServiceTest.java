package io.fullerstack.kafka.core.baseline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link BaselineService}.
 */
class BaselineServiceTest {

  private BaselineService service;

  @BeforeEach
  void setUp() {
    service = new BaselineService();
  }

  @Test
  void shouldStoreAndRetrieveThreshold() {
    // Given: A threshold for consumer lag
    Threshold threshold = new Threshold(
        50000L,
        75000L,
        90000L,
        "test-config");

    // When: Updating the threshold
    service.updateThreshold("consumer.lag.max", threshold);

    // Then: Threshold can be retrieved
    Threshold retrieved = service.getThreshold("consumer.lag.max");
    assertThat(retrieved).isNotNull();
    assertThat(retrieved.getNormalMax()).isEqualTo(50000L);
    assertThat(retrieved.getWarningMax()).isEqualTo(75000L);
    assertThat(retrieved.getCriticalMax()).isEqualTo(90000L);
    assertThat(retrieved.getSource()).isEqualTo("test-config");
  }

  @Test
  void shouldReturnNullForUnknownMetric() {
    // When: Getting threshold for non-existent metric
    Threshold threshold = service.getThreshold("unknown.metric");

    // Then: Returns null
    assertThat(threshold).isNull();
  }

  @Test
  void shouldUpdateExistingThreshold() {
    // Given: Initial threshold
    Threshold initial = new Threshold(100L, 200L, 300L, "initial");
    service.updateThreshold("metric", initial);

    // When: Updating with new threshold
    Threshold updated = new Threshold(150L, 250L, 350L, "updated");
    service.updateThreshold("metric", updated);

    // Then: Retrieves updated values
    Threshold retrieved = service.getThreshold("metric");
    assertThat(retrieved.getNormalMax()).isEqualTo(150L);
    assertThat(retrieved.getSource()).isEqualTo("updated");
  }

  @Test
  void shouldRemoveThreshold() {
    // Given: Stored threshold
    Threshold threshold = new Threshold(10L, 20L, 30L, "test");
    service.updateThreshold("metric", threshold);

    // When: Removing the threshold
    Threshold removed = service.removeThreshold("metric");

    // Then: Threshold is removed and returned
    assertThat(removed).isNotNull();
    assertThat(removed.getNormalMax()).isEqualTo(10L);
    assertThat(service.getThreshold("metric")).isNull();
  }

  @Test
  void shouldReturnNullWhenRemovingNonExistentThreshold() {
    // When: Removing non-existent threshold
    Threshold removed = service.removeThreshold("non-existent");

    // Then: Returns null
    assertThat(removed).isNull();
  }

  @Test
  void shouldClearAllThresholds() {
    // Given: Multiple thresholds
    service.updateThreshold("metric1", new Threshold(1L, 2L, 3L, "m1"));
    service.updateThreshold("metric2", new Threshold(4L, 5L, 6L, "m2"));
    service.updateThreshold("metric3", new Threshold(7L, 8L, 9L, "m3"));

    assertThat(service.size()).isEqualTo(3);

    // When: Clearing all thresholds
    service.clear();

    // Then: All thresholds removed
    assertThat(service.size()).isZero();
    assertThat(service.getThreshold("metric1")).isNull();
    assertThat(service.getThreshold("metric2")).isNull();
    assertThat(service.getThreshold("metric3")).isNull();
  }

  @Test
  void shouldReturnCorrectSize() {
    // Given: Empty service
    assertThat(service.size()).isZero();

    // When: Adding thresholds
    service.updateThreshold("metric1", new Threshold(1L, 2L, 3L, "m1"));
    assertThat(service.size()).isEqualTo(1);

    service.updateThreshold("metric2", new Threshold(4L, 5L, 6L, "m2"));
    assertThat(service.size()).isEqualTo(2);

    // When: Updating existing threshold (should not increase size)
    service.updateThreshold("metric1", new Threshold(10L, 20L, 30L, "m1-updated"));
    assertThat(service.size()).isEqualTo(2);

    // When: Removing threshold
    service.removeThreshold("metric1");
    assertThat(service.size()).isEqualTo(1);
  }

  @Test
  void shouldHandleConcurrentAccess() throws Exception {
    // Given: Concurrent threads updating thresholds
    int threadCount = 10;
    int iterationsPerThread = 100;

    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      threads[i] = new Thread(() -> {
        for (int j = 0; j < iterationsPerThread; j++) {
          String metric = "metric-" + threadId;
          Threshold threshold = new Threshold(j, j + 1, j + 2, "thread-" + threadId);
          service.updateThreshold(metric, threshold);
        }
      });
    }

    // When: Starting all threads
    for (Thread thread : threads) {
      thread.start();
    }

    // Wait for completion
    for (Thread thread : threads) {
      thread.join();
    }

    // Then: All metrics stored (one per thread)
    assertThat(service.size()).isEqualTo(threadCount);
    for (int i = 0; i < threadCount; i++) {
      assertThat(service.getThreshold("metric-" + i)).isNotNull();
    }
  }

  @Test
  void shouldStoreMultipleMetrics() {
    // Given: Different metric types
    service.updateThreshold("consumer.lag.max",
        new Threshold(500_000L, 750_000L, 900_000L, "MSK segment.bytes"));
    service.updateThreshold("producer.buffer.util",
        new Threshold(70L, 85L, 95L, "MSK buffer.memory"));
    service.updateThreshold("partition.count",
        new Threshold(10L, 15L, 20L, "MSK num.partitions"));

    // Then: All metrics stored independently
    assertThat(service.size()).isEqualTo(3);

    Threshold consumerLag = service.getThreshold("consumer.lag.max");
    assertThat(consumerLag.getNormalMax()).isEqualTo(500_000L);

    Threshold producerBuffer = service.getThreshold("producer.buffer.util");
    assertThat(producerBuffer.getNormalMax()).isEqualTo(70L);

    Threshold partitionCount = service.getThreshold("partition.count");
    assertThat(partitionCount.getNormalMax()).isEqualTo(10L);
  }
}
