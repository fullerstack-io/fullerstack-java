package io.fullerstack.kafka.consumer.leases;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Leases;
import io.humainary.substrates.ext.serventis.ext.Leases.Lease;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Observes partition assignment ownership as time-bounded leases.
 * <p>
 * This observer tracks partition ownership leases within a Kafka consumer group using the
 * Leases API (Serventis PREVIEW). Each partition assignment is modeled as a lease where:
 * <ul>
 *   <li>The consumer is the LESSEE (lease holder)</li>
 *   <li>The group coordinator is the LESSOR (lease authority)</li>
 * </ul>
 *
 * <h3>Partition Lease Lifecycle:</h3>
 * <pre>
 * Assignment:  Consumer → ACQUIRE (lessee) [wants partition]
 *              Coordinator → GRANT (lessor) [assigns partition]
 *                     ↓
 * Heartbeat:   Consumer → RENEW (lessee) [via poll()]
 *              Coordinator → EXTEND (lessor) [heartbeat response]
 *                     ↓
 * Termination:
 *   Rebalance:   Coordinator → REVOKE (lessor) [forced reassignment]
 *   Shutdown:    Consumer → RELEASE (lessee) [voluntary]
 *   Timeout:     Coordinator → EXPIRE (lessor) [session timeout]
 * </pre>
 *
 * <h3>Dual-Dimension Model:</h3>
 * <pre>
 * LESSOR (Coordinator):  GRANT, DENY, EXTEND, EXPIRE, REVOKE
 * LESSEE (Consumer):     ACQUIRE, RENEW, RELEASE
 * </pre>
 *
 * <h3>Integration with Kafka Consumer API:</h3>
 * <pre>{@code
 * // In your ConsumerRebalanceListener:
 * @Override
 * public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
 *     leaseObserver.onPartitionsRevoked(partitions);
 * }
 *
 * @Override
 * public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
 *     leaseObserver.onPartitionsAssigned(partitions);
 * }
 *
 * // In your poll loop:
 * ConsumerRecords<K, V> records = consumer.poll(Duration.ofMillis(100));
 * leaseObserver.onHeartbeatSuccess();  // Poll succeeded = all partition leases renewed
 *
 * // On shutdown:
 * leaseObserver.onConsumerClosing();
 * leaseObserver.close();
 * }</pre>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("consumer.monitoring"));
 *
 * PartitionAssignmentLeaseObserver observer = new PartitionAssignmentLeaseObserver(
 *     circuit,
 *     "consumer-group-1",
 *     "consumer-1"
 * );
 *
 * // Leases signals now emitted for each partition assignment
 * Conduit<Lease, Leases.Signal> leases = observer.leases();
 * leases.subscribe(...);
 *
 * // Later...
 * observer.close();
 * }</pre>
 *
 * @author Fullerstack
 * @see Leases
 * @see ConsumerGroupLeaseObserver
 */
