package io.fullerstack.kafka.broker.monitors;

import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Routers;
import io.humainary.substrates.ext.serventis.ext.Routers.Router;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Monitors.Monitor;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Gauges.Gauge;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.PartitionReassignment;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Monitors partition reassignment operations using AdminClient and RC6 Routers API.
 *
 * <p><b>Layer 2: Serventis Signal Emission</b>
 * This monitor emits signals with Routers API vocabulary (FRAGMENT/SEND/RECEIVE/REASSEMBLE/DROP)
 * to track partition reassignment lifecycle.
 *
 * <h3>RC6 Routers API - Partition Reassignment Lifecycle</h3>
 * Models Kafka partition reassignment as a routing topology change:
 * <ul>
 *   <li><b>FRAGMENT</b> - Reassignment started, traffic splitting between old/new leaders</li>
 *   <li><b>SEND</b> - Old leader sending partition data to new replicas</li>
 *   <li><b>RECEIVE</b> - New replicas receiving partition data</li>
 *   <li><b>REASSEMBLE</b> - Reassignment complete, traffic consolidated on new leader</li>
 *   <li><b>DROP</b> - Old replicas removed from topology</li>
 * </ul>
 *
 * <h3>Metrics Tracked</h3>
 * <ol>
 *   <li><b>Reassignment In Progress</b> - AdminClient.listPartitionReassignments() + Monitors (DEGRADED)</li>
 *   <li><b>Reassignment Bytes Moved</b> - JMX ReassignmentMetrics.BytesMovedPerSec + Counters (INCREMENT)</li>
 *   <li><b>Reassignment Completion %</b> - AdminClient (replicas vs target) + Gauges (0-100%)</li>
 * </ol>
 *
 * <h3>Signal Flow Example</h3>
 * <pre>
 * 1. Reassignment Detected (AdminClient):
 *    - oldLeader.fragment()       // Traffic splitting starts
 *    - monitor.degraded()         // System under reassignment
 *
 * 2. Data Transfer (JMX):
 *    - oldLeader.send()           // Send partition data
 *    - newLeader.receive()        // Receive partition data
 *    - bytesCounter.increment()   // Track bytes moved
 *    - progressGauge.increment()  // Track completion %
 *
 * 3. Reassignment Complete (AdminClient):
 *    - newLeader.reassemble()     // Traffic consolidated
 *    - oldLeader.drop()           // Old replicas removed
 *    - monitor.stable()           // System recovered
 * </pre>
 *
 * <p><b>Router Naming Convention</b>:
 * <ul>
 *   <li>Old leader router: {@code partition-{topic}-{partition}-old}</li>
 *   <li>New leader router: {@code partition-{topic}-{partition}-new}</li>
 * </ul>
 *
 * <p><b>Polling Strategy</b>: Uses ScheduledExecutorService for periodic AdminClient queries
 * (10-second intervals by default).
 *
 * <p><b>Thread Safety</b>: All signal emissions synchronized via Circuit's Valve pattern.
 *
 * @see PartitionReassignment
 * @see Routers
 */
