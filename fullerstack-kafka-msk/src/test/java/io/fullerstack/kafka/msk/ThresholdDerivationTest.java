package io.fullerstack.kafka.msk;

import io.fullerstack.kafka.core.baseline.BaselineService;
import io.fullerstack.kafka.core.baseline.Threshold;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ThresholdDerivation}.
 */
@ExtendWith(MockitoExtension.class)
class ThresholdDerivationTest {

  @Mock
  private MSKConfigurationDiscovery configDiscovery;

  @Mock
  private BaselineService baselineService;

  private ThresholdDerivation derivation;

  private static final String CONFIG_ARN = "arn:aws:kafka:us-east-1:123456789012:configuration/test/abc";

  @BeforeEach
  void setUp() {
    derivation = new ThresholdDerivation(
        configDiscovery,
        baselineService,
        CONFIG_ARN,
        Duration.ofSeconds(1)); // Fast polling for tests
  }

  @AfterEach
  void tearDown() {
    if (derivation != null) {
      derivation.close();
    }
  }

  @Test
  void shouldDeriveThresholdsOnStart() throws Exception {
    // Given: MSK configuration with specific values
    ClusterConfiguration config = new ClusterConfiguration(
        1073741824L, // 1 GB segment.bytes
        33554432L,   // 32 MB buffer.memory
        10);         // 10 partitions

    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(config);

    // When: Starting threshold derivation
    derivation.start();
    Thread.sleep(200); // Allow initial derivation

    // Then: All three thresholds derived and updated
    verify(baselineService, timeout(500)).updateThreshold(eq("consumer.lag.max"), any(Threshold.class));
    verify(baselineService, timeout(500)).updateThreshold(eq("producer.buffer.util"), any(Threshold.class));
    verify(baselineService, timeout(500)).updateThreshold(eq("partition.count"), any(Threshold.class));
  }

  @Test
  void shouldDeriveConsumerLagThresholdCorrectly() throws Exception {
    // Given: MSK configuration with 1 GB segment.bytes
    ClusterConfiguration config = new ClusterConfiguration(
        1073741824L, // 1 GB
        33554432L,
        10);

    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(config);

    // When: Starting derivation
    derivation.start();
    Thread.sleep(200);

    // Then: Consumer lag threshold derived as percentages of segment size
    ArgumentCaptor<Threshold> captor = ArgumentCaptor.forClass(Threshold.class);
    verify(baselineService, timeout(500)).updateThreshold(eq("consumer.lag.max"), captor.capture());

    Threshold threshold = captor.getValue();
    assertThat(threshold.getNormalMax()).isEqualTo(536870912L);   // 50% of 1 GB
    assertThat(threshold.getWarningMax()).isEqualTo(805306368L);  // 75% of 1 GB
    assertThat(threshold.getCriticalMax()).isEqualTo(966367641L); // 90% of 1 GB
    assertThat(threshold.getSource()).contains("segment.bytes=1073741824");
  }

  @Test
  void shouldDeriveProducerBufferThresholdCorrectly() throws Exception {
    // Given: MSK configuration with 32 MB buffer.memory
    ClusterConfiguration config = new ClusterConfiguration(
        1073741824L,
        33554432L, // 32 MB
        10);

    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(config);

    // When: Starting derivation
    derivation.start();
    Thread.sleep(200);

    // Then: Producer buffer threshold derived as percentage values
    ArgumentCaptor<Threshold> captor = ArgumentCaptor.forClass(Threshold.class);
    verify(baselineService, timeout(500)).updateThreshold(eq("producer.buffer.util"), captor.capture());

    Threshold threshold = captor.getValue();
    assertThat(threshold.getNormalMax()).isEqualTo(70L);   // 70%
    assertThat(threshold.getWarningMax()).isEqualTo(85L);  // 85%
    assertThat(threshold.getCriticalMax()).isEqualTo(95L); // 95%
    assertThat(threshold.getSource()).contains("buffer.memory=33554432");
  }

  @Test
  void shouldDerivePartitionCountThresholdCorrectly() throws Exception {
    // Given: MSK configuration with 10 partitions
    ClusterConfiguration config = new ClusterConfiguration(
        1073741824L,
        33554432L,
        10); // 10 partitions

    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(config);

    // When: Starting derivation
    derivation.start();
    Thread.sleep(200);

    // Then: Partition count threshold derived as multiples
    ArgumentCaptor<Threshold> captor = ArgumentCaptor.forClass(Threshold.class);
    verify(baselineService, timeout(500)).updateThreshold(eq("partition.count"), captor.capture());

    Threshold threshold = captor.getValue();
    assertThat(threshold.getNormalMax()).isEqualTo(10L);  // 1× num.partitions
    assertThat(threshold.getWarningMax()).isEqualTo(15L); // 1.5× num.partitions
    assertThat(threshold.getCriticalMax()).isEqualTo(20L); // 2× num.partitions
    assertThat(threshold.getSource()).contains("num.partitions=10");
  }

