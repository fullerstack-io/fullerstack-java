package io.fullerstack.kafka.msk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.kafka.KafkaClient;
import software.amazon.awssdk.services.kafka.model.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MSKConfigurationDiscovery}.
 */
@ExtendWith(MockitoExtension.class)
class MSKConfigurationDiscoveryTest {

  @Mock
  private KafkaClient mskClient;

  private MSKConfigurationDiscovery discovery;

  @BeforeEach
  void setUp() {
    discovery = new MSKConfigurationDiscovery(mskClient);
  }

  @Test
  void shouldReturnDefaultsWhenConfigArnIsNull() {
    // When: No configuration ARN provided
    ClusterConfiguration config = discovery.discoverConfiguration(null);

    // Then: Returns default configuration
    assertThat(config).isEqualTo(ClusterConfiguration.defaults());
    verifyNoInteractions(mskClient);
  }

  @Test
  void shouldReturnDefaultsWhenConfigArnIsEmpty() {
    // When: Empty configuration ARN provided
    ClusterConfiguration config = discovery.discoverConfiguration("");

    // Then: Returns default configuration
    assertThat(config).isEqualTo(ClusterConfiguration.defaults());
    verifyNoInteractions(mskClient);
  }

  @Test
  void shouldDiscoverConfigurationFromMSK() {
    // Given: MSK returns configuration with custom values
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/test-config/abc-123";
    String serverProperties = """
        # Test configuration
        segment.bytes=2147483648
        buffer.memory=67108864
        num.partitions=5
        """;

    ConfigurationRevision revision = ConfigurationRevision.builder()
        .description(serverProperties)
        .revision(1L)
        .build();

    DescribeConfigurationResponse response = DescribeConfigurationResponse.builder()
        .latestRevision(revision)
        .build();

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenReturn(response);

    // When: Discovering configuration
    ClusterConfiguration config = discovery.discoverConfiguration(configArn);

    // Then: Custom values retrieved
    assertThat(config.segmentBytes()).isEqualTo(2147483648L); // 2 GB
    assertThat(config.bufferMemory()).isEqualTo(67108864L);   // 64 MB
    assertThat(config.numPartitions()).isEqualTo(5);

    verify(mskClient).describeConfiguration(any(DescribeConfigurationRequest.class));
  }

  @Test
  void shouldUseDefaultsWhenPropertiesNotFound() {
    // Given: MSK returns configuration without target properties
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/test-config/abc-123";
    String serverProperties = """
        # Configuration without our target properties
        log.retention.hours=168
        compression.type=gzip
        """;

    ConfigurationRevision revision = ConfigurationRevision.builder()
        .description(serverProperties)
        .revision(1L)
        .build();

    DescribeConfigurationResponse response = DescribeConfigurationResponse.builder()
        .latestRevision(revision)
        .build();

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenReturn(response);

    // When: Discovering configuration
    ClusterConfiguration config = discovery.discoverConfiguration(configArn);

    // Then: Default values used
    assertThat(config).isEqualTo(ClusterConfiguration.defaults());
  }

  @Test
  void shouldHandlePartialConfiguration() {
    // Given: MSK returns configuration with only some properties
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/test-config/abc-123";
    String serverProperties = """
        segment.bytes=536870912
        # buffer.memory not specified
        # num.partitions not specified
        """;

    ConfigurationRevision revision = ConfigurationRevision.builder()
        .description(serverProperties)
        .revision(1L)
        .build();

    DescribeConfigurationResponse response = DescribeConfigurationResponse.builder()
        .latestRevision(revision)
        .build();

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenReturn(response);

    // When: Discovering configuration
    ClusterConfiguration config = discovery.discoverConfiguration(configArn);

    // Then: Custom segment.bytes, defaults for others
    assertThat(config.segmentBytes()).isEqualTo(536870912L); // 512 MB (custom)
    assertThat(config.bufferMemory()).isEqualTo(33554432L);  // Default
    assertThat(config.numPartitions()).isEqualTo(1);         // Default
  }

  @Test
  void shouldHandleInvalidPropertyValues() {
    // Given: MSK returns configuration with invalid values
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/test-config/abc-123";
    String serverProperties = """
        segment.bytes=not-a-number
        buffer.memory=invalid
        num.partitions=abc
        """;

    ConfigurationRevision revision = ConfigurationRevision.builder()
        .description(serverProperties)
        .revision(1L)
        .build();

    DescribeConfigurationResponse response = DescribeConfigurationResponse.builder()
        .latestRevision(revision)
        .build();

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenReturn(response);

    // When: Discovering configuration
    ClusterConfiguration config = discovery.discoverConfiguration(configArn);

    // Then: Default values used (parsing falls back to defaults)
    assertThat(config).isEqualTo(ClusterConfiguration.defaults());
  }

