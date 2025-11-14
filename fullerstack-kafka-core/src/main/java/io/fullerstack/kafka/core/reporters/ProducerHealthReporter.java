package io.fullerstack.kafka.core.reporters;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;

import static io.humainary.substrates.api.Substrates.cortex;

/**
 * Layer 3 (DECIDE phase): Assesses producer health urgency.
 *
 * <p>Subscribes to the producer root Cell outlet, receives aggregated {@link Monitors.Sign}
 * from all producers in the hierarchy, and emits {@link Reporters.Sign} based on urgency
 * assessment.
 *
 * <p><strong>Signal Flow:</strong>
 * <pre>
 * Producer Cell (DEGRADED) → Producer Root Cell
 *                                    ↓
 *                         ProducerHealthReporter
 *                                    ↓
 *                       Reporter.critical(OPERATIONAL)
 * </pre>
 *
 * <p><strong>Hierarchy:</strong>
 * <pre>
 * Producer Root Cell
 *   ├─ producer-1 (STABLE)
 *   ├─ producer-2 (DEGRADED)  ← Buffer overflow, send pressure
 *   └─ producer-3 (STABLE)
 * </pre>
 *
 * <p><strong>Urgency Mapping:</strong>
 * <ul>
 *   <li>{@link Monitors.Sign#DOWN DOWN}, {@link Monitors.Sign#DEFECTIVE DEFECTIVE},
 *       {@link Monitors.Sign#DEGRADED DEGRADED} → {@link Reporters.Sign#CRITICAL CRITICAL}</li>
 *   <li>{@link Monitors.Sign#ERRATIC ERRATIC}, {@link Monitors.Sign#DIVERGING DIVERGING}
 *       → {@link Reporters.Sign#WARNING WARNING}</li>
 *   <li>{@link Monitors.Sign#CONVERGING CONVERGING}, {@link Monitors.Sign#STABLE STABLE}
 *       → {@link Reporters.Sign#NORMAL NORMAL}</li>
 * </ul>
 *
 * <p><strong>Usage Example:</strong>
 * <pre>{@code
 * Circuit producerCircuit = cortex().circuit(cortex().name("producers"));
 * Cell<Monitors.Sign, Monitors.Sign> producerRoot = producerCircuit.cell(
 *     cortex().name("producer-root"),
 *     Composer.pipe(),  // Identity ingress
 *     Composer.pipe(),  // Identity egress
 *     cortex().pipe((Monitors.Sign sign) -> {})  // Outlet
 * );
 *
 * Circuit reporterCircuit = cortex().circuit(cortex().name("reporters"));
 * Conduit<Reporters.Reporter, Reporters.Sign> reporters = reporterCircuit.conduit(
 *     cortex().name("reporters"),
 *     Reporters::composer
 * );
 *
 * ProducerHealthReporter reporter = new ProducerHealthReporter(
 *     producerRoot,
 *     reporters
 * );
 *
 * // Reporter automatically receives aggregated Signs from producer hierarchy
 * // and emits urgency assessments to the reporters conduit
 * }</pre>
 *
 * @since 1.0.0
 */
public class ProducerHealthReporter implements AutoCloseable {

    private final Cell<Monitors.Sign, Monitors.Sign> producerRootCell;
    private final Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private final Reporters.Reporter reporter;
    private final Subscription subscription;

    /**
     * Creates a new ProducerHealthReporter.
     *
     * @param producerRootCell the producer root cell to subscribe to (receives aggregated Signs)
     * @param reporters the reporters conduit for emitting urgency assessments
     */
    public ProducerHealthReporter(
        Cell<Monitors.Sign, Monitors.Sign> producerRootCell,
        Conduit<Reporters.Reporter, Reporters.Sign> reporters
    ) {
        this.producerRootCell = producerRootCell;
        this.reporters = reporters;
        this.reporter = reporters.percept(cortex().name("producer-health"));

        // Subscribe to producer root cell outlet
        this.subscription = producerRootCell.subscribe(cortex().subscriber(
            cortex().name("producer-health-reporter"),
            this::handleProducerSign
        ));
    }

    /**
     * Handles aggregated Monitor Signs from the producer root cell.
     *
     * <p>Assesses urgency based on the aggregated Sign and emits appropriate
     * Reporter Sign via the reporter instrument.
     *
     * @param subject the producer root cell subject
     * @param registrar the registrar for handling Monitors.Sign emissions
     */
    private void handleProducerSign(
        Subject<Channel<Monitors.Sign>> subject,
        Registrar<Monitors.Sign> registrar
    ) {
        registrar.register(sign -> assessAndReport(sign));
    }

    /**
     * Assesses urgency from Monitor Sign and emits Reporter Sign.
     *
     * <p><strong>Urgency Rules:</strong>
     * <ul>
     *   <li>DOWN, DEFECTIVE, DEGRADED → CRITICAL (producer failure, buffer overflow)</li>
     *   <li>ERRATIC, DIVERGING → WARNING (buffer pressure, send latency increasing)</li>
     *   <li>CONVERGING, STABLE → NORMAL (producer healthy, normal operation)</li>
     * </ul>
     *
     * @param sign the aggregated Monitor Sign from producer hierarchy
     */
    private void assessAndReport(Monitors.Sign sign) {
        switch (sign) {
            case DOWN, DEFECTIVE -> {
                // Critical failure - producer completely down or not functioning
                reporter.critical();
            }
            case DEGRADED -> {
                // Degraded performance - buffer overflow, high send latency
                reporter.critical();
            }
            case ERRATIC, DIVERGING -> {
                // Early warning signs - buffer pressure building, latency increasing
                reporter.warning();
            }
            case CONVERGING, STABLE -> {
                // Normal operation - producer healthy
                reporter.normal();
            }
        }
    }

    /**
     * Closes this reporter by unsubscribing from the producer root cell.
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
