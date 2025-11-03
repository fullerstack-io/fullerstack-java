package io.fullerstack.kafka.msk;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Discovers active Kafka consumer groups via AdminClient API.
 * <p>
 * This is INFRASTRUCTURE discovery, NOT observability.
 * Epic 1 monitors discovered consumers using RC1 Queues API
 * (queue.underflow()).
 * <p>
 * Discovery mechanism: Kafka AdminClient listConsumerGroups API.
 *
 * @author Fullerstack
 */
public class ConsumerDiscovery {

  private static final Logger logger = LoggerFactory.getLogger(ConsumerDiscovery.class);

  private final AdminClient adminClient;

  /**
   * Creates a new ConsumerDiscovery instance.
   *
   * @param adminClient Kafka AdminClient for consumer group discovery
   */
  public ConsumerDiscovery(AdminClient adminClient) {
    this.adminClient = adminClient;
  }

  /**
   * Discover active consumer group IDs.
   * <p>
   * Queries Kafka cluster for all registered consumer groups.
   *
   * @return List of consumer group IDs
   * @throws MSKDiscoveryException if AdminClient call fails
   */
  public List<String> discoverConsumerGroups() {
    try {
      logger.debug("Discovering consumer groups");

      List<String> groupIds = adminClient.listConsumerGroups()
          .all()
          .get()
          .stream()
          .map(ConsumerGroupListing::groupId)
          .toList();

      logger.debug("Discovered {} consumer group(s)", groupIds.size());

      return groupIds;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MSKDiscoveryException("Consumer group discovery interrupted", e);
    } catch (ExecutionException e) {
      throw new MSKDiscoveryException("Failed to discover consumer groups", e);
    }
  }

  /**
   * Check if consumer group is still active (has members).
   * <p>
   * A consumer group is considered active if it has at least one active member.
   *
   * @param groupId Consumer group ID
   * @return true if group has active members
   * @throws MSKDiscoveryException if AdminClient call fails
   */
  public boolean isConsumerActive(String groupId) {
    try {
      ConsumerGroupDescription description = adminClient.describeConsumerGroups(List.of(groupId))
          .describedGroups()
          .get(groupId)
          .get();

      boolean hasMembers = !description.members().isEmpty();

      logger.debug("Consumer group {} active check: {}", groupId, hasMembers);

      return hasMembers;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MSKDiscoveryException("Consumer activity check interrupted for: " + groupId, e);
    } catch (ExecutionException e) {
      throw new MSKDiscoveryException("Failed to check consumer group: " + groupId, e);
    }
  }
}