  @Test
  void shouldHandleEmptyServerProperties() {
    // Given: MSK returns configuration with empty server.properties
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/test-config/abc-123";

    ConfigurationRevision revision = ConfigurationRevision.builder()
        .description("")
        .revision(1L)
        .build();

    DescribeConfigurationResponse response = DescribeConfigurationResponse.builder()
        .latestRevision(revision)
        .build();

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenReturn(response);

    // When: Discovering configuration
    ClusterConfiguration config = discovery.discoverConfiguration(configArn);

    // Then: Default values used
    assertThat(config).isEqualTo(ClusterConfiguration.defaults());
  }

  @Test
  void shouldHandleNullRevision() {
    // Given: MSK returns configuration without latest revision
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/test-config/abc-123";

    DescribeConfigurationResponse response = DescribeConfigurationResponse.builder()
        .latestRevision((ConfigurationRevision) null)
        .build();

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenReturn(response);

    // When: Discovering configuration
    ClusterConfiguration config = discovery.discoverConfiguration(configArn);

    // Then: Default values used
    assertThat(config).isEqualTo(ClusterConfiguration.defaults());
  }

  @Test
  void shouldThrowExceptionWhenConfigurationNotFound() {
    // Given: MSK throws NotFoundException
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/missing/abc-123";

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenThrow(NotFoundException.builder().message("Configuration not found").build());

    // When: Discovering configuration
    ClusterConfiguration config = discovery.discoverConfiguration(configArn);

    // Then: Returns defaults (graceful degradation)
    assertThat(config).isEqualTo(ClusterConfiguration.defaults());
  }

  @Test
  void shouldThrowExceptionWhenAPIThrottled() {
    // Given: MSK throws TooManyRequestsException
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/test-config/abc-123";

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenThrow(TooManyRequestsException.builder().message("Throttled").build());

    // When/Then: Throws MSKDiscoveryException
    assertThatThrownBy(() -> discovery.discoverConfiguration(configArn))
        .isInstanceOf(MSKDiscoveryException.class)
        .hasMessageContaining("AWS API throttled");
  }

  @Test
  void shouldParseServerPropertiesWithComments() {
    // Given: Server properties with comments
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/test-config/abc-123";
    String serverProperties = """
        # Segment configuration
        segment.bytes=1073741824

        # Buffer configuration
        buffer.memory=33554432

        # Partition configuration
        num.partitions=3
        """;

    ConfigurationRevision revision = ConfigurationRevision.builder()
        .description(serverProperties)
        .revision(1L)
        .build();

    DescribeConfigurationResponse response = DescribeConfigurationResponse.builder()
        .latestRevision(revision)
        .build();

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenReturn(response);

    // When: Discovering configuration
    ClusterConfiguration config = discovery.discoverConfiguration(configArn);

    // Then: Comments ignored, values parsed correctly
    assertThat(config.segmentBytes()).isEqualTo(1073741824L);
    assertThat(config.bufferMemory()).isEqualTo(33554432L);
    assertThat(config.numPartitions()).isEqualTo(3);
  }

  @Test
  void shouldHandleMalformedPropertyLines() {
    // Given: Server properties with malformed lines
    String configArn = "arn:aws:kafka:us-east-1:123456789012:configuration/test-config/abc-123";
    String serverProperties = """
        segment.bytes=1073741824
        malformed-line-without-equals
        buffer.memory=33554432
        another-bad-line
        num.partitions=2
        """;

    ConfigurationRevision revision = ConfigurationRevision.builder()
        .description(serverProperties)
        .revision(1L)
        .build();

    DescribeConfigurationResponse response = DescribeConfigurationResponse.builder()
        .latestRevision(revision)
        .build();

    when(mskClient.describeConfiguration(any(DescribeConfigurationRequest.class)))
        .thenReturn(response);

    // When: Discovering configuration
    ClusterConfiguration config = discovery.discoverConfiguration(configArn);

    // Then: Valid properties parsed, malformed lines ignored
    assertThat(config.segmentBytes()).isEqualTo(1073741824L);
    assertThat(config.bufferMemory()).isEqualTo(33554432L);
    assertThat(config.numPartitions()).isEqualTo(2);
  }
}
