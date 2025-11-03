package io.fullerstack.kafka.msk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DynamicClientDiscovery.
 */
@ExtendWith(MockitoExtension.class)
class DynamicClientDiscoveryTest {

  @Mock
  private ProducerDiscovery producerDiscovery;

  @Mock
  private ConsumerDiscovery consumerDiscovery;

  @Mock
  private Consumer<String> onProducerAdded;

  @Mock
  private Consumer<String> onProducerRemoved;

  @Mock
  private Consumer<String> onConsumerAdded;

  @Mock
  private Consumer<String> onConsumerRemoved;

  private DynamicClientDiscovery discovery;

  private static final String CLUSTER_NAME = "test-cluster";

  @BeforeEach
  void setUp() {
    discovery = new DynamicClientDiscovery(
        producerDiscovery,
        consumerDiscovery,
        CLUSTER_NAME,
        Duration.ofSeconds(1), // Fast polling for tests
        onProducerAdded,
        onProducerRemoved,
        onConsumerAdded,
        onConsumerRemoved);
  }

  @AfterEach
  void tearDown() {
    if (discovery != null) {
      discovery.close();
    }
  }

  @Test
  void shouldStartMonitoringForNewProducers() throws Exception {
    // Given: Discovery returns 2 producers
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of("producer-1", "producer-2"));
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of());

    // When: Starting discovery and waiting for sync
    discovery.start();
    Thread.sleep(100); // Allow initial sync

    // Then: Callbacks invoked for both producers
    verify(onProducerAdded, timeout(500)).accept("producer-1");
    verify(onProducerAdded, timeout(500)).accept("producer-2");

    assertThat(discovery.getActiveProducerCount()).isEqualTo(2);
  }

  @Test
  void shouldStartMonitoringForNewConsumers() throws Exception {
    // Given: Discovery returns 3 consumer groups
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of());
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of("group-1", "group-2", "group-3"));

    // When: Starting discovery and waiting for sync
    discovery.start();
    Thread.sleep(100);

    // Then: Callbacks invoked for all consumers
    verify(onConsumerAdded, timeout(500)).accept("group-1");
    verify(onConsumerAdded, timeout(500)).accept("group-2");
    verify(onConsumerAdded, timeout(500)).accept("group-3");

    assertThat(discovery.getActiveConsumerCount()).isEqualTo(3);
  }

  @Test
  void shouldStopMonitoringForRemovedProducers() throws Exception {
    // Given: Initially 2 producers
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of("producer-1", "producer-2"));
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of());

    discovery.start();
    Thread.sleep(100);

    verify(onProducerAdded, timeout(500).times(2)).accept(anyString());
    assertThat(discovery.getActiveProducerCount()).isEqualTo(2);

    // When: One producer removed
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of("producer-1"));

    Thread.sleep(1500); // Wait for next poll (1s interval)

    // Then: Callback invoked for removed producer
    verify(onProducerRemoved, timeout(500)).accept("producer-2");
    assertThat(discovery.getActiveProducerCount()).isEqualTo(1);
  }

  @Test
  void shouldStopMonitoringForRemovedConsumers() throws Exception {
    // Given: Initially 3 consumer groups
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of());
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of("group-1", "group-2", "group-3"));

    discovery.start();
    Thread.sleep(100);

    verify(onConsumerAdded, timeout(500).times(3)).accept(anyString());
    assertThat(discovery.getActiveConsumerCount()).isEqualTo(3);

    // When: Two consumer groups removed
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of("group-1"));

    Thread.sleep(1500); // Wait for next poll

    // Then: Callbacks invoked for removed consumers
    verify(onConsumerRemoved, timeout(500)).accept("group-2");
    verify(onConsumerRemoved, timeout(500)).accept("group-3");
    assertThat(discovery.getActiveConsumerCount()).isEqualTo(1);
  }

  @Test
  void shouldHandleProducerChurn() throws Exception {
    // Given: Initial state
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of("producer-1"));
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of());

    discovery.start();
    Thread.sleep(100);

    verify(onProducerAdded, timeout(500)).accept("producer-1");

    // When: Producer removed and new one added
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of("producer-2"));

    Thread.sleep(1500);

    // Then: Old removed, new added
    verify(onProducerRemoved, timeout(500)).accept("producer-1");
    verify(onProducerAdded, timeout(500)).accept("producer-2");
    assertThat(discovery.getActiveProducerCount()).isEqualTo(1);
  }

  @Test
  void shouldHandleDiscoveryFailureGracefully() throws Exception {
    // Given: Discovery throws exception
    doThrow(new MSKDiscoveryException("CloudWatch unavailable"))
        .when(producerDiscovery).discoverProducers(CLUSTER_NAME);
    doReturn(List.of()).when(consumerDiscovery).discoverConsumerGroups();

    // When: Starting discovery
    discovery.start();
    Thread.sleep(100);

    // Then: No callbacks invoked, discovery continues
    verify(onProducerAdded, never()).accept(anyString());
    verify(onProducerRemoved, never()).accept(anyString());

    // When: Discovery recovers
    doReturn(List.of("producer-1")).when(producerDiscovery).discoverProducers(CLUSTER_NAME);

    Thread.sleep(1500);

    // Then: Normal operation resumes
    verify(onProducerAdded, timeout(500)).accept("producer-1");
  }

  @Test
  void shouldNotAddDuplicateProducers() throws Exception {
    // Given: Same producers discovered multiple times
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of("producer-1", "producer-2"));
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of());

    // When: Starting discovery and waiting for multiple polls
    discovery.start();
    Thread.sleep(2500); // Wait for 2+ polls

    // Then: Callbacks invoked only once per producer
    verify(onProducerAdded, times(1)).accept("producer-1");
    verify(onProducerAdded, times(1)).accept("producer-2");
  }

  @Test
  void shouldStopAllMonitoringOnClose() throws Exception {
    // Given: Active producers and consumers
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of("producer-1", "producer-2"));
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of("group-1", "group-2", "group-3"));

    discovery.start();
    Thread.sleep(100);

    verify(onProducerAdded, timeout(500).times(2)).accept(anyString());
    verify(onConsumerAdded, timeout(500).times(3)).accept(anyString());

    // When: Closing discovery
    discovery.close();

    // Then: All monitoring stopped
    verify(onProducerRemoved).accept("producer-1");
    verify(onProducerRemoved).accept("producer-2");
    verify(onConsumerRemoved).accept("group-1");
    verify(onConsumerRemoved).accept("group-2");
    verify(onConsumerRemoved).accept("group-3");

    assertThat(discovery.getActiveProducerCount()).isZero();
    assertThat(discovery.getActiveConsumerCount()).isZero();
  }

  @Test
  void shouldHandleCallbackFailuresGracefully() throws Exception {
    // Given: Callback throws exception
    doThrow(new RuntimeException("Monitor creation failed"))
        .when(onProducerAdded).accept("producer-1");

    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of("producer-1", "producer-2"));
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of());

    // When: Starting discovery
    discovery.start();

    // Then: Discovery continues despite callback failure
    // producer-1 callback fails, but producer-2 still processed
    verify(onProducerAdded, timeout(500)).accept("producer-1");
    verify(onProducerAdded, timeout(500)).accept("producer-2");

    // Only producer-2 tracked (producer-1 callback failed, so not added to tracked set)
    Thread.sleep(200);  // Give time for tracking to complete
    assertThat(discovery.getActiveProducerCount()).isEqualTo(1);
  }

  @Test
  void shouldRespectPollInterval() throws Exception {
    // Given: Discovery with 1-second poll interval
    AtomicInteger callCount = new AtomicInteger(0);
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenAnswer(inv -> {
          callCount.incrementAndGet();
          return List.of();
        });
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of());

    // When: Starting discovery and waiting
    discovery.start();
    Thread.sleep(2500); // Wait for ~2 polls

    // Then: Discovery called approximately 2-3 times (allowing for timing variance)
    int calls = callCount.get();
    assertThat(calls).isBetween(2, 4);
  }

  @Test
  void shouldInitiallyHaveZeroCounts() {
    // When: Discovery created but not started
    // Then: Counts are zero
    assertThat(discovery.getActiveProducerCount()).isZero();
    assertThat(discovery.getActiveConsumerCount()).isZero();
  }

  @Test
  void shouldHandleEmptyDiscoveryResults() throws Exception {
    // Given: No producers or consumers discovered
    when(producerDiscovery.discoverProducers(CLUSTER_NAME))
        .thenReturn(List.of());
    when(consumerDiscovery.discoverConsumerGroups())
        .thenReturn(List.of());

    // When: Starting discovery
    discovery.start();
    Thread.sleep(100);

    // Then: No callbacks invoked
    verify(onProducerAdded, never()).accept(anyString());
    verify(onConsumerAdded, never()).accept(anyString());

    assertThat(discovery.getActiveProducerCount()).isZero();
    assertThat(discovery.getActiveConsumerCount()).isZero();
  }
}
