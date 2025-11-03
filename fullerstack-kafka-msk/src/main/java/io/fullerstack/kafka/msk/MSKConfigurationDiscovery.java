package io.fullerstack.kafka.msk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.kafka.KafkaClient;
import software.amazon.awssdk.services.kafka.model.*;

import java.util.Map;

/**
 * Discovers MSK cluster configuration values from AWS Kafka Configuration API.
 * <p>
 * This is INFRASTRUCTURE discovery for Epic 5 Story 5.4 (Configuration-Aware Thresholds).
 * Retrieved configuration values are used by {@link ThresholdDerivation} to calculate
 * health thresholds for Epic 1 Assessors.
 * <p>
 * <b>Key Configuration Properties</b>:
 * <ul>
 *   <li><code>segment.bytes</code> → Consumer lag threshold</li>
 *   <li><code>buffer.memory</code> → Producer buffer threshold</li>
 *   <li><code>num.partitions</code> → Partition count threshold</li>
 * </ul>
 *
 * @author Fullerstack
 */
public class MSKConfigurationDiscovery {

  private static final Logger logger = LoggerFactory.getLogger(MSKConfigurationDiscovery.class);

  // Default values per Kafka documentation
  private static final long DEFAULT_SEGMENT_BYTES = 1073741824L; // 1 GB
  private static final long DEFAULT_BUFFER_MEMORY = 33554432L;   // 32 MB
  private static final int DEFAULT_NUM_PARTITIONS = 1;

  private final KafkaClient mskClient;

  public MSKConfigurationDiscovery(KafkaClient mskClient) {
    this.mskClient = mskClient;
  }

  /**
   * Discovers cluster configuration from MSK.
   * <p>
   * Uses AWS Kafka API to retrieve custom configuration or defaults.
   *
   * @param configArn The configuration ARN (may be null for default config)
   * @return ClusterConfiguration with discovered or default values
   * @throws MSKDiscoveryException if configuration retrieval fails
   */
  public ClusterConfiguration discoverConfiguration(String configArn) {
    if (configArn == null || configArn.isEmpty()) {
      logger.info("No custom configuration ARN provided, using Kafka defaults");
      return ClusterConfiguration.defaults();
    }

    try {
      DescribeConfigurationResponse response = mskClient.describeConfiguration(
          DescribeConfigurationRequest.builder()
              .arn(configArn)
              .build());

      ConfigurationRevision latestRevision = response.latestRevision();
      if (latestRevision == null) {
        logger.warn("Configuration has no revisions, using defaults: {}", configArn);
        return ClusterConfiguration.defaults();
      }

      // Parse server.properties format (key=value pairs)
      Map<String, String> properties = parseServerProperties(
          latestRevision.description());

      long segmentBytes = parseLong(properties, "segment.bytes", DEFAULT_SEGMENT_BYTES);
      long bufferMemory = parseLong(properties, "buffer.memory", DEFAULT_BUFFER_MEMORY);
      int numPartitions = parseInt(properties, "num.partitions", DEFAULT_NUM_PARTITIONS);

      logger.info("Discovered configuration: segment.bytes={}, buffer.memory={}, num.partitions={}",
          segmentBytes, bufferMemory, numPartitions);

      return new ClusterConfiguration(segmentBytes, bufferMemory, numPartitions);

    } catch (NotFoundException e) {
      logger.warn("Configuration not found, using defaults: {}", configArn, e);
      return ClusterConfiguration.defaults();
    } catch (TooManyRequestsException e) {
      logger.warn("AWS API throttled for configuration: {}", configArn, e);
      throw new MSKDiscoveryException("AWS API throttled, retry with backoff", e);
    } catch (Exception e) {
      logger.error("Failed to discover configuration: {}", configArn, e);
      throw new MSKDiscoveryException("Failed to discover configuration: " + configArn, e);
    }
  }

  /**
   * Parses server.properties format from AWS MSK Configuration.
   * <p>
   * Format: key=value pairs separated by newlines, with # for comments.
   *
   * @param serverProperties The server.properties content
   * @return Map of configuration properties
   */
  private Map<String, String> parseServerProperties(String serverProperties) {
    if (serverProperties == null || serverProperties.isEmpty()) {
      logger.debug("Empty server.properties, returning empty map");
      return Map.of();
    }

    Map<String, String> properties = new java.util.HashMap<>();
    String[] lines = serverProperties.split("\n");

    for (String line : lines) {
      line = line.trim();

      // Skip comments and empty lines
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }

      int equalsIndex = line.indexOf('=');
      if (equalsIndex == -1) {
        logger.debug("Skipping malformed property line: {}", line);
        continue;
      }

      String key = line.substring(0, equalsIndex).trim();
      String value = line.substring(equalsIndex + 1).trim();
      properties.put(key, value);
    }

    logger.debug("Parsed {} configuration properties", properties.size());
    return properties;
  }

  /**
   * Safely parses long value from properties, with fallback to default.
   */
  private long parseLong(Map<String, String> properties, String key, long defaultValue) {
    String value = properties.get(key);
    if (value == null) {
      logger.debug("Property {} not found, using default: {}", key, defaultValue);
      return defaultValue;
    }

    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      logger.warn("Invalid long value for {}: {}, using default: {}", key, value, defaultValue);
      return defaultValue;
    }
  }

  /**
   * Safely parses int value from properties, with fallback to default.
   */
  private int parseInt(Map<String, String> properties, String key, int defaultValue) {
    String value = properties.get(key);
    if (value == null) {
      logger.debug("Property {} not found, using default: {}", key, defaultValue);
      return defaultValue;
    }

    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      logger.warn("Invalid int value for {}: {}, using default: {}", key, value, defaultValue);
      return defaultValue;
    }
  }
}
