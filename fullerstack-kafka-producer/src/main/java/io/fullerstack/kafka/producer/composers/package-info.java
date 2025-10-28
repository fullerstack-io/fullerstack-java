/**
 * Cell Composers for transforming producer metrics into MonitorSignals.
 * <p>
 * <b>Key Component:</b>
 * <ul>
 *   <li>{@link io.fullerstack.kafka.producer.composers.ProducerHealthCellComposer} - Transforms ProducerMetrics → MonitorSignal with health assessment</li>
 * </ul>
 * <p>
 * <b>Health Assessment Thresholds:</b>
 * <ul>
 *   <li><b>STABLE:</b> avgLatencyMs < 50ms, buffer < 80%, no errors</li>
 *   <li><b>DEGRADED:</b> avgLatencyMs 50-200ms, buffer 80-95%, errors < 1/sec</li>
 *   <li><b>DOWN:</b> avgLatencyMs > 200ms, buffer > 95%, errors ≥ 1/sec</li>
 * </ul>
 * <p>
 * <b>Confidence Levels:</b>
 * <ul>
 *   <li><b>CONFIRMED:</b> Metrics < 5 seconds old</li>
 *   <li><b>MEASURED:</b> Metrics 5-30 seconds old</li>
 *   <li><b>TENTATIVE:</b> Metrics > 30 seconds old</li>
 * </ul>
 * <p>
 * Implements M18 {@code Composer<Pipe<ProducerMetrics>, MonitorSignal>} pattern.
 *
 * @see io.fullerstack.kafka.producer.composers.ProducerHealthCellComposer
 */
package io.fullerstack.kafka.producer.composers;
