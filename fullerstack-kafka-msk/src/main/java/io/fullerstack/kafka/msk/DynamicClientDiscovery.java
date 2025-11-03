package io.fullerstack.kafka.msk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Dynamically discovers producer/consumer changes and manages Epic 1 monitoring
 * lifecycle.
 * <p>
 * This is INFRASTRUCTURE management - Epic 1's monitors emit RC1 signals.
 * <p>
 * Polls CloudWatch (for producers) and AdminClient (for consumers) at
 * configurable
 * intervals, automatically starting/stopping Epic 1 monitoring agents as
 * clients
 * come and go.
 *
 * <h3>Usage:</h3>
 * 
 * <pre>{@code
 * ProducerDiscovery producerDiscovery = new ProducerDiscovery(cloudWatch);
 * ConsumerDiscovery consumerDiscovery = new ConsumerDiscovery(adminClient);
 *
 * DynamicClientDiscovery discovery = new DynamicClientDiscovery(
 *     producerDiscovery,
 *     consumerDiscovery,
 *     "my-cluster",
 *     Duration.ofSeconds(60),
 *     producerId -> {
 *       // Create and start Epic 1's ProducerBufferMonitor
 *       ProducerBufferMonitor monitor = createProducerMonitor(producerId);
 *       monitor.start();
 *     },
 *     producerId -> {
 *       // Stop Epic 1's ProducerBufferMonitor
 *       stopProducerMonitor(producerId);
 *     },
 *     groupId -> {
 *       // Create and start Epic 1's ConsumerLagMonitor
 *       ConsumerLagMonitor monitor = createConsumerMonitor(groupId);
 *       monitor.start();
 *     },
 *     groupId -> {
 *       // Stop Epic 1's ConsumerLagMonitor
 *       stopConsumerMonitor(groupId);
 *     });
 *
 * discovery.start();
 *
 * // Later...
 * discovery.close();
 * }</pre>
 *
 * @author Fullerstack
 */
