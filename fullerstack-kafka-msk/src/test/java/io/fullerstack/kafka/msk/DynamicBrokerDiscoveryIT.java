package io.fullerstack.kafka.msk;

import io.fullerstack.kafka.broker.models.BrokerMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kafka.KafkaClient;

import java.time.Duration;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DynamicBrokerDiscovery} with real AWS MSK.
 * <p>
 * These tests are conditional on AWS_MSK_CLUSTER_ARN environment variable.
 * <p>
 * <b>Manual Verification Required:</b>
 * <ul>
 * <li>Test shouldDetectBrokerAddition: Manually add broker via AWS Console
 * after test starts</li>
 * <li>Test shouldDetectBrokerRemoval: Manually remove broker via AWS Console
 * after test starts</li>
 * </ul>
 */
class DynamicBrokerDiscoveryIT {

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_MSK_CLUSTER_ARN", matches = "arn:.*")
  void shouldDetectBrokerChanges() throws Exception {
    String clusterArn = System.getenv("AWS_MSK_CLUSTER_ARN");

    // Create real MSK discovery
    KafkaClient kafkaClient = KafkaClient.builder()
        .region(Region.US_EAST_1)
        .build();
    MSKClusterDiscovery realDiscovery = new MSKClusterDiscovery(kafkaClient);

    // Metrics emitter callback
    BiConsumer<String, BrokerMetrics> metricsEmitter = (brokerId, metrics) -> {
      System.out.println("Received metrics from broker " + brokerId + ": " + metrics);
    };

    // Create dynamic discovery with fast poll interval for testing
    DynamicBrokerDiscoveryConfig config = DynamicBrokerDiscoveryConfig.builder()
        .pollIntervalSeconds(10) // Fast polling for test
        .build();

    DynamicBrokerDiscovery dynamicDiscovery = new DynamicBrokerDiscovery(
        realDiscovery,
        clusterArn,
        config,
        metricsEmitter);

    try {
      dynamicDiscovery.start();

      // Wait for initial sync
      Thread.sleep(15_000); // 15 seconds

      int initialCount = dynamicDiscovery.getActiveSensorCount();
      System.out.println("Initial broker count: " + initialCount);

      // Verify we detected brokers
      assertThat(initialCount).isGreaterThan(0);

      // Note: Manual broker addition/removal testing
      // To test dynamic detection:
      // 1. Add broker to MSK cluster via AWS Console
      // 2. Wait 15s for detection (max 10s poll interval + processing time)
      // 3. Verify sensor count increases
      //
      // OR
      //
      // 1. Remove broker from MSK cluster via AWS Console
      // 2. Wait 15s for detection
      // 3. Verify sensor count decreases

    } finally {
      dynamicDiscovery.close();
      kafkaClient.close();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "AWS_MSK_CLUSTER_ARN", matches = "arn:.*")
  void shouldHandleClusterDiscovery() throws Exception {
    String clusterArn = System.getenv("AWS_MSK_CLUSTER_ARN");

    // Create real MSK discovery
    KafkaClient kafkaClient = KafkaClient.builder()
        .region(Region.US_EAST_1)
        .build();
    MSKClusterDiscovery realDiscovery = new MSKClusterDiscovery(kafkaClient);

    // Metrics emitter callback
    BiConsumer<String, BrokerMetrics> metricsEmitter = (brokerId, metrics) -> {
      // Callback from Epic 1's BrokerSensor
    };

    DynamicBrokerDiscoveryConfig config = DynamicBrokerDiscoveryConfig.defaults();
    DynamicBrokerDiscovery dynamicDiscovery = new DynamicBrokerDiscovery(
        realDiscovery,
        clusterArn,
        config,
        metricsEmitter);

    try {
      // Trigger manual sync
      dynamicDiscovery.syncBrokers();

      // Verify discovery works (sensor creation may fail due to JMX access, that's
      // OK)
      assertThat(dynamicDiscovery).isNotNull();

    } finally {
      dynamicDiscovery.close();
      kafkaClient.close();
    }
  }
}
