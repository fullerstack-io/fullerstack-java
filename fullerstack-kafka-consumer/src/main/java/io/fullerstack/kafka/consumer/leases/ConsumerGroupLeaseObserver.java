package io.fullerstack.kafka.consumer.leases;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Leases;
import io.humainary.substrates.ext.serventis.ext.Leases.Lease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Observes consumer group coordinator leadership as lease-based coordination.
 * <p>
 * This observer tracks leadership leases within a Kafka consumer group using the Leases API
 * (Serventis PREVIEW). It emits lease signals when:
 * <ul>
 *   <li>Rebalance begins - consumers request leadership lease (ACQUIRE)</li>
 *   <li>Consumer becomes leader - coordinator grants lease (GRANT)</li>
 *   <li>Consumer loses leader election - coordinator denies lease (DENY)</li>
 *   <li>Consumer receives heartbeat response - coordinator extends lease (EXTEND)</li>
 *   <li>Consumer voluntarily leaves - releases leadership lease (RELEASE)</li>
 *   <li>Consumer session expires - coordinator expires lease (EXPIRE)</li>
 * </ul>
 *
 * <h3>Dual-Dimension Model:</h3>
 * <pre>
 * LESSOR (Coordinator):  GRANT, DENY, EXTEND, EXPIRE, REVOKE
 * LESSEE (Consumer):     ACQUIRE, RENEW, RELEASE
 * </pre>
 *
 * <h3>Leadership Lease Lifecycle:</h3>
 * <pre>
 * Rebalance:  All consumers → ACQUIRE (lessee)
 *                Coordinator → GRANT (lessor) to leader
 *                Coordinator → DENY (lessor) to followers
 *                      ↓
 * Heartbeat:  Leader → RENEW (lessee) [via poll()]
 *             Coordinator → EXTEND (lessor) [heartbeat response]
 *                      ↓
 * Termination:
 *   Voluntary:  Leader → RELEASE (lessee) [consumer.close()]
 *   Timeout:    Coordinator → EXPIRE (lessor) [session timeout]
 *   Revoke:     Coordinator → REVOKE (lessor) [forced rebalance]
 * </pre>
 *
 * <h3>Pure Reactive Design:</h3>
 * This observer is purely reactive - it emits signals in response to Kafka consumer
 * lifecycle events (rebalance callbacks, poll responses, session timeouts). It does NOT:
 * <ul>
 *   <li>Create its own heartbeat scheduler (Kafka consumer handles this)</li>
 *   <li>Implement lease renewal logic (that's Kafka's job)</li>
 *   <li>Make policy decisions (that's Layer 4 Agents)</li>
 * </ul>
 * <p>
 * The observer simply **reports what Kafka does** using lease semantics.
 *
 * <h3>Integration with Kafka Consumer API:</h3>
 * <pre>{@code
 * // In your ConsumerRebalanceListener:
 * @Override
 * public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
 *     if (isLeader()) {
 *         leaseObserver.onBecameLeader();
 *     } else {
 *         leaseObserver.onBecameFollower(leaderId);
 *     }
 * }
 *
 * // In your poll loop:
 * ConsumerRecords<K, V> records = consumer.poll(Duration.ofMillis(100));
 * if (isLeader()) {
 *     leaseObserver.onHeartbeatSuccess();  // Poll succeeded = heartbeat renewed
 * }
 *
 * // On shutdown:
 * leaseObserver.onConsumerLeaving();
 * leaseObserver.close();
 * }</pre>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("consumer.monitoring"));
 *
 * ConsumerGroupLeaseObserver observer = new ConsumerGroupLeaseObserver(
 *     circuit,
 *     "consumer-group-1",
 *     "consumer-1"
 * );
 *
 * // Leases signals now emitted based on Kafka events
 * Conduit<Lease, Leases.Signal> leases = observer.leases();
 * leases.subscribe(...);
 *
 * // Later...
 * observer.close();
 * }</pre>
 *
 * @author Fullerstack
 * @see Leases
 */
public class ConsumerGroupLeaseObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerGroupLeaseObserver.class);

    private final Cortex cortex;
    private final Circuit circuit;
    private final String groupId;
    private final String consumerId;
    private final Conduit<Lease, Leases.Signal> leases;
    private final Lease groupLease;

    /**
     * Creates a new consumer group lease observer.
     *
     * @param circuit     Circuit for creating leases conduit
     * @param groupId     Consumer group identifier (e.g., "consumer-group-1")
     * @param consumerId  Consumer identifier (e.g., "consumer-1")
     */
    public ConsumerGroupLeaseObserver(
        Circuit circuit,
        String groupId,
        String consumerId
    ) {
        this.cortex = cortex();
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        this.groupId = Objects.requireNonNull(groupId, "groupId cannot be null");
        this.consumerId = Objects.requireNonNull(consumerId, "consumerId cannot be null");

        // Create Leases conduit
        this.leases = circuit.conduit(
            cortex.name("leases"),
            Leases::composer
        );

        // Get Lease for group leadership
        this.groupLease = leases.percept(cortex.name("group." + groupId + ".leadership"));

        logger.info("[LEASES] ConsumerGroupLeaseObserver created for {} in group {} (pure reactive)",
            consumerId, groupId);
    }

    /**
     * Called when rebalance starts - consumer requests leadership lease.
     * <p>
     * Triggered by: ConsumerRebalanceListener.onPartitionsRevoked()
     */
    public void onRebalanceStarted() {
        // LESSEE perspective: Request leadership lease
        groupLease.acquire(Leases.Dimension.LESSEE);

        logger.info("[LEASES] {} requesting leadership for group {} (ACQUIRE/LESSEE)",
            consumerId, groupId);
    }

    /**
     * Called when this consumer becomes the group leader.
     * <p>
     * Triggered by: ConsumerRebalanceListener.onPartitionsAssigned() when isLeader() = true
     */
    public void onBecameLeader() {
        // LESSOR perspective: Coordinator granted lease to this consumer
        groupLease.grant(Leases.Dimension.LESSOR);

        logger.info("[LEASES] {} became leader for group {} (GRANT/LESSOR)",
            consumerId, groupId);
    }

    /**
     * Called when another consumer becomes the group leader (this consumer was denied).
     * <p>
     * Triggered by: ConsumerRebalanceListener.onPartitionsAssigned() when isLeader() = false
     *
     * @param leaderId Identifier of the consumer that became leader
     */
    public void onBecameFollower(String leaderId) {
        // LESSOR perspective: Coordinator granted lease to another consumer
        // (This signal represents coordinator's decision, not this consumer's action)
        groupLease.grant(Leases.Dimension.LESSOR);

        // LESSEE perspective: This consumer was denied leadership
        groupLease.deny(Leases.Dimension.LESSEE);

        logger.info("[LEASES] {} denied leadership in group {}, leader is {} (DENY/LESSEE)",
            consumerId, groupId, leaderId);
    }

    /**
     * Called when leader's heartbeat succeeds (poll() returned successfully).
     * <p>
     * Triggered by: Successful consumer.poll() when isLeader() = true
     * <p>
     * In Kafka, the leader sends heartbeats via poll() to maintain the session.
     * Each successful poll response from the coordinator implicitly renews the lease.
     */
    public void onHeartbeatSuccess() {
        // LESSEE perspective: Leader sent heartbeat (implicit in poll)
        groupLease.renew(Leases.Dimension.LESSEE);

        // LESSOR perspective: Coordinator extended lease TTL
        groupLease.extend(Leases.Dimension.LESSOR);

        logger.debug("[LEASES] {} leadership lease renewed for group {} (RENEW/LESSEE → EXTEND/LESSOR)",
            consumerId, groupId);
    }

    /**
     * Called when consumer voluntarily leaves the group (graceful shutdown).
     * <p>
     * Triggered by: consumer.close() or application shutdown
     */
    public void onConsumerLeaving() {
        // LESSEE perspective: Voluntarily releasing leadership lease
        groupLease.release(Leases.Dimension.LESSEE);

        logger.info("[LEASES] {} voluntarily releasing leadership for group {} (RELEASE/LESSEE)",
            consumerId, groupId);
    }

    /**
     * Called when consumer session expires (heartbeat timeout).
     * <p>
     * Triggered by: Kafka coordinator detects session.timeout.ms exceeded
     * <p>
     * This is called externally when your monitoring detects session expiration.
     */
    public void onSessionExpired() {
        // LESSOR perspective: Coordinator expired lease due to TTL exhaustion
        groupLease.expire(Leases.Dimension.LESSOR);

        logger.warn("[LEASES] {} session expired for group {}, lease terminated (EXPIRE/LESSOR)",
            consumerId, groupId);
    }

    /**
     * Called when coordinator forcefully revokes leadership (forced rebalance, admin action).
     * <p>
     * Triggered by: Administrative rebalance, cluster controller decision, split-brain detection
     */
    public void onLeadershipRevoked() {
        // LESSOR perspective: Coordinator forcefully revoked lease
        groupLease.revoke(Leases.Dimension.LESSOR);

        logger.warn("[LEASES] {} leadership forcefully revoked for group {} (REVOKE/LESSOR)",
            consumerId, groupId);
    }

    /**
     * Gets the leases conduit for subscribing to lease signals.
     *
     * @return Leases conduit
     */
    public Conduit<Lease, Leases.Signal> leases() {
        return leases;
    }

    @Override
    public void close() {
        logger.info("[LEASES] Shutting down ConsumerGroupLeaseObserver for {} in group {}",
            consumerId, groupId);

        // No scheduler to shutdown - pure reactive observer

        logger.info("[LEASES] ConsumerGroupLeaseObserver stopped for {}", consumerId);
    }
}
