package io.fullerstack.kafka.core.baseline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages health thresholds for Kafka observability.
 * <p>
 * This service stores and provides access to dynamically-derived thresholds
 * based on MSK cluster configuration (Epic 5) and other sources (Epic 6).
 * <p>
 * <b>NOTE</b>: Minimal implementation created in Epic 5 Story 5.4.
 * Epic 6 Story 6.2 will enhance with additional features (e.g., persistence,
 * baseline learning, statistical analysis).
 * <p>
 * <b>Integration Points</b>:
 * <ul>
 *   <li>Epic 5: MSK configuration discovery writes thresholds here</li>
 *   <li>Epic 1: Assessors read thresholds to determine health status</li>
 *   <li>Epic 6: Context providers will enhance with learned baselines</li>
 * </ul>
 *
 * @author Fullerstack
 */
public class BaselineService {

  private final Map<String, Threshold> thresholds = new ConcurrentHashMap<>();

  /**
   * Updates or adds a threshold for a specific metric.
   *
   * @param metric    Metric identifier (e.g., "consumer.lag.max", "producer.buffer.util")
   * @param threshold The threshold values to apply
   */
  public void updateThreshold(String metric, Threshold threshold) {
    thresholds.put(metric, threshold);
  }

  /**
   * Retrieves the threshold for a specific metric.
   *
   * @param metric Metric identifier
   * @return The threshold, or null if not defined
   */
  public Threshold getThreshold(String metric) {
    return thresholds.get(metric);
  }

  /**
   * Removes a threshold for a specific metric.
   *
   * @param metric Metric identifier
   * @return The removed threshold, or null if not found
   */
  public Threshold removeThreshold(String metric) {
    return thresholds.remove(metric);
  }

  /**
   * Clears all thresholds.
   * Primarily for testing purposes.
   */
  public void clear() {
    thresholds.clear();
  }

  /**
   * Gets the number of configured thresholds.
   *
   * @return Count of thresholds
   */
  public int size() {
    return thresholds.size();
  }
}
