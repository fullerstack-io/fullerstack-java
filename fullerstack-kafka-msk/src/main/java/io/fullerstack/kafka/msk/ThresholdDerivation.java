package io.fullerstack.kafka.msk;

import io.fullerstack.kafka.core.baseline.BaselineService;
import io.fullerstack.kafka.core.baseline.Threshold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Derives health thresholds from MSK cluster configuration and updates {@link BaselineService}.
 * <p>
 * This class is part of Epic 5 Story 5.4 (Configuration-Aware Thresholds).
 * It periodically polls MSK configuration and derives threshold values based on cluster settings.
 * <p>
 * <b>Derivation Logic</b>:
 * <ul>
 *   <li><b>Consumer Lag</b>: Derived from <code>segment.bytes</code>
 *       <ul>
 *         <li>Normal: 50% of segment size</li>
 *         <li>Warning: 75% of segment size</li>
 *         <li>Critical: 90% of segment size</li>
 *       </ul>
 *   </li>
 *   <li><b>Producer Buffer</b>: Derived from <code>buffer.memory</code>
 *       <ul>
 *         <li>Normal: 70% utilization</li>
 *         <li>Warning: 85% utilization</li>
 *         <li>Critical: 95% utilization</li>
 *       </ul>
 *   </li>
 *   <li><b>Partition Count</b>: Derived from <code>num.partitions</code>
 *       <ul>
 *         <li>Normal: Configured partition count</li>
 *         <li>Warning: 1.5× configured count</li>
 *         <li>Critical: 2× configured count</li>
 *       </ul>
 *   </li>
 * </ul>
 * <p>
 * <b>Integration</b>: Epic 1 Assessors read thresholds from {@link BaselineService}
 * to determine health status (STABLE, DEGRADING, CRITICAL, etc.).
 *
 * @author Fullerstack
 */
public class ThresholdDerivation implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(ThresholdDerivation.class);

  private final MSKConfigurationDiscovery configDiscovery;
  private final BaselineService baselineService;
  private final String configArn;
  private final Duration pollInterval;
  private final ScheduledExecutorService scheduler;
  private volatile boolean running = false;

  /**
   * Creates a new threshold derivation manager.
   *
   * @param configDiscovery  MSK configuration discovery service
   * @param baselineService  Baseline service to update with derived thresholds
   * @param configArn        MSK configuration ARN (may be null for defaults)
   * @param pollInterval     How often to poll MSK for configuration updates
   */
  public ThresholdDerivation(
      MSKConfigurationDiscovery configDiscovery,
      BaselineService baselineService,
      String configArn,
      Duration pollInterval) {
    this.configDiscovery = configDiscovery;
    this.baselineService = baselineService;
    this.configArn = configArn;
    this.pollInterval = pollInterval;
    this.scheduler = Executors.newScheduledThreadPool(1, r -> {
      Thread t = new Thread(r, "threshold-derivation");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Start polling MSK configuration and deriving thresholds.
   * <p>
   * Performs initial threshold derivation immediately, then polls at configured interval.
   */
  public void start() {
    if (running) {
      logger.warn("Threshold derivation is already running");
      return;
    }

    logger.info("Starting threshold derivation for configuration: {}", configArn);
    running = true;

    scheduler.scheduleAtFixedRate(
        this::deriveAndUpdateThresholds,
        0, // Initial delay (derive immediately)
        pollInterval.toSeconds(),
        TimeUnit.SECONDS);
  }

  /**
   * Derives thresholds from MSK configuration and updates BaselineService.
   */
  private void deriveAndUpdateThresholds() {
    try {
      ClusterConfiguration config = configDiscovery.discoverConfiguration(configArn);

      logger.info("Deriving thresholds from configuration: {}", config);

      // Derive consumer lag threshold from segment.bytes
      Threshold consumerLagThreshold = deriveConsumerLagThreshold(config.segmentBytes());
      baselineService.updateThreshold("consumer.lag.max", consumerLagThreshold);
      logger.debug("Updated consumer lag threshold: {}", consumerLagThreshold);

      // Derive producer buffer threshold from buffer.memory
      Threshold producerBufferThreshold = deriveProducerBufferThreshold(config.bufferMemory());
      baselineService.updateThreshold("producer.buffer.util", producerBufferThreshold);
      logger.debug("Updated producer buffer threshold: {}", producerBufferThreshold);

      // Derive partition count threshold from num.partitions
      Threshold partitionCountThreshold = derivePartitionCountThreshold(config.numPartitions());
      baselineService.updateThreshold("partition.count", partitionCountThreshold);
      logger.debug("Updated partition count threshold: {}", partitionCountThreshold);

      logger.info("Successfully derived and updated all thresholds");

    } catch (Exception e) {
      logger.error("Failed to derive thresholds, will retry on next poll", e);
    }
  }

  /**
   * Derives consumer lag threshold from segment.bytes.
   * <p>
   * Logic: Consumer lag is healthy when below 50% of segment size,
   * warning at 75%, critical at 90%.
   *
   * @param segmentBytes Kafka segment.bytes configuration value
   * @return Threshold for consumer lag in bytes
   */
  private Threshold deriveConsumerLagThreshold(long segmentBytes) {
    long normalMax = (long) (segmentBytes * 0.50);
    long warningMax = (long) (segmentBytes * 0.75);
    long criticalMax = (long) (segmentBytes * 0.90);

    return new Threshold(
        normalMax,
        warningMax,
        criticalMax,
        "MSK config: segment.bytes=" + segmentBytes);
  }

  /**
   * Derives producer buffer threshold from buffer.memory.
   * <p>
   * Logic: Producer buffer is healthy when below 70% utilization,
   * warning at 85%, critical at 95%.
   *
   * @param bufferMemory Kafka buffer.memory configuration value
   * @return Threshold for producer buffer utilization (percentage as integer 0-100)
   */
  private Threshold deriveProducerBufferThreshold(long bufferMemory) {
    // Thresholds are expressed as percentage values (0-100)
    long normalMax = 70;
    long warningMax = 85;
    long criticalMax = 95;

    return new Threshold(
        normalMax,
        warningMax,
        criticalMax,
        "MSK config: buffer.memory=" + bufferMemory);
  }

  /**
   * Derives partition count threshold from num.partitions.
   * <p>
   * Logic: Partition count is healthy at configured value,
   * warning at 1.5×, critical at 2×.
   *
   * @param numPartitions Kafka num.partitions configuration value
   * @return Threshold for partition count
   */
  private Threshold derivePartitionCountThreshold(int numPartitions) {
    long normalMax = numPartitions;
    long warningMax = (long) (numPartitions * 1.5);
    long criticalMax = numPartitions * 2L;

    return new Threshold(
        normalMax,
        warningMax,
        criticalMax,
        "MSK config: num.partitions=" + numPartitions);
  }

  /**
   * Stop polling and cleanup.
   */
  @Override
  public void close() {
    logger.info("Shutting down threshold derivation");
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

    logger.info("Threshold derivation stopped");
  }

  /**
   * Gets the current running state.
   *
   * @return true if threshold derivation is running
   */
  public boolean isRunning() {
    return running;
  }
}
