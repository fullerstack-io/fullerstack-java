/**
 * Lease observers for Kafka consumer coordination operations.
 * <p>
 * This package implements time-bounded ownership observability for consumer coordination
 * using the Leases API (Serventis PREVIEW). Leases track distributed coordination patterns:
 * leadership election, partition ownership, and session management.
 * <p>
 * <b>Observers in this package:</b>
 * <ul>
 *   <li>{@link io.fullerstack.kafka.consumer.leases.ConsumerGroupLeaseObserver} -
 *       Tracks group coordinator leadership as lease-based coordination</li>
 *   <li>{@link io.fullerstack.kafka.consumer.leases.PartitionAssignmentLeaseObserver} -
 *       Tracks partition assignment ownership as time-bounded leases</li>
 * </ul>
 *
 * <h3>Lease Model:</h3>
 * <pre>
 * Dual-Dimension:
 * - LESSOR: Authority perspective (group coordinator)
 * - LESSEE: Client perspective (consumer instance)
 *
 * Lifecycle:
 * Request:  ACQUIRE (lessee) → GRANT (lessor) | DENY (lessor)
 *              ↓
 * Holding:  [lease active with TTL]
 *              ↓
 * Extension: RENEW (lessee) → EXTEND (lessor) [heartbeat]
 *              ↓
 * Termination: RELEASE (lessee)     [voluntary shutdown]
 *          OR: EXPIRE (lessor)       [session timeout]
 *          OR: REVOKE (lessor)       [forced rebalance]
 * </pre>
 *
 * <h3>Pure Reactive Design:</h3>
 * <pre>
 * Observers emit signals in response to Kafka consumer lifecycle events:
 * - ConsumerRebalanceListener callbacks → ACQUIRE, GRANT, DENY, REVOKE
 * - Successful poll() → RENEW, EXTEND (heartbeat implicit in poll)
 * - consumer.close() → RELEASE
 * - Session timeout → EXPIRE
 *
 * NO schedulers or timers - Kafka consumer handles heartbeat internally.
 * Observers simply report what Kafka does using lease semantics.
 * </pre>
 *
 * <h3>Integration with OODA Loop:</h3>
 * <pre>
 * Layer 1 (OBSERVE):  Gauges → Track session age, assignment count
 * Layer 2 (ORIENT):   Monitors → Assess DEGRADED when session expires
 * Layer 2.5:          Leases → Track ownership lifecycle (NEW)
 * Layer 4 (ACT):      Agents → Rejoin group, request rebalance
 * </pre>
 *
 * <h3>Use Cases:</h3>
 * <ul>
 *   <li><b>Leadership Tracking</b>: Which consumer is group leader? When did leadership change?</li>
 *   <li><b>Partition Ownership</b>: Which partitions does each consumer own? When were they assigned?</li>
 *   <li><b>Session Management</b>: Track session expiration, voluntary leaves, forced revocations</li>
 *   <li><b>Coordination Observability</b>: Visualize rebalance patterns, lease contention</li>
 * </ul>
 *
 * @author Fullerstack
 * @since 1.0.0
 */
package io.fullerstack.kafka.consumer.leases;
