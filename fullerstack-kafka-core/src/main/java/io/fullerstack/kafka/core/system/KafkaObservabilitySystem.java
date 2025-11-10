package io.fullerstack.kafka.core.system;

import io.fullerstack.kafka.core.actors.*;
import io.fullerstack.kafka.core.bridge.MonitorCellBridge;
import io.fullerstack.kafka.core.command.CommandHierarchy;
import io.fullerstack.kafka.core.hierarchy.HierarchyManager;
import io.fullerstack.kafka.core.reporters.ClusterHealthReporter;
import io.fullerstack.kafka.core.reporters.ConsumerHealthReporter;
import io.fullerstack.kafka.core.reporters.ProducerHealthReporter;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Complete Kafka Observability System - Full OODA Loop Integration with Bidirectional Flow.
 *
 * <p>This class assembles all layers of the observability system:
 * <ul>
 *   <li><b>Layer 1-2 (OBSERVE/ORIENT)</b>: Monitor conduits + Cell hierarchy (upward)</li>
 *   <li><b>Bridge</b>: Connects Monitor emissions to Cell hierarchy</li>
 *   <li><b>Layer 3 (DECIDE)</b>: Reporters for urgency assessment</li>
 *   <li><b>Layer 4 (ACT)</b>: Actors for automated responses + Command hierarchy (downward)</li>
 * </ul>
 *
 * <h3>Bidirectional Flow:</h3>
 * <pre>
 * UPWARD (Sensing):
 * Monitor.status(DEGRADED) → MonitorCellBridge → Cell hierarchy aggregation
 *     → Reporter.critical() → Actor observes
 *
 * DOWNWARD (Control):
 * Actor.command(THROTTLE) → CommandHierarchy → Cascades to all partitions
 *     → Partition handlers execute physical action
 * </pre>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Create mock clients for external services
 * KafkaConfigManager configManager = new MyKafkaConfigManager();
 * PagerDutyClient pagerDuty = new MyPagerDutyClient();
 * SlackClient slack = new MySlackClient();
 * TeamsClient teams = new MyTeamsClient();
 *
 * // Build complete system
 * KafkaObservabilitySystem system = KafkaObservabilitySystem.builder()
 *     .clusterName("prod-cluster")
 *     .configManager(configManager)
 *     .pagerDutyClient(pagerDuty)
 *     .slackClient(slack)
 *     .teamsClient(teams)
 *     .build();
 *
 * // Start the system
 * system.start();
 *
 * // Get monitor conduit for emitting signals
 * Conduit<Monitors.Monitor, Monitors.Signal> monitors = system.getMonitors();
 *
 * // Emit a signal - flows through entire OODA loop
 * Monitor brokerMonitor = monitors.get(cortex().name("broker-1.jvm.heap"));
 * brokerMonitor.status(Monitors.Condition.DEGRADED, Monitors.Confidence.HIGH);
 *
 * // ... system automatically:
 * // 1. Routes signal to broker cell via bridge
 * // 2. Cell aggregates to cluster
 * // 3. ClusterHealthReporter assesses urgency → CRITICAL
 * // 4. AlertActor sends PagerDuty/Slack/Teams alerts
 *
 * // Shutdown cleanly
 * system.close();
 * }</pre>
 *
 * @since 1.0.0
 */
