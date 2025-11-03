package io.fullerstack.kafka.core.baseline;

/**
 * Represents health threshold values for a metric.
 * <p>
 * Created in Epic 5 Story 5.4 (Configuration-Aware Thresholds).
 * Epic 6 Story 6.2 will enhance with additional features.
 *
 * @author Fullerstack
 */
public class Threshold {

  private final long normalMax;
  private final long warningMax;
  private final long criticalMax;
  private final String source;

  /**
   * Creates a new threshold definition.
   *
   * @param normalMax   Maximum value for normal/healthy operation
   * @param warningMax  Maximum value before entering warning state
   * @param criticalMax Maximum value before entering critical state
   * @param source      Description of where this threshold came from (e.g., "MSK config: segment.bytes")
   */
  public Threshold(long normalMax, long warningMax, long criticalMax, String source) {
    this.normalMax = normalMax;
    this.warningMax = warningMax;
    this.criticalMax = criticalMax;
    this.source = source;
  }

  public long getNormalMax() {
    return normalMax;
  }

  public long getWarningMax() {
    return warningMax;
  }

  public long getCriticalMax() {
    return criticalMax;
  }

  public String getSource() {
    return source;
  }

  @Override
  public String toString() {
    return "Threshold{" +
        "normalMax=" + normalMax +
        ", warningMax=" + warningMax +
        ", criticalMax=" + criticalMax +
        ", source='" + source + '\'' +
        '}';
  }
}
