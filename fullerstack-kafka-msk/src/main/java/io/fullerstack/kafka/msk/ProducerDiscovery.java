package io.fullerstack.kafka.msk;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Discovers active Kafka producers via AWS CloudWatch metrics.
 * <p>
 * This is INFRASTRUCTURE discovery, NOT observability.
 * Epic 1 monitors discovered producers using RC1 Queues API (queue.overflow()).
 * <p>
 * Discovery mechanism: CloudWatch ListMetrics API for "BytesOutPerSec" metric
 * with "Client ID" dimension filtering.
 *
 * @author Fullerstack
 */
public class ProducerDiscovery {

  private static final Logger logger = LoggerFactory.getLogger(ProducerDiscovery.class);

  private final CloudWatchClient cloudWatch;

  /**
   * Creates a new ProducerDiscovery instance.
   *
   * @param cloudWatch AWS CloudWatch client for metrics discovery
   */
  public ProducerDiscovery(CloudWatchClient cloudWatch) {
    this.cloudWatch = cloudWatch;
  }

  /**
   * Discover active producer client IDs from CloudWatch metrics.
   * <p>
   * Queries CloudWatch for producers that have emitted "BytesOutPerSec" metrics,
   * indicating active data transmission to the cluster.
   *
   * @param clusterName MSK cluster name (from Story 5.1)
   * @return List of producer client IDs currently sending data
   * @throws MSKDiscoveryException if CloudWatch API fails or is throttled
   */
  public List<String> discoverProducers(String clusterName) {
    try {
      logger.debug("Discovering producers for cluster: {}", clusterName);

      ListMetricsResponse response = cloudWatch.listMetrics(
          ListMetricsRequest.builder()
              .namespace("AWS/Kafka")
              .metricName("BytesOutPerSec") // Producer traffic metric
              .dimensions(DimensionFilter.builder()
                  .name("Cluster Name")
                  .value(clusterName)
                  .build())
              .build());

      List<String> producerIds = response.metrics().stream()
          .flatMap(metric -> metric.dimensions().stream())
          .filter(dim -> "Client ID".equals(dim.name()))
          .map(Dimension::value)
          .distinct()
          .toList();

      logger.debug("Discovered {} producer(s) for cluster {}", producerIds.size(), clusterName);

      return producerIds;

    } catch (CloudWatchException e) {
      if ("ThrottlingException".equals(e.awsErrorDetails().errorCode())) {
        throw new MSKDiscoveryException("CloudWatch API throttled, retry later", e);
      }
      throw new MSKDiscoveryException("Failed to discover producers for cluster: " + clusterName, e);
    } catch (Exception e) {
      throw new MSKDiscoveryException("Failed to discover producers for cluster: " + clusterName, e);
    }
  }

  /**
   * Check if a producer is still active (has recent traffic).
   * <p>
   * Queries CloudWatch metrics for the last 5 minutes to determine
   * if the producer has sent any data recently.
   *
   * @param producerId  Client ID of the producer
   * @param clusterName MSK cluster name
   * @return true if producer has sent data in last 5 minutes
   * @throws MSKDiscoveryException if CloudWatch API fails
   */
  public boolean isProducerActive(String producerId, String clusterName) {
    try {
      Instant endTime = Instant.now();
      Instant startTime = endTime.minus(5, ChronoUnit.MINUTES);

      GetMetricStatisticsResponse stats = cloudWatch.getMetricStatistics(
          GetMetricStatisticsRequest.builder()
              .namespace("AWS/Kafka")
              .metricName("BytesOutPerSec")
              .dimensions(
                  Dimension.builder().name("Cluster Name").value(clusterName).build(),
                  Dimension.builder().name("Client ID").value(producerId).build())
              .startTime(startTime)
              .endTime(endTime)
              .period(300) // 5 minutes
              .statistics(Statistic.SUM)
              .build());

      boolean hasTraffic = stats.datapoints().stream()
          .anyMatch(dp -> dp.sum() != null && dp.sum() > 0);

      logger.debug("Producer {} active check: {}", producerId, hasTraffic);

      return hasTraffic;

    } catch (Exception e) {
      throw new MSKDiscoveryException(
          "Failed to check producer activity: " + producerId, e);
    }
  }
}
