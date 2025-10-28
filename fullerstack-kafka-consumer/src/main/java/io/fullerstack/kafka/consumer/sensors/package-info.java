/**
 * Consumer sensors for AdminClient and JMX metrics collection.
 * <p>
 * This package contains sensor implementations that collect consumer metrics:
 * <ul>
 *   <li>{@link io.fullerstack.kafka.consumer.sensors.ConsumerLagCollector} - AdminClient-based lag collection</li>
 *   <li>{@link io.fullerstack.kafka.consumer.sensors.ConsumerMetricsCollector} - JMX metrics collection</li>
 *   <li>{@link io.fullerstack.kafka.consumer.sensors.ConsumerSensor} - Main sensor coordinating metric collection</li>
 * </ul>
 * <p>
 * <b>Important</b>: Sensors have NO Substrates dependencies. They emit metrics via {@link java.util.function.BiConsumer}.
 */
package io.fullerstack.kafka.consumer.sensors;
