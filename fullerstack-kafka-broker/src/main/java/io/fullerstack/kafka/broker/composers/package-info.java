/**
 * Cell Composers for transforming broker metrics into monitoring signals.
 * <p>
 * This package contains Substrates Composers that implement the transformation
 * from raw broker metrics (Layer 2: Serventis) to semantic monitoring signals
 * with health assessments.
 * <p>
 * <b>Key Classes:</b>
 * <ul>
 *   <li>{@link io.kafkaobs.broker.composers.BrokerHealthCellComposer} - Transforms
 *       BrokerMetrics → MonitorSignal with health condition assessment (STABLE/DEGRADED/DOWN)</li>
 * </ul>
 * <p>
 * <b>Architecture Pattern (M18):</b>
 * <pre>{@code
 * public class BrokerHealthCellComposer implements Composer<Pipe<BrokerMetrics>, MonitorSignal> {
 *     @Override
 *     public Pipe<BrokerMetrics> compose(Channel<MonitorSignal> channel) {
 *         Subject<Channel<MonitorSignal>> channelSubject = channel.subject();
 *         Pipe<MonitorSignal> outputPipe = channel.pipe();
 *
 *         return metrics -> {
 *             // Assess health condition
 *             Monitors.Condition condition = assessCondition(metrics);
 *             Monitors.Confidence confidence = assessConfidence(metrics);
 *
 *             // Create MonitorSignal
 *             MonitorSignal signal = MonitorSignal.create(
 *                 channelSubject, condition, confidence, buildContext(metrics)
 *             );
 *
 *             // Emit to output
 *             outputPipe.emit(signal);
 *         };
 *     }
 * }
 * }</pre>
 * <p>
 * <b>Health Assessment Thresholds:</b>
 * <ul>
 *   <li><b>STABLE</b>: heap &lt; 75%, CPU &lt; 70%, no offline partitions</li>
 *   <li><b>DEGRADED</b>: heap 75-90%, CPU 70-85%, underReplicated &gt; 0</li>
 *   <li><b>DOWN</b>: heap &gt; 90%, CPU &gt; 85%, offlinePartitions &gt; 0, activeControllers == 0</li>
 * </ul>
 * <p>
 * <b>Confidence Levels:</b>
 * <ul>
 *   <li><b>CONFIRMED</b>: Metrics &lt; 5 seconds old, STABLE condition</li>
 *   <li><b>MEASURED</b>: Metrics &lt; 30 seconds old</li>
 *   <li><b>TENTATIVE</b>: Metrics &gt; 30 seconds old (stale)</li>
 * </ul>
 *
 * @see io.kafkaobs.broker.models.BrokerMetrics
 * @see io.fullerstack.serventis.signals.MonitorSignal
 * @see io.humainary.serventis.monitors.Monitors
 */
package io.fullerstack.kafka.broker.composers;
