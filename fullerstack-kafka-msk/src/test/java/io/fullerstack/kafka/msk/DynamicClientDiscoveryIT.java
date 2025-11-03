package io.fullerstack.kafka.msk;

import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for DynamicClientDiscovery with real AWS MSK cluster.
 * <p>
 * Requires environment variables:
 * <ul>
 * <li>AWS_MSK_CLUSTER_ARN - MSK cluster ARN</li>
 * <li>AWS_MSK_BOOTSTRAP_SERVERS - Kafka bootstrap servers</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "AWS_MSK_CLUSTER_ARN", matches = "arn:.*")
class DynamicClientDiscoveryIT {

  private CloudWatchClient cloudWatch;
  private AdminClient adminClient;
  private DynamicClientDiscovery discovery;

  private String clusterArn;
  private String clusterName;
  private String bootstrapServers;

  private final AtomicInteger producersAdded = new AtomicInteger(0);
  private final AtomicInteger producersRemoved = new AtomicInteger(0);
  private final AtomicInteger consumersAdded = new AtomicInteger(0);
  private final AtomicInteger consumersRemoved = new AtomicInteger(0);

  @BeforeEach
  void setUp() {
    clusterArn = System.getenv("AWS_MSK_CLUSTER_ARN");
    bootstrapServers = System.getenv("AWS_MSK_BOOTSTRAP_SERVERS");

    // Extract cluster name from ARN
    // ARN format: arn:aws:kafka:region:account:cluster/cluster-name/uuid
    String[] arnParts = clusterArn.split("/");
    clusterName = arnParts[arnParts.length - 2];

    // Create AWS clients
    cloudWatch = CloudWatchClient.builder()
        .region(Region.US_EAST_1)
        .build();

    Properties adminProps = new Properties();
    adminProps.put("bootstrap.servers", bootstrapServers);
    adminClient = AdminClient.create(adminProps);
  }

  @AfterEach
  void tearDown() {
    if (discovery != null) {
      discovery.close();
    }
    if (adminClient != null) {
      adminClient.close();
    }
    if (cloudWatch != null) {
      cloudWatch.close();
    }
  }

  @Test
  void shouldDiscoverRealProducersAndConsumers() throws Exception {
    // Given: Real MSK cluster with discovery
    ProducerDiscovery producerDiscovery = new ProducerDiscovery(cloudWatch);
    ConsumerDiscovery consumerDiscovery = new ConsumerDiscovery(adminClient);

    discovery = new DynamicClientDiscovery(
        producerDiscovery,
        consumerDiscovery,
        clusterName,
        Duration.ofSeconds(10), // Fast polling for test
        producerId -> {
          System.out.println("Producer added: " + producerId);
          producersAdded.incrementAndGet();
        },
        producerId -> {
          System.out.println("Producer removed: " + producerId);
          producersRemoved.incrementAndGet();
        },
        groupId -> {
          System.out.println("Consumer added: " + groupId);
          consumersAdded.incrementAndGet();
        },
        groupId -> {
          System.out.println("Consumer removed: " + groupId);
          consumersRemoved.incrementAndGet();
        });

    // When: Starting discovery
    discovery.start();

    // Wait for initial discovery (one poll cycle)
    Thread.sleep(15_000); // 15 seconds to ensure at least one poll

    // Then: Discovery should find existing clients (if any)
    // Note: Exact counts depend on cluster state, so we just verify no errors
    int initialProducers = discovery.getActiveProducerCount();
    int initialConsumers = discovery.getActiveConsumerCount();

    System.out.println("Initial discovery: " + initialProducers + " producers, " +
        initialConsumers + " consumers");

    assertThat(producersAdded.get()).isEqualTo(initialProducers);
    assertThat(consumersAdded.get()).isEqualTo(initialConsumers);

    // Verify counts are non-negative
    assertThat(initialProducers).isGreaterThanOrEqualTo(0);
    assertThat(initialConsumers).isGreaterThanOrEqualTo(0);
  }