public class KafkaObservabilitySystem implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(KafkaObservabilitySystem.class);

    // Circuits
    private final Circuit monitorCircuit;
    private final Circuit reporterCircuit;
    private final Circuit actorCircuit;

    // Layer 1-2: Monitors + Cells (Upward Flow)
    private final Conduit<Monitors.Monitor, Monitors.Signal> monitors;
    private final HierarchyManager hierarchy;
    private final MonitorCellBridge bridge;

    // Command Hierarchy (Downward Flow)
    private final CommandHierarchy commandHierarchy;

    // Layer 3: Reporters
    private final Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private final ProducerHealthReporter producerHealthReporter;
    private final ConsumerHealthReporter consumerHealthReporter;
    private final ClusterHealthReporter clusterHealthReporter;

    // Layer 4: Actors
    private final Conduit<Actors.Actor, Actors.Sign> actors;
    private final ThrottleActor throttleActor;
    private final AlertActor alertActor;

    private final String clusterName;

    /**
     * Private constructor - use builder() to create instances.
     */
    private KafkaObservabilitySystem(
        String clusterName,
        KafkaConfigManager configManager,
        PagerDutyClient pagerDutyClient,
        SlackClient slackClient,
        TeamsClient teamsClient
    ) {
        this.clusterName = clusterName;

        logger.info("Initializing KafkaObservabilitySystem for cluster: {}", clusterName);

        // ===== Layer 1-2: OBSERVE + ORIENT =====

        // Create monitor circuit
        this.monitorCircuit = cortex().circuit(cortex().name(clusterName + "-monitors"));

        // Create monitor conduit (flat namespace)
        this.monitors = monitorCircuit.conduit(
            cortex().name("monitors"),
            Monitors::composer
        );

        // Create Cell hierarchy for aggregation
        this.hierarchy = new HierarchyManager(clusterName);

        // Create bridge to connect monitors → cells
        this.bridge = new MonitorCellBridge(monitors, hierarchy);

        // Create command hierarchy for downward control flow
        this.commandHierarchy = new CommandHierarchy(clusterName);

        logger.info("Layer 1-2 initialized: Monitors conduit + Cell hierarchy + Bridge + Command hierarchy");

        // ===== Layer 3: DECIDE =====

        // Create reporter circuit
        this.reporterCircuit = cortex().circuit(cortex().name(clusterName + "-reporters"));

        // Create reporter conduit
        this.reporters = reporterCircuit.conduit(
            cortex().name("reporters"),
            Reporters::composer
        );

        // Create root cells for reporters to subscribe to
        Cell<Monitors.Sign, Monitors.Sign> clusterCell = hierarchy.getClusterCell();

        // Create producer root cell for producer health aggregation
        Cell<Monitors.Sign, Monitors.Sign> producerRootCell = monitorCircuit.cell(
            cortex().name("producer-root"),
            Composer.pipe(),
            Composer.pipe(),
            cortex().pipe((Monitors.Sign sign) -> {})
        );

        // Create consumer root cell for consumer health aggregation
        Cell<Monitors.Sign, Monitors.Sign> consumerRootCell = monitorCircuit.cell(
            cortex().name("consumer-root"),
            Composer.pipe(),
            Composer.pipe(),
            cortex().pipe((Monitors.Sign sign) -> {})
        );

        // Create reporters (subscribe to cell outlets)
        this.producerHealthReporter = new ProducerHealthReporter(
            producerRootCell,  // Subscribe to producer root cell
            reporters
        );

        this.consumerHealthReporter = new ConsumerHealthReporter(
            monitors,  // Subscribe to monitors conduit directly
            reporters
        );

        this.clusterHealthReporter = new ClusterHealthReporter(
            clusterCell,  // Subscribe to cluster cell for cluster health
            reporters
        );

        logger.info("Layer 3 initialized: 3 Reporters (Producer, Consumer, Cluster)");

        // ===== Layer 4: ACT =====

        // Create actor circuit
        this.actorCircuit = cortex().circuit(cortex().name(clusterName + "-actors"));

        // Create actor conduit
        this.actors = actorCircuit.conduit(
            cortex().name("actors"),
            Actors::composer
        );

        // Create actors (subscribe to reporters)
        this.throttleActor = new ThrottleActor(
            reporters,
            actors,
            configManager
        );

        this.alertActor = new AlertActor(
            reporters,
            actors,
            pagerDutyClient,
            slackClient,
            teamsClient,
            clusterName
        );

        logger.info("Layer 4 initialized: 2 Actors (Throttle, Alert)");

        logger.info("KafkaObservabilitySystem initialization complete for cluster: {}", clusterName);
    }

    /**
     * Starts the system by activating the bridge.
     *
     * <p>Once started, Monitor signals will flow through the complete OODA loop.
     */
    public void start() {
        logger.info("Starting KafkaObservabilitySystem for cluster: {}", clusterName);

        // Start the bridge to connect monitors → cells
        bridge.start();

        logger.info("✅ KafkaObservabilitySystem ACTIVE - Full OODA loop operational");
        logger.info("   Monitor signals will now flow: Monitor → Cell → Reporter → Actor");
    }

    /**
     * Returns the Monitor conduit for emitting signals.
     *
     * <p>Use this to get Monitor instruments and emit operational signals.
     *
     * @return the monitor conduit
     */
    public Conduit<Monitors.Monitor, Monitors.Signal> getMonitors() {
        return monitors;
    }

    /**
     * Returns the Cell hierarchy manager.
     *
     * <p>Use this to access the Cell hierarchy for aggregation.
     *
     * @return the hierarchy manager
     */
    public HierarchyManager getHierarchy() {
        return hierarchy;
    }

    /**
     * Returns the Reporter conduit.
     *
     * <p>Use this to observe Reporter urgency assessments.
     *
     * @return the reporter conduit
     */
    public Conduit<Reporters.Reporter, Reporters.Sign> getReporters() {
        return reporters;
    }

    /**
     * Returns the Actor conduit.
     *
     * <p>Use this to observe Actor speech acts (DELIVER/DENY).
     *
     * @return the actor conduit
     */
    public Conduit<Actors.Actor, Actors.Sign> getActors() {
        return actors;
    }

    /**
     * Returns the Command hierarchy for issuing control commands.
     *
     * <p>Use this to broadcast commands downward through the hierarchy.
     *
     * @return the command hierarchy
     */
    public CommandHierarchy getCommandHierarchy() {
        return commandHierarchy;
    }

    /**
     * Returns the cluster name.
     *
     * @return the cluster name
     */
    public String getClusterName() {
        return clusterName;
    }

    /**
     * Waits for all circuits to finish processing pending signals.
     *
     * <p>Useful in tests to ensure signals have fully propagated.
     */
    public void await() {
        monitorCircuit.await();
        reporterCircuit.await();
        actorCircuit.await();
        commandHierarchy.await();
    }

    /**
     * Closes the system and releases all resources.
     *
     * <p>Shutdown order:
     * <ol>
     *   <li>Close actors (stop taking actions)</li>
     *   <li>Close reporters (stop assessing)</li>
     *   <li>Close bridge (stop routing signals)</li>
     *   <li>Close hierarchy (release cells)</li>
     *   <li>Close circuits</li>
     * </ol>
     */
    @Override
    public void close() {
        logger.info("Shutting down KafkaObservabilitySystem for cluster: {}", clusterName);

        // Close in reverse order of initialization
        if (alertActor != null) alertActor.close();
        if (throttleActor != null) throttleActor.close();

        if (clusterHealthReporter != null) clusterHealthReporter.close();
        if (consumerHealthReporter != null) consumerHealthReporter.close();
        if (producerHealthReporter != null) producerHealthReporter.close();

        if (bridge != null) bridge.close();
        if (commandHierarchy != null) commandHierarchy.close();
        if (hierarchy != null) hierarchy.close();

        if (actorCircuit != null) actorCircuit.close();
        if (reporterCircuit != null) reporterCircuit.close();
        if (monitorCircuit != null) monitorCircuit.close();

        logger.info("✅ KafkaObservabilitySystem shutdown complete");
    }

    /**
     * Creates a new builder for constructing a KafkaObservabilitySystem.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for KafkaObservabilitySystem.
     */
    public static class Builder {
        private String clusterName;
        private KafkaConfigManager configManager;
        private PagerDutyClient pagerDutyClient;
        private SlackClient slackClient;
        private TeamsClient teamsClient;

        private Builder() {}

        /**
         * Sets the cluster name.
         *
         * @param clusterName the cluster name
         * @return this builder
         */
        public Builder clusterName(String clusterName) {
            this.clusterName = clusterName;
            return this;
        }

        /**
         * Sets the Kafka config manager (required for ThrottleActor).
         *
         * @param configManager the config manager
         * @return this builder
         */
        public Builder configManager(KafkaConfigManager configManager) {
            this.configManager = configManager;
            return this;
        }

        /**
         * Sets the PagerDuty client (required for AlertActor).
         *
         * @param pagerDutyClient the PagerDuty client
         * @return this builder
         */
        public Builder pagerDutyClient(PagerDutyClient pagerDutyClient) {
            this.pagerDutyClient = pagerDutyClient;
            return this;
        }

        /**
         * Sets the Slack client (required for AlertActor).
         *
         * @param slackClient the Slack client
         * @return this builder
         */
        public Builder slackClient(SlackClient slackClient) {
            this.slackClient = slackClient;
            return this;
        }

        /**
         * Sets the Teams client (required for AlertActor).
         *
         * @param teamsClient the Teams client
         * @return this builder
         */
        public Builder teamsClient(TeamsClient teamsClient) {
            this.teamsClient = teamsClient;
            return this;
        }

        /**
         * Builds the KafkaObservabilitySystem.
         *
         * @return the configured system
         * @throws IllegalStateException if required fields are missing
         */
        public KafkaObservabilitySystem build() {
            if (clusterName == null || clusterName.isEmpty()) {
                throw new IllegalStateException("clusterName is required");
            }
            if (configManager == null) {
                throw new IllegalStateException("configManager is required");
            }
            if (pagerDutyClient == null) {
                throw new IllegalStateException("pagerDutyClient is required");
            }
            if (slackClient == null) {
                throw new IllegalStateException("slackClient is required");
            }
            if (teamsClient == null) {
                throw new IllegalStateException("teamsClient is required");
            }

            return new KafkaObservabilitySystem(
                clusterName,
                configManager,
                pagerDutyClient,
                slackClient,
                teamsClient
            );
        }
    }
}
