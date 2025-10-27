/**
 * Sensors for collecting broker metrics from Kafka JMX endpoints.
 * <p>
 * This package provides the data collection layer (Layer 2: Serventis) for
 * gathering operational metrics from Kafka brokers via JMX and emitting them
 * to the Cell hierarchy for transformation and monitoring.
 * <p>
 * <b>Key Classes:</b>
 * <ul>
 *   <li>{@link io.kafkaobs.broker.sensors.JmxMetricsCollector} - Collects 13 JMX metrics
 *       from a single Kafka broker endpoint</li>
 *   <li>{@link io.kafkaobs.broker.sensors.BrokerMonitoringAgent} - Schedules periodic
 *       collection from multiple brokers and emits to Cell hierarchy</li>
 * </ul>
 * <p>
 * <b>JMX Metrics Collected:</b>
 * <ol>
 *   <li><b>Heap Memory</b>: used/max from java.lang:type=Memory</li>
 *   <li><b>CPU Usage</b>: ProcessCpuLoad from java.lang:type=OperatingSystem</li>
 *   <li><b>Request Rate</b>: MessagesInPerSec from kafka.server:type=BrokerTopicMetrics</li>
 *   <li><b>Byte Rates</b>: BytesInPerSec, BytesOutPerSec</li>
 *   <li><b>Controller Status</b>: ActiveControllerCount</li>
 *   <li><b>Replication Health</b>: UnderReplicatedPartitions, OfflineReplicaCount</li>
 *   <li><b>Thread Pool Idle</b>: NetworkProcessorAvgIdlePercent, RequestHandlerAvgIdlePercent</li>
 *   <li><b>Request Latencies</b>: FetchConsumer, Produce TotalTimeMs</li>
 * </ol>
 * <p>
 * <b>Collection Strategy:</b>
 * <ul>
 *   <li>Periodic collection every {@code collectionIntervalMs} (default 30 seconds)</li>
 *   <li>Retry logic with exponential backoff (3 attempts)</li>
 *   <li>Timeout per collection (5 seconds)</li>
 *   <li>Graceful failure handling - continues with other brokers</li>
 *   <li>VectorClock increment before each emission for causal ordering</li>
 * </ul>
 * <p>
 * <b>Usage Example:</b>
 * <pre>{@code
 * // Direct JMX collection
 * JmxMetricsCollector collector = new JmxMetricsCollector();
 * BrokerMetrics metrics = collector.collect("localhost:11001");
 *
 * // Scheduled collection with Cell emission
 * ClusterConfig config = ClusterConfig.withDefaults("localhost:9092", "localhost:11001");
 * KafkaCircuitSetup setup = new KafkaCircuitSetup(config);
 * BrokerMonitoringAgent agent = setup.createMonitoringAgent();
 *
 * agent.start();  // Begins periodic collection
 * // ... monitoring runs ...
 * agent.shutdown();  // Graceful shutdown
 * }</pre>
 * <p>
 * <b>Error Handling:</b>
 * <ul>
 *   <li>JMX connection failures: Retry with exponential backoff</li>
 *   <li>Metric collection failures: Log error, continue with other brokers</li>
 *   <li>Missing MBeans: Use default/zero values, log warning</li>
 *   <li>Timeout: Cancel collection after 5 seconds</li>
 * </ul>
 *
 * @see io.kafkaobs.broker.models.BrokerMetrics
 * @see io.kafkaobs.broker.circuit.KafkaCircuitSetup
 * @see io.kafkaobs.core.config.ClusterConfig
 */
package io.fullerstack.kafka.broker.sensors;
