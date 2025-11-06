package io.fullerstack.kafka.broker.circuits;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Routers;
import io.humainary.substrates.ext.serventis.ext.Routers.Router;
import io.humainary.substrates.ext.serventis.ext.Routers.Sign;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Reference implementation of Routers API (RC6) pattern for ISR replication monitoring.
 * <p>
 * This circuit demonstrates the complete Router lifecycle and serves as the canonical
 * pattern for all Router-based monitoring in fullerstack-kafka.
 * <p>
 * <b>Analogous to QueueFlowCircuit</b> - demonstrates Routers API similar to how
 * QueueFlowCircuit demonstrates Queues API.
 * <p>
 * <h3>Routers API - Packet Routing Observability</h3>
 * Models network packet routing operations with 9 routing signs:
 * <ul>
 *   <li><b>SEND</b> - Packet originated by this router (source node)</li>
 *   <li><b>RECEIVE</b> - Packet arrived from network (entry point)</li>
 *   <li><b>FORWARD</b> - Packet routed to next hop (intermediary function)</li>
 *   <li><b>ROUTE</b> - Routing decision/table lookup performed</li>
 *   <li><b>DROP</b> - Packet discarded (congestion, policy, TTL, etc.)</li>
 *   <li><b>FRAGMENT</b> - Packet split due to MTU constraints</li>
 *   <li><b>REASSEMBLE</b> - Fragments recombined into original packet</li>
 *   <li><b>CORRUPT</b> - Corruption detected (checksum/malformed header)</li>
 *   <li><b>REORDER</b> - Out-of-order arrival detected</li>
 * </ul>
 * <p>
 * <h3>Kafka ISR Mapping</h3>
 * <ul>
 *   <li><b>SEND</b> - Leader sending replication data to follower</li>
 *   <li><b>RECEIVE</b> - Follower successfully receiving and in-sync</li>
 *   <li><b>DROP</b> - Follower dropped from ISR (lag > threshold or offline)</li>
 *   <li><b>FORWARD</b> - Partition reassignment (data forwarded to new broker)</li>
 *   <li><b>FRAGMENT</b> - Reassignment traffic splitting (old + new leaders)</li>
 *   <li><b>REASSEMBLE</b> - Reassignment completion (consolidated on new leader)</li>
 * </ul>
 * <p>
 * <h3>Usage Pattern</h3>
 * <pre>{@code
 * // 1. Create circuit with Routers conduit
 * RouterFlowCircuit circuit = new RouterFlowCircuit("isr-replication");
 *
 * // 2. Get Router for leader → follower link
 * Router leaderToFollower = circuit.getRouter("broker-1.partition-0.follower-2");
 *
 * // 3. Emit routing signals based on JMX events
 * if (isrShrinkDetected) {
 *     leaderToFollower.drop();  // Follower fell behind, dropped from ISR
 * } else if (replicationSuccess) {
 *     leaderToFollower.send();     // Leader sends
 *     leaderToFollower.receive();  // Follower receives
 * }
 *
 * circuit.await();  // Wait for signal processing
 * }</pre>
 * <p>
 * <b>RC7 Pattern</b>: Uses static cortex() access (Substrates M18)
 * <p>
 * <b>Thread Safety</b>: All operations synchronized via Circuit's Valve (virtual thread)
 *
 * @see io.humainary.substrates.ext.serventis.ext.Routers
 * @see IsrReplicationObserver
 */