public class PartitionReassignmentMonitor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PartitionReassignmentMonitor.class);
    private static final long DEFAULT_POLL_INTERVAL_SECONDS = 10;
    private static final long ADMIN_CLIENT_TIMEOUT_SECONDS = 10;

    private final AdminClient adminClient;
    private final MBeanServerConnection mbsc;
    private final Circuit circuit;
    private final ScheduledExecutorService scheduler;

    // Conduits
    private final Conduit<Router, Routers.Sign> routers;
    private final Conduit<Monitor, Monitors.Signal> monitors;
    private final Conduit<Counter, Counters.Sign> counters;
    private final Conduit<Gauge, Gauges.Sign> gauges;

    // Tracking state
    private final Map<TopicPartition, ReassignmentState> activeReassignments;
    private final Map<TopicPartition, Router> oldLeaders;
    private final Map<TopicPartition, Router> newLeaders;

    /**
     * Creates a PartitionReassignmentMonitor.
     *
     * @param adminClient AdminClient for querying reassignment state
     * @param mbsc        MBean server connection for JMX metrics
     * @param circuit     Circuit for signal emission
     * @throws NullPointerException if any parameter is null
     */
    public PartitionReassignmentMonitor(
        AdminClient adminClient,
        MBeanServerConnection mbsc,
        Circuit circuit
    ) {
        this.adminClient = Objects.requireNonNull(adminClient, "adminClient cannot be null");
        this.mbsc = Objects.requireNonNull(mbsc, "mbsc cannot be null");
        this.circuit = Objects.requireNonNull(circuit, "circuit cannot be null");

        // Create conduits with RC7 pattern
        this.routers = circuit.conduit(cortex().name("routers"), Routers::composer);
        this.monitors = circuit.conduit(cortex().name("monitors"), Monitors::composer);
        this.counters = circuit.conduit(cortex().name("counters"), Counters::composer);
        this.gauges = circuit.conduit(cortex().name("gauges"), Gauges::composer);

        this.activeReassignments = new ConcurrentHashMap<>();
        this.oldLeaders = new ConcurrentHashMap<>();
        this.newLeaders = new ConcurrentHashMap<>();

        // Schedule polling
        this.scheduler = Executors.newScheduledThreadPool(1);

        logger.info("PartitionReassignmentMonitor created");
    }

    /**
     * Starts monitoring reassignments with default poll interval.
     */
    public void start() {
        start(DEFAULT_POLL_INTERVAL_SECONDS);
    }

    /**
     * Starts monitoring reassignments with custom poll interval.
     *
     * @param pollIntervalSeconds Seconds between AdminClient polls
     */
    public void start(long pollIntervalSeconds) {
        scheduler.scheduleAtFixedRate(
            this::pollReassignments,
            0,
            pollIntervalSeconds,
            TimeUnit.SECONDS
        );
        logger.info("PartitionReassignmentMonitor started (poll interval: {}s)", pollIntervalSeconds);
    }

    /**
     * Polls AdminClient for active reassignments and emits Router signals.
     * <p>
     * Called periodically by scheduler. Detects:
     * <ul>
     *   <li>New reassignments (FRAGMENT signal)</li>
     *   <li>Ongoing reassignments (SEND/RECEIVE signals)</li>
     *   <li>Completed reassignments (REASSEMBLE/DROP signals)</li>
     * </ul>
     */
    void pollReassignments() {
        try {
            // Query AdminClient for active reassignments
            Map<TopicPartition, PartitionReassignment> reassignments =
                adminClient.listPartitionReassignments()
                    .reassignments()
                    .get(ADMIN_CLIENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Detect new reassignments
            for (Map.Entry<TopicPartition, PartitionReassignment> entry : reassignments.entrySet()) {
                TopicPartition tp = entry.getKey();
                PartitionReassignment r = entry.getValue();

                if (!activeReassignments.containsKey(tp)) {
                    // NEW reassignment - emit FRAGMENT
                    startReassignment(tp, r);
                } else {
                    // ONGOING reassignment - track progress
                    updateReassignment(tp, r);
                }
            }

            // Detect completed reassignments
            Set<TopicPartition> completedPartitions = new ConcurrentHashMap<TopicPartition, ReassignmentState>(activeReassignments).keySet();
            for (TopicPartition tp : completedPartitions) {
                if (!reassignments.containsKey(tp)) {
                    // Reassignment completed - emit REASSEMBLE/DROP
                    completeReassignment(tp);
                }
            }

        } catch (TimeoutException e) {
            logger.error("AdminClient query timed out", e);
        } catch (java.lang.Exception e) {
            logger.error("Reassignment monitoring failed", e);
        }
    }

    /**
     * Handles new reassignment detection.
     * <p>
     * Emits:
     * <ul>
     *   <li>Router FRAGMENT signal (traffic splitting)</li>
     *   <li>Monitor DEGRADED signal (system under reassignment)</li>
     * </ul>
     *
     * @param tp TopicPartition being reassigned
     * @param r PartitionReassignment details
     */
    private void startReassignment(TopicPartition tp, PartitionReassignment r) {
        logger.info("Reassignment started: {} (adding: {}, removing: {})",
            tp, r.addingReplicas(), r.removingReplicas());

        // Create routers for old and new leaders
        Router oldLeader = routers.percept(cortex().name("partition-" + tp + "-old"));
        Router newLeader = routers.percept(cortex().name("partition-" + tp + "-new"));

        oldLeaders.put(tp, oldLeader);
        newLeaders.put(tp, newLeader);

        // Emit FRAGMENT signal (traffic splitting)
        oldLeader.fragment();
        logger.debug("Emitted FRAGMENT signal for {}", tp);

        // Set monitor to DEGRADED
        Monitor monitor = monitors.percept(cortex().name("reassignment-" + tp));
        monitor.degraded(Monitors.Dimension.CONFIRMED);
        logger.debug("Emitted DEGRADED monitor signal for {}", tp);

        // Track state
        activeReassignments.put(tp, new ReassignmentState(
            r.replicas(),
            r.addingReplicas(),
            r.removingReplicas(),
            System.currentTimeMillis(),
            0L  // Initial bytes moved
        ));

        logger.debug("Emitted FRAGMENT and DEGRADED signals for {}", tp);
    }

    /**
     * Handles ongoing reassignment updates.
     * <p>
     * Emits:
     * <ul>
     *   <li>Router SEND signal (old leader sending data)</li>
     *   <li>Router RECEIVE signal (new replicas receiving data)</li>
     *   <li>Counter INCREMENT signal (bytes moved)</li>
     *   <li>Gauge signal (completion progress)</li>
     * </ul>
     *
     * @param tp TopicPartition being reassigned
     * @param r PartitionReassignment details
     */
    private void updateReassignment(TopicPartition tp, PartitionReassignment r) {
        ReassignmentState state = activeReassignments.get(tp);
        if (state == null) {
            return;
        }

        // Query JMX for bytes moved
        long bytesMoved = getBytesMoved();
        long deltaBytes = bytesMoved - state.bytesMoved();

        if (deltaBytes > 0) {
            // Emit SEND/RECEIVE signals
            Router oldLeader = oldLeaders.get(tp);
            Router newLeader = newLeaders.get(tp);

            oldLeader.send();
            newLeader.receive();

            // Update bytes counter
            Counter bytesCounter = counters.percept(cortex().name("reassignment-bytes-" + tp));
            bytesCounter.increment();

            // Update state
            activeReassignments.put(tp, new ReassignmentState(
                state.targetReplicas(),
                state.addingReplicas(),
                state.removingReplicas(),
                state.startTime(),
                bytesMoved
            ));

            logger.debug("Reassignment progress: {} - {} bytes moved", tp, deltaBytes);
        }

        // Calculate completion %
        double completion = calculateCompletion(r);
        Gauge progressGauge = gauges.percept(cortex().name("reassignment-progress-" + tp));

        if (completion >= 90) {
            progressGauge.overflow();  // Almost done
        } else {
            progressGauge.increment();
        }
    }

    /**
     * Handles reassignment completion.
     * <p>
     * Emits:
     * <ul>
     *   <li>Router REASSEMBLE signal (traffic consolidated)</li>
     *   <li>Router DROP signal (old replicas removed)</li>
     *   <li>Monitor STABLE signal (system recovered)</li>
     * </ul>
     *
     * @param tp TopicPartition that completed reassignment
     */
    private void completeReassignment(TopicPartition tp) {
        logger.info("Reassignment completed: {}", tp);

        Router oldLeader = oldLeaders.get(tp);
        Router newLeader = newLeaders.get(tp);
        ReassignmentState state = activeReassignments.get(tp);

        if (oldLeader == null || newLeader == null || state == null) {
            logger.warn("Cannot complete reassignment for {}: missing state", tp);
            return;
        }

        // Emit REASSEMBLE signal (traffic consolidated)
        newLeader.reassemble();

        // Emit DROP signal (old replicas removed)
        oldLeader.drop();

        // Set monitor to STABLE
        Monitor monitor = monitors.percept(cortex().name("reassignment-" + tp));
        monitor.stable(Monitors.Dimension.CONFIRMED);

        // Cleanup
        activeReassignments.remove(tp);
        oldLeaders.remove(tp);
        newLeaders.remove(tp);

        long duration = System.currentTimeMillis() - state.startTime();
        logger.info("Reassignment {} completed in {}ms", tp, duration);
    }

    /**
     * Queries JMX for total bytes moved during reassignments.
     *
     * @return Bytes moved (cumulative count)
     */
    private long getBytesMoved() {
        try {
            ObjectName name = new ObjectName(
                "kafka.server:type=ReassignmentMetrics,name=BytesMovedPerSec"
            );
            Object value = mbsc.getAttribute(name, "Count");
            return value != null ? ((Number) value).longValue() : 0L;
        } catch (java.lang.Exception e) {
            logger.debug("Failed to get bytes moved: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * Calculates reassignment completion percentage.
     * <p>
     * Simplified calculation based on adding replicas count.
     * Real implementation would query partition metadata.
     *
     * @param r PartitionReassignment details
     * @return Completion percentage (0-100)
     */
    private double calculateCompletion(PartitionReassignment r) {
        int currentReplicas = r.replicas().size();
        int addingReplicas = r.addingReplicas().size();
        int targetReplicas = currentReplicas + addingReplicas;

        if (targetReplicas == 0) {
            return 100.0;
        }

        // Simplified: assume linear progress based on adding replicas
        return (double) currentReplicas / targetReplicas * 100.0;
    }

    /**
     * Returns active reassignments (for testing).
     *
     * @return Map of active reassignments
     */
    Map<TopicPartition, ReassignmentState> getActiveReassignments() {
        return activeReassignments;
    }

    @Override
    public void close() {
        logger.info("Shutting down PartitionReassignmentMonitor");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Tracks state of an active reassignment.
     *
     * @param targetReplicas  Final replica set
     * @param addingReplicas  Replicas being added
     * @param removingReplicas Replicas being removed
     * @param startTime       Reassignment start timestamp
     * @param bytesMoved      Cumulative bytes moved
     */
    record ReassignmentState(
        java.util.List<Integer> targetReplicas,
        java.util.List<Integer> addingReplicas,
        java.util.List<Integer> removingReplicas,
        long startTime,
        long bytesMoved
    ) {}
}