public class DynamicClientDiscovery implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(DynamicClientDiscovery.class);

  private final ProducerDiscovery producerDiscovery;
  private final ConsumerDiscovery consumerDiscovery;
  private final String clusterName;
  private final Duration pollInterval;

  // Lifecycle callbacks for Epic 1 monitoring
  private final Consumer<String> onProducerAdded;
  private final Consumer<String> onProducerRemoved;
  private final Consumer<String> onConsumerAdded;
  private final Consumer<String> onConsumerRemoved;

  // Tracking active clients
  private final Set<String> trackedProducers = ConcurrentHashMap.newKeySet();
  private final Set<String> trackedConsumers = ConcurrentHashMap.newKeySet();

  private final ScheduledExecutorService scheduler;
  private volatile boolean running = false;

  /**
   * Creates a new dynamic client discovery manager.
   *
   * @param producerDiscovery CloudWatch-based producer discovery
   * @param consumerDiscovery AdminClient-based consumer discovery
   * @param clusterName       MSK cluster name
   * @param pollInterval      How often to poll for changes
   * @param onProducerAdded   Callback when new producer detected (start Epic 1
   *                          monitor)
   * @param onProducerRemoved Callback when producer removed (stop Epic 1 monitor)
   * @param onConsumerAdded   Callback when new consumer detected (start Epic 1
   *                          monitor)
   * @param onConsumerRemoved Callback when consumer removed (stop Epic 1 monitor)
   */
  public DynamicClientDiscovery(
      ProducerDiscovery producerDiscovery,
      ConsumerDiscovery consumerDiscovery,
      String clusterName,
      Duration pollInterval,
      Consumer<String> onProducerAdded,
      Consumer<String> onProducerRemoved,
      Consumer<String> onConsumerAdded,
      Consumer<String> onConsumerRemoved) {
    this.producerDiscovery = producerDiscovery;
    this.consumerDiscovery = consumerDiscovery;
    this.clusterName = clusterName;
    this.pollInterval = pollInterval;
    this.onProducerAdded = onProducerAdded;
    this.onProducerRemoved = onProducerRemoved;
    this.onConsumerAdded = onConsumerAdded;
    this.onConsumerRemoved = onConsumerRemoved;
    this.scheduler = Executors.newScheduledThreadPool(1, r -> {
      Thread t = new Thread(r, "dynamic-client-discovery");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Start polling for producer/consumer changes.
   */
  public void start() {
    if (running) {
      logger.warn("Dynamic client discovery is already running");
      return;
    }

    logger.info("Starting dynamic client discovery for cluster: {}", clusterName);
    running = true;

    scheduler.scheduleAtFixedRate(
        this::syncClients,
        0, // Initial delay
        pollInterval.toSeconds(),
        TimeUnit.SECONDS);
  }

  /**
   * Poll CloudWatch + AdminClient and sync Epic 1 monitoring agents.
   */
  private void syncClients() {
    try {
      syncProducers();
      syncConsumers();
    } catch (Exception e) {
      logger.error("Failed to sync clients, will retry on next poll", e);
    }
  }

  /**
   * Sync Epic 1 producer monitoring.
   */
  private void syncProducers() {
    try {
      Set<String> currentProducers = Set.copyOf(
          producerDiscovery.discoverProducers(clusterName));

      // Start Epic 1 monitoring for new producers
      currentProducers.stream()
          .filter(id -> !trackedProducers.contains(id))
          .forEach(this::startProducerMonitoring);

      // Stop Epic 1 monitoring for removed producers
      trackedProducers.stream()
          .filter(id -> !currentProducers.contains(id))
          .forEach(this::stopProducerMonitoring);

      logger.debug("Producer sync complete: {} active monitors", trackedProducers.size());

    } catch (Exception e) {
      logger.error("Producer sync failed", e);
    }
  }

  /**
   * Sync Epic 1 consumer monitoring.
   */
  private void syncConsumers() {
    try {
      Set<String> currentConsumers = Set.copyOf(
          consumerDiscovery.discoverConsumerGroups());

      // Start Epic 1 monitoring for new consumers
      currentConsumers.stream()
          .filter(id -> !trackedConsumers.contains(id))
          .forEach(this::startConsumerMonitoring);

      // Stop Epic 1 monitoring for removed consumers
      trackedConsumers.stream()
          .filter(id -> !currentConsumers.contains(id))
          .forEach(this::stopConsumerMonitoring);

      logger.debug("Consumer sync complete: {} active monitors", trackedConsumers.size());

    } catch (Exception e) {
      logger.error("Consumer sync failed", e);
    }
  }

  /**
   * Start Epic 1's ProducerBufferMonitor for a new producer.
   * <p>
   * Epic 1's monitor emits Queues.Signal via queue.overflow() (RC1 API).
   */
  private void startProducerMonitoring(String producerId) {
    try {
      logger.info("Starting Epic 1 monitoring for producer: {}", producerId);

      // Delegate to callback (Epic 5 doesn't create monitors, just manages lifecycle)
      onProducerAdded.accept(producerId);

      // Only add to tracked set if callback succeeded
      trackedProducers.add(producerId);

    } catch (Exception e) {
      logger.error("Failed to start monitoring for producer: {}", producerId, e);
      // Don't add to tracked set if callback failed
    }
  }

  /**
   * Start Epic 1's ConsumerLagMonitor for a new consumer group.
   * <p>
   * Epic 1's monitor emits Queues.Signal via queue.underflow() (RC1 API).
   */
  private void startConsumerMonitoring(String groupId) {
    try {
      logger.info("Starting Epic 1 monitoring for consumer group: {}", groupId);

      // Delegate to callback (Epic 5 doesn't create monitors, just manages lifecycle)
      onConsumerAdded.accept(groupId);

      trackedConsumers.add(groupId);

    } catch (Exception e) {
      logger.error("Failed to start monitoring for consumer: {}", groupId, e);
    }
  }

  /**
   * Stop Epic 1's ProducerBufferMonitor for a removed producer.
   */
  private void stopProducerMonitoring(String producerId) {
    try {
      logger.info("Stopping Epic 1 monitoring for producer: {}", producerId);

      // Delegate to callback
      onProducerRemoved.accept(producerId);

      trackedProducers.remove(producerId);

    } catch (Exception e) {
      logger.error("Error stopping monitoring for producer: {}", producerId, e);
    }
  }

  /**
   * Stop Epic 1's ConsumerLagMonitor for a removed consumer.
   */
  private void stopConsumerMonitoring(String groupId) {
    try {
      logger.info("Stopping Epic 1 monitoring for consumer: {}", groupId);

      // Delegate to callback
      onConsumerRemoved.accept(groupId);

      trackedConsumers.remove(groupId);

    } catch (Exception e) {
      logger.error("Error stopping monitoring for consumer: {}", groupId, e);
    }
  }

  /**
   * Get count of actively monitored producers.
   */
  public int getActiveProducerCount() {
    return trackedProducers.size();
  }

  /**
   * Get count of actively monitored consumers.
   */
  public int getActiveConsumerCount() {
    return trackedConsumers.size();
  }

  /**
   * Stop polling and cleanup.
   */
  @Override
  public void close() {
    logger.info("Shutting down dynamic client discovery");
    running = false;

    scheduler.shutdown();

    try {
      if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      scheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }

    // Stop all Epic 1 monitoring agents via callbacks
    trackedProducers.forEach(onProducerRemoved);
    trackedConsumers.forEach(onConsumerRemoved);

    trackedProducers.clear();
    trackedConsumers.clear();
  }
}
