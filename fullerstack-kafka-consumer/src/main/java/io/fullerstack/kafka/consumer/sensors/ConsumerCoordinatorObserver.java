package io.fullerstack.kafka.consumer.sensors;

import io.humainary.substrates.api.Substrates.Circuit;
import io.humainary.substrates.api.Substrates.Conduit;
import io.humainary.substrates.ext.serventis.Counters;
import io.humainary.substrates.ext.serventis.Counters.Counter;
import io.humainary.substrates.ext.serventis.Gauges;
import io.humainary.substrates.ext.serventis.Gauges.Gauge;
import io.humainary.substrates.ext.serventis.Monitors;
import io.humainary.substrates.ext.serventis.Monitors.Monitor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Monitors Kafka consumer group coordination metrics and emits signals using RC7 Serventis API.
 * <p>
 * Collects AdminClient and JMX metrics for consumer coordinator health:
 * <ul>
 *   <li><b>Coordinator Sync Rate</b> (Counter): INCREMENT on each rebalance sync</li>
 *   <li><b>Heartbeat Response Time</b> (Gauge): INCREMENT/DECREMENT/OVERFLOW based on latency</li>
 *   <li><b>Session Timeout Violations</b> (Monitor): DEGRADED (approaching), DEFECTIVE (violated)</li>
 *   <li><b>Poll Interval Violations</b> (Monitor): DEGRADED (approaching), DEFECTIVE (violated)</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * Circuit circuit = cortex().circuit(cortex().name("consumer-coordination"));
 *
 * Properties adminProps = new Properties();
 * adminProps.put("bootstrap.servers", "localhost:9092");
 * AdminClient adminClient = AdminClient.create(adminProps);
 *
 * MBeanServerConnection mbsc = ...; // JMX connection
 *
 * ConsumerCoordinatorObserver observer = new ConsumerCoordinatorObserver(
 *     adminClient,
 *     mbsc,
 *     circuit,
 *     Set.of("consumer-group-1", "consumer-group-2")
 * );
 *
 * observer.start();  // Begins monitoring every 10 seconds
 *
 * // Later...
 * observer.stop();
 * }</pre>
 *
 * @author Fullerstack
 * @see Counter
 * @see Gauge
 * @see Monitor
 */
