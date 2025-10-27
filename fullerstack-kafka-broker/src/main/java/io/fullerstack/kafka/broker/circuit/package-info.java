/**
 * Circuit and Cell hierarchy setup for Kafka monitoring.
 * <p>
 * This package provides the infrastructure for creating and managing Substrates
 * Circuit and Cell hierarchies for AWS MSK cluster monitoring.
 * <p>
 * <b>Key Classes:</b>
 * <ul>
 *   <li>{@link io.kafkaobs.broker.circuit.KafkaCircuitSetup} - Sets up Circuit and Cell hierarchy
 *       for an AWS MSK cluster with one Circuit per cluster strategy</li>
 * </ul>
 * <p>
 * <b>Architecture:</b>
 * <pre>
 * Circuit: "prod-account.us-east-1.transactions-cluster"
 * │
 * └─ clusterCell (Root Cell)
 *    ├── broker-1 (Child Cell)
 *    ├── broker-2 (Child Cell)
 *    └── broker-3 (Child Cell)
 * </pre>
 * <p>
 * Each MSK cluster gets its own isolated Circuit for:
 * <ul>
 *   <li>Operational isolation between clusters</li>
 *   <li>Independent lifecycle management</li>
 *   <li>Regional deployment optimization</li>
 *   <li>Simplified debugging</li>
 * </ul>
 * <p>
 * <b>Usage Example:</b>
 * <pre>{@code
 * ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
 * KafkaCircuitSetup setup = new KafkaCircuitSetup(config);
 *
 * // Get cluster Cell - subscribe to receive all broker signals
 * Cell<BrokerMetrics, MonitorSignal> clusterCell = setup.getClusterCell();
 * clusterCell.subscribe(...);
 *
 * // Get specific broker Cell for emitting metrics
 * Cell<BrokerMetrics, MonitorSignal> broker1 = setup.getBrokerCell("b-1");
 * broker1.emit(brokerMetrics);
 *
 * // Create monitoring agent
 * BrokerMonitoringAgent agent = setup.createMonitoringAgent();
 * agent.start();
 *
 * // Cleanup
 * agent.shutdown();
 * setup.close();
 * }</pre>
 *
 * @see io.kafkaobs.core.config.ClusterConfig
 * @see io.kafkaobs.broker.composers.BrokerHealthCellComposer
 * @see io.kafkaobs.broker.sensors.BrokerMonitoringAgent
 */
package io.fullerstack.kafka.broker.circuit;