public class PartitionAssignmentLeaseObserver implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PartitionAssignmentLeaseObserver.class);

    private final Cortex cortex;
    private final Circuit circuit;
    private final String groupId;
    private final String consumerId;
    private final Conduit<Lease, Leases.Signal> leases;

    // Track currently assigned partitions (for RENEW signals)
    private final Set<TopicPartition> assignedPartitions = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new partition assignment lease observer.
     *
     * @param circuit     Circuit for creating leases conduit
     * @param groupId     Consumer group identifier (e.g., "consumer-group-1")
     * @param consumerId  Consumer identifier (e.g., "consumer-1")
     */
    public PartitionAssignmentLeaseObserver(
        Circuit circuit,
        String groupId,
        String consumerId
    ) {
        this.cortex = cortex();
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");
        this.groupId = Objects.requireNonNull(groupId, "groupId cannot be null");
        this.consumerId = Objects.requireNonNull(consumerId, "consumerId cannot be null");

        // Create Leases conduit (shared with ConsumerGroupLeaseObserver if in same circuit)
        this.leases = circuit.conduit(
            cortex.name("leases"),
            Leases::composer
        );

        logger.info("[LEASES] PartitionAssignmentLeaseObserver created for {} in group {} (pure reactive)",
            consumerId, groupId);
    }

    /**
     * Called when partitions are revoked during rebalance.
     * <p>
     * Triggered by: ConsumerRebalanceListener.onPartitionsRevoked()
     *
     * @param partitions Partitions being revoked
     */
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            Lease partitionLease = getPartitionLease(partition);

            // LESSOR perspective: Coordinator revoked partition lease (forced reassignment)
            partitionLease.revoke(Leases.Dimension.LESSOR);

            logger.info("[LEASES] {} partition {} revoked by coordinator in group {} (REVOKE/LESSOR)",
                consumerId, partition, groupId);
        }

        // Remove from assigned set
        assignedPartitions.removeAll(partitions);
    }

    /**
     * Called when partitions are assigned during rebalance.
     * <p>
     * Triggered by: ConsumerRebalanceListener.onPartitionsAssigned()
     *
     * @param partitions Partitions being assigned
     */
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            Lease partitionLease = getPartitionLease(partition);

            // LESSEE perspective: Consumer requested partition ownership
            partitionLease.acquire(Leases.Dimension.LESSEE);

            // LESSOR perspective: Coordinator granted partition lease
            partitionLease.grant(Leases.Dimension.LESSOR);

            logger.info("[LEASES] {} acquired partition {} in group {} (ACQUIRE/LESSEE → GRANT/LESSOR)",
                consumerId, partition, groupId);
        }

        // Add to assigned set for future RENEW signals
        assignedPartitions.addAll(partitions);
    }

    /**
     * Called when a partition assignment is denied (rare - for completeness).
     * <p>
     * This would occur if the consumer requested a partition but coordinator denied it
     * (e.g., assigned to another consumer). In normal Kafka operation, this doesn't happen
     * because coordinator decides assignments. Included for API completeness.
     *
     * @param partition Partition that was denied
     */
    public void onPartitionDenied(TopicPartition partition) {
        Lease partitionLease = getPartitionLease(partition);

        // LESSEE perspective: Consumer was denied partition
        partitionLease.deny(Leases.Dimension.LESSEE);

        logger.warn("[LEASES] {} denied partition {} in group {} (DENY/LESSEE)",
            consumerId, partition, groupId);
    }

    /**
     * Called when heartbeat succeeds (poll() returned successfully).
     * <p>
     * Triggered by: Successful consumer.poll()
     * <p>
     * In Kafka, each successful poll implicitly renews the session, which maintains
     * ownership of all assigned partitions. This emits RENEW/EXTEND signals for all
     * currently assigned partitions.
     */
    public void onHeartbeatSuccess() {
        if (assignedPartitions.isEmpty()) {
            return;  // No partitions assigned, nothing to renew
        }

        for (TopicPartition partition : assignedPartitions) {
            Lease partitionLease = getPartitionLease(partition);

            // LESSEE perspective: Consumer sent heartbeat (implicit in poll)
            partitionLease.renew(Leases.Dimension.LESSEE);

            // LESSOR perspective: Coordinator extended lease TTL
            partitionLease.extend(Leases.Dimension.LESSOR);
        }

        logger.debug("[LEASES] {} renewed partition leases ({} partitions) in group {} (RENEW/LESSEE → EXTEND/LESSOR)",
            consumerId, assignedPartitions.size(), groupId);
    }

    /**
     * Called when consumer is closing gracefully (voluntary release).
     * <p>
     * Triggered by: consumer.close()
     */
    public void onConsumerClosing() {
        for (TopicPartition partition : assignedPartitions) {
            Lease partitionLease = getPartitionLease(partition);

            // LESSEE perspective: Consumer voluntarily releasing partition lease
            partitionLease.release(Leases.Dimension.LESSEE);

            logger.info("[LEASES] {} voluntarily releasing partition {} in group {} (RELEASE/LESSEE)",
                consumerId, partition, groupId);
        }

        assignedPartitions.clear();
    }

    /**
     * Called when consumer session expires (heartbeat timeout).
     * <p>
     * Triggered by: Kafka coordinator detects session.timeout.ms exceeded
     * <p>
     * This is called externally when your monitoring detects session expiration.
     * All partition assignments are automatically expired by the coordinator.
     */
    public void onSessionExpired() {
        for (TopicPartition partition : assignedPartitions) {
            Lease partitionLease = getPartitionLease(partition);

            // LESSOR perspective: Coordinator expired partition lease due to TTL exhaustion
            partitionLease.expire(Leases.Dimension.LESSOR);

            logger.warn("[LEASES] {} partition {} lease expired in group {} (EXPIRE/LESSOR)",
                consumerId, partition, groupId);
        }

        assignedPartitions.clear();
    }

    /**
     * Gets the Lease for a specific partition assignment.
     * <p>
     * Creates a unique lease name per partition: "consumer-1.topic-0.partition-5.assignment"
     *
     * @param partition Topic partition
     * @return Lease for this partition assignment
     */
    private Lease getPartitionLease(TopicPartition partition) {
        String leaseName = String.format(
            "%s.%s.partition-%d.assignment",
            consumerId,
            partition.topic(),
            partition.partition()
        );

        return leases.percept(cortex.name(leaseName));
    }

    /**
     * Gets the leases conduit for subscribing to partition assignment lease signals.
     *
     * @return Leases conduit
     */
    public Conduit<Lease, Leases.Signal> leases() {
        return leases;
    }

    /**
     * Gets the currently assigned partitions (for testing/monitoring).
     *
     * @return Set of assigned partitions
     */
    public Set<TopicPartition> getAssignedPartitions() {
        return Set.copyOf(assignedPartitions);
    }

    @Override
    public void close() {
        logger.info("[LEASES] Shutting down PartitionAssignmentLeaseObserver for {} in group {}",
            consumerId, groupId);

        // Release any remaining partitions
        if (!assignedPartitions.isEmpty()) {
            logger.warn("[LEASES] {} still has {} assigned partitions during shutdown, releasing...",
                consumerId, assignedPartitions.size());
            onConsumerClosing();
        }

        logger.info("[LEASES] PartitionAssignmentLeaseObserver stopped for {}", consumerId);
    }
}