public class ConsumerCoordinatorObserver implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerCoordinatorObserver.class);

    // Thresholds
    private static final double HEARTBEAT_WARNING_MS = 500.0;   // >500ms = warning
    private static final double HEARTBEAT_CRITICAL_MS = 1000.0; // >1000ms = critical (OVERFLOW)
    private static final double SESSION_TIMEOUT_WARNING_RATIO = 0.8;  // 80% of timeout = DEGRADED
    private static final double POLL_INTERVAL_WARNING_RATIO = 0.8;    // 80% of interval = DEGRADED

    private final AdminClient adminClient;
    private final MBeanServerConnection mbsc;
    private final Circuit circuit;
    private final Set<String> consumerGroups;

    // Instruments per consumer group
    private final Map<String, Counter> syncCounters;
    private final Map<String, Gauge> heartbeatGauges;
    private final Map<String, Monitor> sessionMonitors;
    private final Map<String, Monitor> pollMonitors;

    // Previous values for delta calculations
    private final Map<String, ConsumerGroupState> previousStates;
    private final Map<String, Double> previousHeartbeatLatencies;

    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    /**
     * Creates a new consumer coordinator observer.
     *
     * @param adminClient    Kafka AdminClient for coordinator queries
     * @param mbsc          JMX MBean server connection
     * @param circuit       Substrates circuit for signal emission
     * @param consumerGroups Consumer group IDs to monitor
     */
    public ConsumerCoordinatorObserver(
        AdminClient adminClient,
        MBeanServerConnection mbsc,
        Circuit circuit,
        Set<String> consumerGroups
    ) {
        this.adminClient = adminClient;
        this.mbsc = mbsc;
        this.circuit = circuit;
        this.consumerGroups = Set.copyOf(consumerGroups);

        // Create conduits (RC7 pattern)
        Conduit<Counter, ?> counters =
            circuit.conduit(cortex().name("counters"), Counters::composer);
        Conduit<Gauge, ?> gauges =
            circuit.conduit(cortex().name("gauges"), Gauges::composer);
        Conduit<Monitor, Monitors.Signal> monitors =
            circuit.conduit(cortex().name("monitors"), Monitors::composer);

        // Create instruments per consumer group
        this.syncCounters = new ConcurrentHashMap<>();
        this.heartbeatGauges = new ConcurrentHashMap<>();
        this.sessionMonitors = new ConcurrentHashMap<>();
        this.pollMonitors = new ConcurrentHashMap<>();

        for (String group : consumerGroups) {
            syncCounters.put(group, counters.get(cortex().name(group + ".sync")));
            heartbeatGauges.put(group, gauges.get(cortex().name(group + ".heartbeat-latency")));
            sessionMonitors.put(group, monitors.get(cortex().name(group + ".session")));
            pollMonitors.put(group, monitors.get(cortex().name(group + ".poll")));
        }

        // State tracking
        this.previousStates = new ConcurrentHashMap<>();
        this.previousHeartbeatLatencies = new ConcurrentHashMap<>();

        // Scheduler
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "consumer-coordinator-observer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts coordinator monitoring.
     * <p>
     * Schedules coordinator checks every 10 seconds.
     */
    public void start() {
        if (running) {
            logger.warn("Consumer coordinator observer is already running");
            return;
        }

        running = true;

        // Schedule coordinator monitoring every 10 seconds
        scheduler.scheduleAtFixedRate(
            this::pollCoordinatorMetrics,
            0,          // Initial delay
            10,         // Period
            TimeUnit.SECONDS
        );

        logger.info("Started consumer coordinator observer for {} groups", consumerGroups.size());
    }

    /**
     * Stops coordinator monitoring and releases resources.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("Stopped consumer coordinator observer");
    }

    @Override
    public void close() {
        stop();
    }

    // ========================================
    // Coordinator Metrics Collection
    // ========================================

    private void pollCoordinatorMetrics() {
        try {
            // Query AdminClient for coordinator state (batch query)
            Map<String, ConsumerGroupDescription> descriptions =
                adminClient.describeConsumerGroups(consumerGroups)
                    .all()
                    .get(10, TimeUnit.SECONDS);

            for (Map.Entry<String, ConsumerGroupDescription> entry : descriptions.entrySet()) {
                String groupId = entry.getKey();
                ConsumerGroupDescription desc = entry.getValue();

                // Emit coordinator sync signal
                emitSyncSignal(groupId, desc);

                // Query JMX for heartbeat latency
                emitHeartbeatLatencySignal(groupId);

                // Check session timeout
                emitSessionTimeoutSignal(groupId);

                // Check poll interval
                emitPollIntervalSignal(groupId);
            }

            // Await signal processing (RC7 pattern)
            circuit.await();

        } catch (GroupIdNotFoundException e) {
            logger.debug("Consumer group not found (may not exist yet): {}", e.getMessage());
        } catch (GroupAuthorizationException e) {
            logger.error("Not authorized to describe consumer groups", e);
        } catch (TimeoutException e) {
            logger.error("AdminClient query timed out", e);
        } catch (Exception e) {
            logger.error("Coordinator metrics polling failed", e);
        }
    }

    // ========================================
    // Signal Emission Methods
    // ========================================

    /**
     * Emits sync signal when rebalance is detected.
     */
    private void emitSyncSignal(String groupId, ConsumerGroupDescription desc) {
        ConsumerGroupState currentState = desc.state();
        ConsumerGroupState previousState = previousStates.get(groupId);

        // Detect rebalance (transition to PREPARING_REBALANCE or COMPLETING_REBALANCE)
        if (currentState == ConsumerGroupState.PREPARING_REBALANCE ||
            currentState == ConsumerGroupState.COMPLETING_REBALANCE) {

            if (currentState != previousState) {
                syncCounters.get(groupId).increment();
                logger.info("Consumer group {} REBALANCE: {} -> {}",
                    groupId, previousState, currentState);
            }
        }

        previousStates.put(groupId, currentState);
    }

    /**
     * Emits heartbeat latency signal based on JMX metric.
     */
    private void emitHeartbeatLatencySignal(String groupId) {
        try {
            double heartbeatLatency = getHeartbeatLatency(groupId);
            Double previousLatency = previousHeartbeatLatencies.get(groupId);

            Gauge heartbeatGauge = heartbeatGauges.get(groupId);

            // Check for critical latency (OVERFLOW)
            if (heartbeatLatency >= HEARTBEAT_CRITICAL_MS) {
                heartbeatGauge.overflow();
                logger.error("Consumer group {} heartbeat latency CRITICAL: {:.1f} ms (threshold: {} ms)",
                    groupId, heartbeatLatency, HEARTBEAT_CRITICAL_MS);

            } else if (previousLatency != null) {
                // Compare with previous value
                double delta = heartbeatLatency - previousLatency;
                double threshold = 50.0; // 50ms change threshold

                if (delta > threshold) {
                    heartbeatGauge.increment();
                    logger.debug("Consumer group {} heartbeat latency INCREASED: {:.1f}->{:.1f} ms",
                        groupId, previousLatency, heartbeatLatency);

                    if (heartbeatLatency >= HEARTBEAT_WARNING_MS) {
                        logger.warn("Consumer group {} heartbeat latency WARNING: {:.1f} ms (threshold: {} ms)",
                            groupId, heartbeatLatency, HEARTBEAT_WARNING_MS);
                    }

                } else if (delta < -threshold) {
                    heartbeatGauge.decrement();
                    logger.debug("Consumer group {} heartbeat latency DECREASED: {:.1f}->{:.1f} ms",
                        groupId, previousLatency, heartbeatLatency);
                }
            }

            previousHeartbeatLatencies.put(groupId, heartbeatLatency);

        } catch (Exception e) {
            logger.error("Failed to query heartbeat latency for group {}: {}", groupId, e.getMessage());
            // Emit overflow on error (conservative approach)
            heartbeatGauges.get(groupId).overflow();
        }
    }

    /**
     * Emits session timeout signal based on last heartbeat time.
     */
    private void emitSessionTimeoutSignal(String groupId) {
        try {
            long lastHeartbeatMs = getLastHeartbeatMs(groupId);
            long sessionTimeoutMs = getSessionTimeoutMs(groupId);
            long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatMs;

            Monitor sessionMonitor = sessionMonitors.get(groupId);

            if (timeSinceLastHeartbeat > sessionTimeoutMs) {
                // Session timeout violated
                sessionMonitor.defective(Monitors.Confidence.CONFIRMED);
                logger.error("Consumer group {} SESSION TIMEOUT VIOLATION: {}ms > {}ms",
                    groupId, timeSinceLastHeartbeat, sessionTimeoutMs);

            } else if (timeSinceLastHeartbeat > sessionTimeoutMs * SESSION_TIMEOUT_WARNING_RATIO) {
                // Approaching session timeout
                sessionMonitor.degraded(Monitors.Confidence.MEASURED);
                logger.warn("Consumer group {} APPROACHING SESSION TIMEOUT: {}ms ({}% of {}ms)",
                    groupId, timeSinceLastHeartbeat,
                    (int)(SESSION_TIMEOUT_WARNING_RATIO * 100), sessionTimeoutMs);

            } else {
                // Healthy session
                sessionMonitor.stable(Monitors.Confidence.CONFIRMED);
            }

        } catch (Exception e) {
            logger.debug("Could not check session timeout for group {}: {}", groupId, e.getMessage());
            // Don't emit signal if we can't measure
        }
    }

    /**
     * Emits poll interval signal based on last poll time.
     */
    private void emitPollIntervalSignal(String groupId) {
        try {
            double lastPollSecondsAgo = getLastPollSecondsAgo(groupId);
            long maxPollIntervalMs = getMaxPollIntervalMs(groupId);
            long lastPollMs = (long) (lastPollSecondsAgo * 1000);

            Monitor pollMonitor = pollMonitors.get(groupId);

            if (lastPollMs > maxPollIntervalMs) {
                // Poll interval violated
                pollMonitor.defective(Monitors.Confidence.CONFIRMED);
                logger.error("Consumer group {} POLL INTERVAL VIOLATION: {}ms > {}ms",
                    groupId, lastPollMs, maxPollIntervalMs);

            } else if (lastPollMs > maxPollIntervalMs * POLL_INTERVAL_WARNING_RATIO) {
                // Approaching poll interval limit
                pollMonitor.degraded(Monitors.Confidence.MEASURED);
                logger.warn("Consumer group {} APPROACHING POLL INTERVAL LIMIT: {}ms ({}% of {}ms)",
                    groupId, lastPollMs,
                    (int)(POLL_INTERVAL_WARNING_RATIO * 100), maxPollIntervalMs);

            } else {
                // Healthy poll rate
                pollMonitor.stable(Monitors.Confidence.CONFIRMED);
            }

        } catch (Exception e) {
            logger.debug("Could not check poll interval for group {}: {}", groupId, e.getMessage());
            // Don't emit signal if we can't measure
        }
    }

    // ========================================
    // JMX Query Methods
    // ========================================

    private double getHeartbeatLatency(String groupId) throws Exception {
        ObjectName objectName = new ObjectName(
            String.format("kafka.consumer:type=consumer-coordinator-metrics,client-id=%s", groupId)
        );
        Object value = mbsc.getAttribute(objectName, "heartbeat-response-time-max");
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private long getLastHeartbeatMs(String groupId) throws Exception {
        ObjectName objectName = new ObjectName(
            String.format("kafka.consumer:type=consumer-coordinator-metrics,client-id=%s", groupId)
        );
        Object value = mbsc.getAttribute(objectName, "last-heartbeat-seconds-ago");
        double secondsAgo = value instanceof Number ? ((Number) value).doubleValue() : 0.0;
        return System.currentTimeMillis() - (long)(secondsAgo * 1000);
    }

    private long getSessionTimeoutMs(String groupId) throws Exception {
        // Query from consumer config (typically 10000ms default)
        // For now, use a reasonable default
        return 10_000L;
    }

    private double getLastPollSecondsAgo(String groupId) throws Exception {
        ObjectName objectName = new ObjectName(
            String.format("kafka.consumer:type=consumer-coordinator-metrics,client-id=%s", groupId)
        );
        Object value = mbsc.getAttribute(objectName, "last-poll-seconds-ago");
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private long getMaxPollIntervalMs(String groupId) throws Exception {
        // Query from consumer config (typically 300000ms default)
        // For now, use a reasonable default
        return 300_000L;
    }
}