  @Test
  void shouldPollConfigurationPeriodically() throws Exception {
    // Given: Configuration changes over time
    ClusterConfiguration config1 = new ClusterConfiguration(1073741824L, 33554432L, 10);
    ClusterConfiguration config2 = new ClusterConfiguration(2147483648L, 67108864L, 20);

    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(config1)
        .thenReturn(config2);

    // When: Starting derivation with 1-second poll interval
    derivation.start();
    Thread.sleep(1600); // Wait for at least 2 polls

    // Then: Configuration discovered multiple times
    verify(configDiscovery, atLeast(2)).discoverConfiguration(CONFIG_ARN);

    // Thresholds updated multiple times
    verify(baselineService, atLeast(2)).updateThreshold(eq("consumer.lag.max"), any(Threshold.class));
  }

  @Test
  void shouldHandleConfigurationDiscoveryFailureGracefully() throws Exception {
    // Given: Configuration discovery throws exception
    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenThrow(new MSKDiscoveryException("AWS API unavailable"))
        .thenReturn(new ClusterConfiguration(1073741824L, 33554432L, 10));

    // When: Starting derivation
    derivation.start();
    Thread.sleep(1600); // Wait for initial failure + retry

    // Then: Derivation continues despite initial failure
    verify(configDiscovery, atLeast(2)).discoverConfiguration(CONFIG_ARN);

    // Thresholds updated after recovery
    verify(baselineService, timeout(500)).updateThreshold(eq("consumer.lag.max"), any(Threshold.class));
  }

  @Test
  void shouldNotStartTwice() throws Exception {
    // Given: Derivation already started
    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(new ClusterConfiguration(1073741824L, 33554432L, 10));

    derivation.start();
    Thread.sleep(100);

    // When: Starting again
    derivation.start();
    Thread.sleep(100);

    // Then: Only started once (warning logged, but no duplicate scheduler)
    assertThat(derivation.isRunning()).isTrue();
  }

  @Test
  void shouldStopDerivationOnClose() throws Exception {
    // Given: Running derivation
    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(new ClusterConfiguration(1073741824L, 33554432L, 10));

    derivation.start();
    Thread.sleep(100);
    assertThat(derivation.isRunning()).isTrue();

    // When: Closing derivation
    derivation.close();

    // Then: Derivation stopped
    assertThat(derivation.isRunning()).isFalse();

    // No more configuration polls
    Thread.sleep(1500);
    verify(configDiscovery, atMost(2)).discoverConfiguration(CONFIG_ARN); // Only initial polls
  }

  @Test
  void shouldNotBeRunningInitially() {
    // Given: Newly created derivation
    // Then: Not running
    assertThat(derivation.isRunning()).isFalse();
  }

  @Test
  void shouldDeriveWithDefaultConfiguration() throws Exception {
    // Given: Default configuration values
    ClusterConfiguration defaultConfig = ClusterConfiguration.defaults();

    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(defaultConfig);

    // When: Starting derivation
    derivation.start();
    Thread.sleep(200);

    // Then: Thresholds derived from defaults
    ArgumentCaptor<Threshold> lagCaptor = ArgumentCaptor.forClass(Threshold.class);
    verify(baselineService, timeout(500)).updateThreshold(eq("consumer.lag.max"), lagCaptor.capture());

    Threshold lagThreshold = lagCaptor.getValue();
    assertThat(lagThreshold.getNormalMax()).isEqualTo(536870912L); // 50% of 1 GB default
  }

  @Test
  void shouldHandleSmallSegmentSize() throws Exception {
    // Given: Small segment size (e.g., 100 MB for testing)
    ClusterConfiguration config = new ClusterConfiguration(
        104857600L, // 100 MB
        33554432L,
        5);

    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(config);

    // When: Starting derivation
    derivation.start();
    Thread.sleep(200);

    // Then: Thresholds derived correctly from small values
    ArgumentCaptor<Threshold> captor = ArgumentCaptor.forClass(Threshold.class);
    verify(baselineService, timeout(500)).updateThreshold(eq("consumer.lag.max"), captor.capture());

    Threshold threshold = captor.getValue();
    assertThat(threshold.getNormalMax()).isEqualTo(52428800L);   // 50% of 100 MB
    assertThat(threshold.getWarningMax()).isEqualTo(78643200L);  // 75% of 100 MB
    assertThat(threshold.getCriticalMax()).isEqualTo(94371840L); // 90% of 100 MB
  }

  @Test
  void shouldHandleSinglePartition() throws Exception {
    // Given: Single partition configuration
    ClusterConfiguration config = new ClusterConfiguration(
        1073741824L,
        33554432L,
        1); // 1 partition

    when(configDiscovery.discoverConfiguration(CONFIG_ARN))
        .thenReturn(config);

    // When: Starting derivation
    derivation.start();
    Thread.sleep(200);

    // Then: Partition threshold derived for single partition
    ArgumentCaptor<Threshold> captor = ArgumentCaptor.forClass(Threshold.class);
    verify(baselineService, timeout(500)).updateThreshold(eq("partition.count"), captor.capture());

    Threshold threshold = captor.getValue();
    assertThat(threshold.getNormalMax()).isEqualTo(1L);  // 1× num.partitions
    assertThat(threshold.getWarningMax()).isEqualTo(1L); // 1.5× rounds down to 1
    assertThat(threshold.getCriticalMax()).isEqualTo(2L); // 2× num.partitions
  }
}
