package io.fullerstack.kafka.producer.composers;

import io.fullerstack.kafka.producer.models.ProducerMetrics;
import io.humainary.serventis.monitors.Monitors;
import io.humainary.substrates.api.Substrates.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cell Composer that transforms ProducerMetrics into Monitors.Status assessments.
 * <p>
 * Implements Substrates RC1 Composer pattern by providing Pipe<ProducerMetrics> that accepts
 * producer metrics and emits Monitors.Status (pure semantic assessments: Condition + Confidence).
 * <p>
 * In RC1, diagnostic metadata (latency, buffer utilization, etc.) should be stored in Subject.state()
 * rather than signal payload. The Composer emits only semantic condition assessments.
 *
 * <h3>Health Assessment Thresholds:</h3>
 * <ul>
 *   <li><b>STABLE</b>: avgLatencyMs &lt; 50ms, buffer &lt; 80%, no errors</li>
 *   <li><b>DEGRADED</b>: avgLatencyMs 50-200ms, buffer 80-95%, errors &lt; 1/sec</li>
 *   <li><b>DOWN</b>: avgLatencyMs &gt; 200ms, buffer &gt; 95%, errors ≥ 1/sec</li>
 * </ul>
 *
 * <h3>Confidence Levels:</h3>
 * <ul>
 *   <li><b>CONFIRMED</b>: Metrics &lt; 5 seconds old</li>
 *   <li><b>MEASURED</b>: Metrics 5-30 seconds old</li>
 *   <li><b>TENTATIVE</b>: Metrics &gt; 30 seconds old</li>
 * </ul>
 *
 * @author Fullerstack
 * @see io.fullerstack.kafka.producer.models.ProducerMetrics
 * @see Monitors.Status
 * @see io.humainary.substrates.api.Substrates.Composer
 */
public class ProducerHealthCellComposer implements Composer<Monitors.Status, Pipe<ProducerMetrics>> {

    private static final Logger logger = LoggerFactory.getLogger(ProducerHealthCellComposer.class);

    // Health assessment thresholds
    private static final double LATENCY_STABLE_THRESHOLD_MS = 50.0;
    private static final double LATENCY_DOWN_THRESHOLD_MS = 200.0;
    private static final double BUFFER_STABLE_THRESHOLD = 0.80;    // 80%
    private static final double BUFFER_DOWN_THRESHOLD = 0.95;      // 95%
    private static final long ERROR_RATE_DOWN_THRESHOLD = 1;       // 1 error/sec

    // Confidence thresholds (milliseconds)
    private static final long FRESH_METRICS_MS = 5_000;            // 5 seconds
    private static final long RECENT_METRICS_MS = 30_000;          // 30 seconds

    @Override
    public Pipe < ProducerMetrics > compose (
      final Channel < Monitors.Status > channel
    ) {

      final var channelSubject = channel.subject ();
      final var outputPipe     = channel.pipe ();

      logger.debug (
        "Creating ProducerHealthCellComposer for subject: {}",
        channelSubject.name ()
      );

      // Return input Pipe that transforms ProducerMetrics → Monitors.Status
      return new Pipe <> () {

        @Override
        public void emit (
          final ProducerMetrics metrics
        ) {

          try {

            // Assess producer health condition
            final var condition  = assessCondition ( metrics );
            final var confidence = assessConfidence ( metrics );

            // Create pure semantic Status (no payload in RC1)
            final var status =
              new Monitors.Status (
                condition,
                confidence
              );

            // Log signal emission
            if ( logger.isDebugEnabled () ) {

              logger.debug (
                "Producer {} health: {} ({}), latency: {}ms, buffer: {}%",
                metrics.producerId (),
                condition,
                confidence,
                String.format ( "%.1f", metrics.avgLatencyMs () ),
                String.format ( "%.1f", metrics.bufferUtilization () * 100 )
              );

            }

            // Emit pure semantic assessment
            outputPipe.emit ( status );

          } catch (
            final Exception e
          ) {

            logger.error (
              "Failed to transform ProducerMetrics for producer {}",
              metrics.producerId (),
              e
            );

          }

        }

      };

    }

    /**
     * Assess producer health condition based on metrics.
     * <p>
     * Evaluates latency, buffer utilization, and error rates to determine
     * overall producer health condition.
     *
     * @param metrics ProducerMetrics to assess
     * @return Health condition (STABLE, DEGRADED, or DOWN)
     */
    private Monitors.Condition assessCondition (
      final ProducerMetrics metrics
    ) {

      final var avgLatency = metrics.avgLatencyMs ();
      final var bufferUtil = metrics.bufferUtilization ();
      final var errorRate  = metrics.recordErrorRate ();

      // DOWN: Critical issues - producer may be blocking or failing
      if (
        avgLatency > LATENCY_DOWN_THRESHOLD_MS
          || bufferUtil > BUFFER_DOWN_THRESHOLD
          || errorRate >= ERROR_RATE_DOWN_THRESHOLD
      ) {

        return Monitors.Condition.DOWN;

      }

      // DEGRADED: Performance degradation - producer under stress
      if (
        avgLatency > LATENCY_STABLE_THRESHOLD_MS
          || bufferUtil > BUFFER_STABLE_THRESHOLD
          || errorRate > 0
      ) {

        return Monitors.Condition.DEGRADED;

      }

      // STABLE: Healthy producer
      return Monitors.Condition.STABLE;

    }

    /**
     * Assess confidence level based on metric age.
     * <p>
     * Fresh metrics warrant higher confidence than stale metrics.
     *
     * @param metrics ProducerMetrics to assess
     * @return Confidence level (CONFIRMED, MEASURED, or TENTATIVE)
     */
    private Monitors.Confidence assessConfidence (
      final ProducerMetrics metrics
    ) {

      final var ageMs = metrics.ageMs ();

      if ( ageMs < FRESH_METRICS_MS ) {

        return Monitors.Confidence.CONFIRMED;

      } else if ( ageMs < RECENT_METRICS_MS ) {

        return Monitors.Confidence.MEASURED;

      } else {

        return Monitors.Confidence.TENTATIVE;

      }

    }

}
