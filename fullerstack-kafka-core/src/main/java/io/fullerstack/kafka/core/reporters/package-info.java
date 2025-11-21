/**
 * Layer 3 (DECIDE Phase) - Reporters for kafka-obs.
 *
 * <p>Reporters subscribe to Monitor conduits (Layer 2) and assess situation urgency
 * by interpreting Monitors.Sign patterns. They emit Situations.Sign (NORMAL, WARNING, CRITICAL)
 * to Situation conduits for consumption by Actors (Layer 4).
 *
 * <h2>OODA Loop Position</h2>
 * <pre>
 * Layer 1 (OBSERVE): Gauges, Counters, Queues, Services, Probes
 *           ↓
 * Layer 2 (ORIENT): Monitors (condition assessment)
 *           ↓
 * Layer 3 (DECIDE): Reporters (situation urgency) ← THIS PACKAGE
 *           ↓
 * Layer 4 (ACT): Actors (automated responses)
 * </pre>
 *
 * <h2>Situation Types</h2>
 * <ul>
 *   <li><b>ProducerHealthReporter</b>: Assesses producer health patterns (buffer overflow, errors, latency)</li>
 *   <li><b>ConsumerHealthReporter</b>: Assesses consumer health patterns (lag, rebalancing, errors)</li>
 *   <li><b>ClusterHealthReporter</b>: Assesses cluster-wide health (broker failures, partition offline)</li>
 * </ul>
 *
 * <h2>Urgency Levels</h2>
 * <ul>
 *   <li><b>NORMAL</b>: Routine operation, no action required</li>
 *   <li><b>WARNING</b>: Attention needed, prepare for potential action</li>
 *   <li><b>CRITICAL</b>: Immediate action required, SLA at risk</li>
 * </ul>
 *
 * <h2>Design Patterns</h2>
 * <ul>
 *   <li><b>Pattern Detection</b>: Track signs over time to detect sustained issues vs. transients</li>
 *   <li><b>Escalation</b>: Single DEGRADED → WARNING, sustained DEGRADED (3+) → CRITICAL</li>
 *   <li><b>De-escalation</b>: STABLE/CONVERGING resets pattern counters</li>
 *   <li><b>Independence</b>: Each entity tracked separately (producer-1, producer-2, etc.)</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // Create circuits
 * Circuit monitorCircuit = cortex().circuit(cortex().name("monitors"));
 * Conduit<Monitors.Monitor, Monitors.Signal> monitors =
 *     monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);
 *
 * Circuit reporterCircuit = cortex().circuit(cortex().name("reporters"));
 * Conduit<Situations.Situation, Situations.Signal> reporters =
 *     reporterCircuit.conduit(cortex().name("reporters"), Situations::composer);
 *
 * // Create reporter
 * ProducerHealthReporter reporter = new ProducerHealthReporter(
 *     monitors,
 *     reporters,
 *     cortex().name("producer-health")
 * );
 *
 * reporter.start();
 *
 * // Monitors emit signs (Layer 2)
 * Monitors.Monitor producer = monitors.percept(cortex().name("producer-1"));
 * producer.degraded(Monitors.Dimension.CONFIRMED);
 *
 * // Situation assesses urgency and emits (Layer 3)
 * // → Situations.Sign.WARNING emitted
 *
 * // Actors subscribe to reporters (Layer 4)
 * reporters.subscribe(cortex().subscriber(
 *     cortex().name("alert-actor"),
 *     (subject, registrar) -> registrar.register(signal -> {
 *         if (signal.sign() == Situations.Sign.CRITICAL) {
 *             sendPagerDutyAlert();
 *         }
 *     })
 * ));
 * }</pre>
 *
 * @see io.humainary.substrates.ext.serventis.ext.Monitors
 * @see io.humainary.substrates.ext.serventis.ext.Reporters
 * @author Fullerstack
 */
package io.fullerstack.kafka.core.reporters;
