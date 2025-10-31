package io.fullerstack.kafka.consumer.composers;

import io.fullerstack.kafka.consumer.models.ConsumerMetrics;
import io.humainary.serventis.monitors.Monitors;
import io.humainary.substrates.api.Substrates.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RC1 Cell Composer that transforms ConsumerMetrics into Monitors.Status signals.
 * <p>
 * Uses Humainary Serventis RC1 API directly - no wrapper classes.
 * <p>
 * <b>Health Assessment (Hardcoded Thresholds):</b>
 * - STABLE: lag < 1000, processing < 100ms, rebalances < 5
 * - DEGRADED: moderate issues (lag 1000-10000, processing 100-500ms, rebalances 5-10)
 * - DOWN: lag > 10000, processing > 500ms, rebalances > 10
 * <p>
 * <b>Confidence Assessment:</b>
 * - CONFIRMED: metrics < 5s old
 * - MEASURED: metrics 5-30s old
 * - TENTATIVE: metrics > 30s old
 *
 * @see Monitors
 * @see ConsumerMetrics
 */
public class ConsumerHealthCellComposer implements Composer<Monitors.Status, Pipe<ConsumerMetrics>> {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerHealthCellComposer.class);

    @Override
    public Pipe < ConsumerMetrics > compose (
      final Channel < Monitors.Status > channel
    ) {

      final var channelSubject = channel.subject ();
      final var outputPipe     = channel.pipe ();

      logger.debug (
        "Creating ConsumerHealthCellComposer for subject: {}",
        channelSubject.name ()
      );

      // Return input Pipe that transforms ConsumerMetrics → Monitors.Status
      return new Pipe <> () {

        @Override
        public void emit (
          final ConsumerMetrics metrics
        ) {

          try {

            // Assess consumer health condition
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
                "Consumer {} health: {} ({}), lag: {}, latency: {}ms",
                metrics.consumerGroup (),
                condition,
                confidence,
                metrics.totalLag (),
                String.format ( "%.1f", metrics.avgProcessingLatencyMs () )
              );

            }

            // Emit pure semantic assessment
            outputPipe.emit ( status );

          } catch (
            final Exception e
          ) {

            logger.error (
              "Failed to transform ConsumerMetrics for consumer {}",
              metrics.consumerGroup (),
              e
            );

          }

        }

      };

    }

    /**
     * Assess consumer condition based on lag, processing latency, and rebalance frequency.
     * <p>
     * Priority order (most critical first):
     * 1. DOWN - Critical issues (lag > 10000, processing > 500ms, rebalances > 10)
     * 2. DEGRADED - Moderate issues (lag 1000-10000, processing 100-500ms, rebalances 5-10)
     * 3. STABLE - Normal operation (all checks passed)
     *
     * @param metrics Consumer metrics to assess
     * @return assessed Monitors.Condition
     */
    private Monitors.Condition assessCondition (
      final ConsumerMetrics metrics
    ) {

      // DOWN conditions (most critical)
      if (
        metrics.totalLag () > 10000
          || metrics.avgProcessingLatencyMs () > 500
          || metrics.rebalanceCount () > 10
      ) {

        logger.warn (
          "Consumer {} DOWN: lag={}, latency={}ms, rebalances={}",
          metrics.consumerGroup (),
          metrics.totalLag (),
          String.format ( "%.1f", metrics.avgProcessingLatencyMs () ),
          metrics.rebalanceCount ()
        );

        return Monitors.Condition.DOWN;

      }

      // STABLE conditions (all checks passed)
      if (
        metrics.totalLag () < 1000
          && metrics.avgProcessingLatencyMs () < 100
          && metrics.rebalanceCount () < 5
      ) {

        logger.debug ( "Consumer {} STABLE", metrics.consumerGroup () );
        return Monitors.Condition.STABLE;

      }

      // DEGRADED (moderate issues)
      logger.info (
        "Consumer {} DEGRADED: lag={}, latency={}ms, rebalances={}",
        metrics.consumerGroup (),
        metrics.totalLag (),
        String.format ( "%.1f", metrics.avgProcessingLatencyMs () ),
        metrics.rebalanceCount ()
      );

      return Monitors.Condition.DEGRADED;

    }

    /**
     * Assess confidence based on metric age.
     * <p>
     * Uses Monitors.Confidence values:
     * - CONFIRMED: High confidence (metrics < 5s old)
     * - MEASURED: Medium confidence (metrics 5-30s old)
     * - TENTATIVE: Low confidence (metrics > 30s old)
     *
     * @param metrics Consumer metrics to assess
     * @return assessed Monitors.Confidence
     */
    private Monitors.Confidence assessConfidence (
      final ConsumerMetrics metrics
    ) {

      final var ageMs =
        System.currentTimeMillis () - metrics.timestamp ();

      // TENTATIVE if metrics are stale (> 30s)
      if ( ageMs > 30_000 ) {

        logger.debug (
          "Consumer {} metrics are {}ms old, confidence TENTATIVE",
          metrics.consumerGroup (),
          ageMs
        );

        return Monitors.Confidence.TENTATIVE;

      }

      // MEASURED if metrics are somewhat fresh (5-30s)
      if ( ageMs > 5_000 ) {

        return Monitors.Confidence.MEASURED;

      }

      // CONFIRMED for fresh metrics (< 5s)
      return Monitors.Confidence.CONFIRMED;

    }
}