  @Test
  void shouldHandleProducerDiscoveryFromCloudWatch() throws Exception {
    // Given: ProducerDiscovery with real CloudWatch
    ProducerDiscovery producerDiscovery = new ProducerDiscovery(cloudWatch);

    // When: Discovering producers
    var producers = producerDiscovery.discoverProducers(clusterName);

    // Then: Discovery completes without errors
    assertThat(producers).isNotNull();

    System.out.println("Discovered " + producers.size() + " producer(s): " + producers);

    // Verify activity check works for discovered producers
    if (!producers.isEmpty()) {
      String producerId = producers.get(0);
      boolean active = producerDiscovery.isProducerActive(producerId, clusterName);

      System.out.println("Producer " + producerId + " active: " + active);
    }
  }

  @Test
  void shouldHandleConsumerDiscoveryFromAdminClient() throws Exception {
    // Given: ConsumerDiscovery with real AdminClient
    ConsumerDiscovery consumerDiscovery = new ConsumerDiscovery(adminClient);

    // When: Discovering consumer groups
    var groups = consumerDiscovery.discoverConsumerGroups();

    // Then: Discovery completes without errors
    assertThat(groups).isNotNull();

    System.out.println("Discovered " + groups.size() + " consumer group(s): " + groups);

    // Verify activity check works for discovered consumers
    if (!groups.isEmpty()) {
      String groupId = groups.get(0);
      boolean active = consumerDiscovery.isConsumerActive(groupId);

      System.out.println("Consumer group " + groupId + " active: " + active);
    }
  }

  @Test
  void shouldHandleMultiplePollCycles() throws Exception {
    // Given: Discovery with fast polling
    ProducerDiscovery producerDiscovery = new ProducerDiscovery(cloudWatch);
    ConsumerDiscovery consumerDiscovery = new ConsumerDiscovery(adminClient);

    discovery = new DynamicClientDiscovery(
        producerDiscovery,
        consumerDiscovery,
        clusterName,
        Duration.ofSeconds(5), // 5-second poll interval
        producerId -> producersAdded.incrementAndGet(),
        producerId -> producersRemoved.incrementAndGet(),
        groupId -> consumersAdded.incrementAndGet(),
        groupId -> consumersRemoved.incrementAndGet());

    // When: Running for multiple poll cycles
    discovery.start();
    Thread.sleep(16_000); // ~3 poll cycles

    // Then: Discovery remains stable
    int finalProducers = discovery.getActiveProducerCount();
    int finalConsumers = discovery.getActiveConsumerCount();

    System.out.println("After multiple polls: " + finalProducers + " producers, " +
        finalConsumers + " consumers");

    // Verify no excessive churn (add/remove counts should be close to final counts)
    assertThat(producersAdded.get()).isGreaterThanOrEqualTo(finalProducers);
    assertThat(consumersAdded.get()).isGreaterThanOrEqualTo(finalConsumers);

    // Removed counts should be minimal (only if clients actually stopped)
    System.out.println("Churn - Producers added: " + producersAdded.get() +
        ", removed: " + producersRemoved.get());
    System.out.println("Churn - Consumers added: " + consumersAdded.get() +
        ", removed: " + consumersRemoved.get());
  }

  @Test
  void shouldCleanlyShutdown() throws Exception {
    // Given: Active discovery
    ProducerDiscovery producerDiscovery = new ProducerDiscovery(cloudWatch);
    ConsumerDiscovery consumerDiscovery = new ConsumerDiscovery(adminClient);

    discovery = new DynamicClientDiscovery(
        producerDiscovery,
        consumerDiscovery,
        clusterName,
        Duration.ofSeconds(10),
        producerId -> producersAdded.incrementAndGet(),
        producerId -> producersRemoved.incrementAndGet(),
        groupId -> consumersAdded.incrementAndGet(),
        groupId -> consumersRemoved.incrementAndGet());

    discovery.start();
    Thread.sleep(15_000);

    int activeProducers = discovery.getActiveProducerCount();
    int activeConsumers = discovery.getActiveConsumerCount();

    // When: Shutting down
    discovery.close();

    // Then: All monitors stopped
    assertThat(discovery.getActiveProducerCount()).isZero();
    assertThat(discovery.getActiveConsumerCount()).isZero();

    assertThat(producersRemoved.get()).isEqualTo(activeProducers);
    assertThat(consumersRemoved.get()).isEqualTo(activeConsumers);
  }
}
