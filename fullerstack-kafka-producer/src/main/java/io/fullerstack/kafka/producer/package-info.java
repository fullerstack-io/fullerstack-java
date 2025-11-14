/**
 * Kafka Producer Monitoring - Semiotic observability for Kafka producers.
 * <p>
 * This module provides Cell-based monitoring infrastructure for Kafka producers,
 * transforming raw JMX metrics into actionable MonitorSignals using M18 patterns.
 * <p>
 * <b>Key Components:</b>
 * <ul>
 *   <li>{@link io.fullerstack.kafka.producer.config.ProducerCircuitSetup} - Circuit and Cell hierarchy for producers</li>
 *   <li>{@link io.fullerstack.kafka.producer.composers.ProducerHealthCellComposer} - Transforms metrics to signals</li>
 *   <li>{@link io.fullerstack.kafka.producer.sensors.ProducerMetricsCollector} - Collects JMX metrics</li>
 *   <li>{@link io.fullerstack.kafka.producer.sensors.ProducerMonitoringAgent} - Schedules periodic collection</li>
 *   <li>{@link io.fullerstack.kafka.producer.models.ProducerMetrics} - Domain model for producer performance</li>
 * </ul>
 * <p>
 * <b>Architecture Pattern:</b>
 * <pre>
 * ProducerMonitoringAgent (scheduler)
 *   ↓ collects via
 * ProducerMetricsCollector (JMX)
 *   ↓ emits
 * ProducerCell (dynamic hierarchy)
 *   ↓ transforms via
 * ProducerHealthCellComposer
 *   ↓ produces
 * MonitorSignal (STABLE/DEGRADED/DOWN)
 * </pre>
 * <p>
 * <b>Usage Example:</b>
 * <pre>{@code
 * ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
 *
 * try (ProducerCircuitSetup setup = new ProducerCircuitSetup(config)) {
 *     // Create monitoring agent
 *     ProducerMonitoringAgent agent = setup.createMonitoringAgent(30_000);
 *     agent.registerProducer("my-producer");
 *     agent.start();
 *
 *     // Subscribe to all producer signals
 *     setup.getProducerRootCell().subscribe(cortex().subscriber(
 *         cortex().name("producer-monitor"),
 *         (subject, registrar) -> {
 *             registrar.register(signal -> {
 *                 System.out.printf("Producer health: %s (%s)%n",
 *                     signal.status().condition(),
 *                     signal.payload().percept("producerId"));
 *             });
 *         }
 *     ));
 * }
 * }</pre>
 *
 * @see io.fullerstack.kafka.producer.config.ProducerCircuitSetup
 * @see io.fullerstack.kafka.producer.models.ProducerMetrics
 */
package io.fullerstack.kafka.producer;
