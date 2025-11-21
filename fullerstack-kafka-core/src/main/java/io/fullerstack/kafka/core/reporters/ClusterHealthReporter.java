package io.fullerstack.kafka.core.reporters;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Situations;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 3 (DECIDE phase): Assesses cluster health urgency.
 *
 * <p>Subscribes to the cluster Cell outlet, receives aggregated {@link Monitors.Sign}
 * from all brokers in the cluster hierarchy, and emits {@link Situations.Sign} based
 * on urgency assessment.
 *
 * <p><strong>Signal Flow:</strong>
 * <pre>
 * Partition Cell (DEGRADED) → Topic Cell → Broker Cell → Cluster Cell
 *                                                              ↓
 *                                                    ClusterHealthReporter
 *                                                              ↓
 *                                              Situation.critical(OPERATIONAL)
 * </pre>
 *
 * <p><strong>Urgency Mapping:</strong>
 * <ul>
 *   <li>{@link Monitors.Sign#DOWN DOWN}, {@link Monitors.Sign#DEFECTIVE DEFECTIVE},
 *       {@link Monitors.Sign#DEGRADED DEGRADED} → {@link Situations.Sign#CRITICAL CRITICAL}</li>
 *   <li>{@link Monitors.Sign#ERRATIC ERRATIC}, {@link Monitors.Sign#DIVERGING DIVERGING}
 *       → {@link Situations.Sign#WARNING WARNING}</li>
 *   <li>{@link Monitors.Sign#CONVERGING CONVERGING}, {@link Monitors.Sign#STABLE STABLE}
 *       → {@link Situations.Sign#NORMAL NORMAL}</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 * <pre>{@code
 * HierarchyManager hierarchy = new HierarchyManager("prod-cluster");
 * Circuit reporterCircuit = cortex().circuit(cortex().name("reporters"));
 * Conduit<Situations.Situation, Situations.Signal> reporters = reporterCircuit.conduit(
 *     cortex().name("reporters"),
 *     Situations::composer
 * );
 *
 * ClusterHealthReporter reporter = new ClusterHealthReporter(
 *     hierarchy.getClusterCell(),
 *     reporters
 * );
 *
 * // Situation automatically receives aggregated Signs from cluster hierarchy
 * // and emits urgency assessments to the reporters conduit
 * }</pre>
 *
 * @since 1.0.0
 * @see io.fullerstack.kafka.core.hierarchy.HierarchyManager
 */
public class ClusterHealthReporter implements AutoCloseable {

    private final Cell<Monitors.Sign, Monitors.Sign> clusterCell;
    private final Conduit<Situations.Situation, Situations.Signal> reporters;
    private final Situations.Situation reporter;
    private final Subscription subscription;

    /**
     * Creates a new ClusterHealthReporter.
     *
     * @param clusterCell the cluster cell to subscribe to (receives aggregated Signs)
     * @param reporters the reporters conduit for emitting urgency assessments
     */
    public ClusterHealthReporter(
        Cell<Monitors.Sign, Monitors.Sign> clusterCell,
        Conduit<Situations.Situation, Situations.Signal> reporters
    ) {
        this.clusterCell = clusterCell;
        this.reporters = reporters;
        this.reporter = reporters.percept(cortex().name("cluster.health"));

        // Subscribe to cluster cell outlet
        this.subscription = clusterCell.subscribe(cortex().subscriber(
            cortex().name("cluster.health.reporter"),
            this::handleClusterSign
        ));
    }

    /**
     * Handles aggregated Monitor Signs from the cluster cell.
     *
     * <p>Assesses urgency based on the aggregated Sign and emits appropriate
     * Situation Sign via the reporter instrument.
     *
     * @param subject the cluster cell subject
     * @param registrar the registrar for handling Monitors.Sign emissions
     */
    private void handleClusterSign(
        Subject<Channel<Monitors.Sign>> subject,
        Registrar<Monitors.Sign> registrar
    ) {
        registrar.register(sign -> assessAndReport(sign));
    }

    /**
     * Assesses urgency from Monitor Sign and emits Situation Sign.
     *
     * <p><strong>Urgency Rules:</strong>
     * <ul>
     *   <li>DOWN, DEFECTIVE, DEGRADED → CRITICAL (immediate action required)</li>
     *   <li>ERRATIC, DIVERGING → WARNING (attention needed)</li>
     *   <li>CONVERGING, STABLE → NORMAL (system healthy)</li>
     * </ul>
     *
     * @param sign the aggregated Monitor Sign from cluster hierarchy
     */
    private void assessAndReport(Monitors.Sign sign) {
        switch (sign) {
            case DOWN, DEFECTIVE -> {
                // Critical failure - brokers down or not functioning
                reporter.critical(Situations.Dimension.CONSTANT);
            }
            case DEGRADED -> {
                // Degraded performance - requires immediate attention
                reporter.critical(Situations.Dimension.CONSTANT);
            }
            case ERRATIC, DIVERGING -> {
                // Early warning signs - unstable or trending toward degradation
                reporter.warning(Situations.Dimension.VARIABLE);
            }
            case CONVERGING, STABLE -> {
                // Normal operation - converging toward stability or stable
                reporter.normal(Situations.Dimension.CONSTANT);
            }
        }
    }

    /**
     * Closes this reporter by unsubscribing from the cluster cell.
     *
     * <p>The subscription is automatically cleaned up when the underlying
     * circuit is closed, but explicit close is provided for resource management.
     */
    @Override
    public void close() {
        if (subscription != null) {
            subscription.close();
        }
    }
}
