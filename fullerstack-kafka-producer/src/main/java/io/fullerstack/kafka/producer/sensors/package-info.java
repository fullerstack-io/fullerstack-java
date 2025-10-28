/**
 * Sensors for collecting Kafka producer metrics and orchestrating monitoring.
 * <p>
 * <b>Key Components:</b>
 * <ul>
 *   <li>{@link io.fullerstack.kafka.producer.sensors.ProducerMetricsCollector} - Collects metrics via JMX with retry logic and optional connection pooling</li>
 *   <li>{@link io.fullerstack.kafka.producer.sensors.ProducerMonitoringAgent} - Schedules periodic collection for multiple producers</li>
 * </ul>
 * <p>
 * <b>Collection Workflow:</b>
 * <pre>
 * 1. ProducerMonitoringAgent schedules collection (default: 30s interval)
 * 2. ProducerMetricsCollector queries JMX MBeans:
 *    - kafka.producer:type=producer-metrics,client-id={producer-id}
 * 3. Collected metrics emitted to producer Cells
 * 4. ProducerHealthCellComposer transforms to MonitorSignals
 * </pre>
 * <p>
 * <b>Connection Pooling:</b>
 * For high-frequency collection (< 10s interval), use JmxConnectionPool
 * to reduce connection overhead from 50-200ms to <5ms per cycle.
 *
 * @see io.fullerstack.kafka.producer.sensors.ProducerMetricsCollector
 * @see io.fullerstack.kafka.producer.sensors.ProducerMonitoringAgent
 */
package io.fullerstack.kafka.producer.sensors;
