/**
 * Circuit breaker observers for Kafka producer operations.
 * <p>
 * This package implements the circuit breaker pattern for resilience observability
 * using the Breakers API (Serventis PREVIEW). Circuit breakers prevent cascading failures
 * by failing fast when error thresholds are exceeded.
 * <p>
 * <b>Observers in this package:</b>
 * <ul>
 *   <li>{@link io.fullerstack.kafka.producer.breakers.ProducerSendBreakerObserver} -
 *       Circuit breaker for producer send operations</li>
 * </ul>
 *
 * <h3>Circuit Breaker State Machine:</h3>
 * <pre>
 * CLOSED ──[failures exceed threshold]──> OPEN
 *   ▲                                       │
 *   │                                       │
 *   │                                 [timeout expires]
 *   │                                       │
 *   │                                       ▼
 *   └──[success]── HALF_OPEN ◄──[test request]
 *       │
 *       └──[failure]──> OPEN
 * </pre>
 *
 * <h3>Integration with OODA Loop:</h3>
 * <pre>
 * Layer 1 (OBSERVE):  Probes/Services → Monitor failures
 * Layer 2 (ORIENT):   Monitors → Assess DEGRADED condition
 * Layer 2.5:          Breakers → Circuit state (TRIP/OPEN/HALF_OPEN)
 * Layer 4 (ACT):      Agents → Throttle/pause based on breaker state
 * </pre>
 *
 * @author Fullerstack
 * @since 1.0.0
 */
package io.fullerstack.kafka.producer.breakers;