public class RouterFlowCircuit implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RouterFlowCircuit.class);

    private final Circuit circuit;
    private final Conduit<Router, Sign> routers;
    private final List<Subscriber<Sign>> subscribers;

    /**
     * Creates a RouterFlowCircuit with specified name.
     *
     * @param circuitName Circuit name (e.g., "isr-replication", "partition-reassignment")
     * @throws NullPointerException if circuitName is null
     */
    public RouterFlowCircuit(String circuitName) {
        Objects.requireNonNull(circuitName, "circuitName cannot be null");

        // RC7: Static cortex() access
        this.circuit = cortex().circuit(cortex().name(circuitName));

        // Create Conduit with Routers::composer method reference
        this.routers = circuit.conduit(
            cortex().name("routers"),
            Routers::composer
        );

        this.subscribers = new ArrayList<>();

        logger.debug("RouterFlowCircuit created: {}", circuitName);
    }

    /**
     * Gets or creates a Router instrument for the specified entity.
     * <p>
     * Router names should follow convention:
     * <ul>
     *   <li>ISR: "broker-{id}.partition-{topic}-{partition}.follower-{id}"</li>
     *   <li>Reassignment: "broker-{id}.partition-{topic}-{partition}"</li>
     * </ul>
     *
     * @param routerName Entity name (e.g., "broker-1.partition-test-0.follower-2")
     * @return Router instrument
     * @throws NullPointerException if routerName is null
     */
    public Router getRouter(String routerName) {
        Objects.requireNonNull(routerName, "routerName cannot be null");
        return routers.get(cortex().name(routerName));
    }

    /**
     * Subscribes to all Router signals.
     * <p>
     * Subscriber receives signals with Subject context (entity identity).
     *
     * @param subscriberName Name for subscriber (for tracking/debugging)
     * @param handler Signal handler that receives Subject and Sign
     * @return Subscriber instance
     */
    public Subscriber<Sign> subscribe(
        String subscriberName,
        SignalHandler handler
    ) {
        Objects.requireNonNull(subscriberName, "subscriberName cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");

        Subscriber<Sign> subscriber = cortex().subscriber(
            cortex().name(subscriberName),
            (Subject<Channel<Sign>> subject, Registrar<Sign> registrar) -> {
                registrar.register(sign -> {
                    handler.handle(subject, sign);
                });
            }
        );

        routers.subscribe(subscriber);
        subscribers.add(subscriber);

        logger.debug("Subscriber added: {}", subscriberName);
        return subscriber;
    }

    /**
     * Awaits completion of all pending signal processing.
     * <p>
     * Uses event-driven synchronization (wait/notify), NOT polling.
     * This is the Valve pattern from Substrates M18.
     */
    public void await() {
        circuit.await();
    }

    /**
     * Demonstrates basic ISR replication pattern.
     * <p>
     * Pattern: Leader SEND → Follower RECEIVE (healthy replication)
     */
    public void demonstrateHealthyReplication() {
        Router leader = getRouter("partition-0.leader");
        Router follower = getRouter("partition-0.follower-1");

        // Leader sends replication data
        leader.send();

        // Follower receives successfully (in-sync)
        follower.receive();

        await();

        logger.info("Demonstrated: Healthy replication (SEND → RECEIVE)");
    }

    /**
     * Demonstrates ISR shrink pattern.
     * <p>
     * Pattern: Leader DROP (follower fell behind and removed from ISR)
     */
    public void demonstrateIsrShrink() {
        Router leader = getRouter("partition-0.leader");

        // Leader drops follower from ISR topology
        leader.drop();

        await();

        logger.info("Demonstrated: ISR shrink (DROP)");
    }

    /**
     * Demonstrates ISR expand pattern.
     * <p>
     * Pattern: Follower RECEIVE (caught up and added back to ISR)
     */
    public void demonstrateIsrExpand() {
        Router follower = getRouter("partition-0.follower-1");

        // Follower caught up and back in ISR
        follower.receive();

        await();

        logger.info("Demonstrated: ISR expand (RECEIVE)");
    }

    /**
     * Demonstrates partition reassignment pattern.
     * <p>
     * Pattern: Old leader FRAGMENT (splitting traffic) →
     *          New leader RECEIVE →
     *          Old leader DROP →
     *          New leader REASSEMBLE (consolidation)
     */
    public void demonstratePartitionReassignment() {
        Router oldLeader = getRouter("broker-1.partition-5");
        Router newLeader = getRouter("broker-2.partition-5");

        // Phase 1: Traffic splitting (reassignment in progress)
        oldLeader.fragment();  // Splitting between old and new

        // Phase 2: New leader receiving partition data
        oldLeader.send();      // Send partition data
        oldLeader.forward();   // Forward to new broker
        newLeader.receive();   // Receive partition data

        // Phase 3: Reassignment complete
        oldLeader.drop();      // Old leader no longer serving partition
        newLeader.reassemble(); // New leader consolidated

        await();

        logger.info("Demonstrated: Partition reassignment (FRAGMENT → DROP → REASSEMBLE)");
    }

    /**
     * Demonstrates all 9 routing signs.
     */
    public void demonstrateAllSigns() {
        Router router = getRouter("demo-router");

        router.send();        // 1. Packet originated
        router.receive();     // 2. Packet arrived
        router.route();       // 3. Routing decision made
        router.forward();     // 4. Packet forwarded
        router.drop();        // 5. Packet dropped
        router.fragment();    // 6. Packet fragmented
        router.reassemble();  // 7. Fragments reassembled
        router.corrupt();     // 8. Corruption detected
        router.reorder();     // 9. Out-of-order detected

        await();

        logger.info("Demonstrated: All 9 routing signs");
    }

    @Override
    public void close() {
        try {
            circuit.close();
            logger.debug("RouterFlowCircuit closed");
        } catch (Exception e) {
            logger.error("Error closing RouterFlowCircuit: {}", e.getMessage(), e);
        }
    }

    /**
     * Functional interface for signal handling.
     */
    @FunctionalInterface
    public interface SignalHandler {
        /**
         * Handles a routing signal.
         *
         * @param subject Subject containing entity context (Channel identity)
         * @param sign Routing sign (SEND, RECEIVE, DROP, etc.)
         */
        void handle(Subject<Channel<Sign>> subject, Sign sign);
    }
}
