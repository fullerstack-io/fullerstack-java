/**
 * Consumer domain models.
 * <p>
 * This package previously contained data bag models (ConsumerMetrics, ConsumerEvent)
 * which were deleted per ADR-001 signal-first architecture.
 * Sensors now emit Services.Signal and Queues.Signal directly.
 * <p>
 * Package retained for future internal data structures (not data bags for signal emission).
 */
package io.fullerstack.kafka.consumer.models;
