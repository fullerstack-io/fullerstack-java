/**
 * Circuit breaker observers for Kafka consumer operations.
 * <p>
 * This package implements the circuit breaker pattern for consumer resilience observability
 * using the Breakers API (Serventis PREVIEW). Circuit breakers prevent consumer overload
 * by pausing consumption when lag thresholds are exceeded.
 * <p>
 * <b>Observers in this package:</b>
 * <ul>
 *   <li>{@link io.fullerstack.kafka.consumer.breakers.ConsumerLagBreakerObserver} -
 *       Circuit breaker for consumer lag management (pause/resume)</li>
 * </ul>
 *
 * <h3>Consumer Lag Circuit Breaker:</h3>
 * <pre>
 * Lag Thresholds:
 * - TRIP:      ≥50,000 messages (prevent OOM)
 * - HALF_OPEN: ≤10,000 messages (safe to test)
 * - CLOSE:     ≤1,000 messages (full resume)
 *
 * State Transitions:
 * CLOSED ──[lag ≥50k]──> TRIP → OPEN (pause)
 *   ▲                           │
 *   │                    [lag ≤10k]
 *   │                           │
 *   └──[lag ≤1k]── HALF_OPEN ◄──┘ (partial resume: 1 partition)
 *       │
 *       └──[lag ≥50k]──> OPEN (pause again)
 * </pre>
 *
 * <h3>Integration with OODA Loop:</h3>
 * <pre>
 * Layer 1 (OBSERVE):  Gauges → Track lag metrics
 * Layer 2 (ORIENT):   Monitors → Assess DEGRADED when lag excessive
 * Layer 2.5:          Breakers → Circuit state (TRIP/OPEN/HALF_OPEN)
 * Layer 4 (ACT):      Agents → Pause/resume consumer based on breaker state
 * </pre>
 *
 * @author Fullerstack
 * @since 1.0.0
 */
package io.fullerstack.kafka.consumer.breakers;
