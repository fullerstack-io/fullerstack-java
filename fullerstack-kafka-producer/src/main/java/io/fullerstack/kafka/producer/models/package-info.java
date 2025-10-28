/**
 * Domain models for Kafka producer metrics and events.
 * <p>
 * Contains immutable record types representing producer operational data:
 * <ul>
 *   <li>{@link io.fullerstack.kafka.producer.models.ProducerMetrics} - Performance metrics (send rate, latency, buffer, errors)</li>
 * </ul>
 * <p>
 * All models use Java 25 records with compact constructor validation.
 * Immutability ensures thread-safe sharing across async signal processing.
 *
 * @see io.fullerstack.kafka.producer.models.ProducerMetrics
 */
package io.fullerstack.kafka.producer.models;
