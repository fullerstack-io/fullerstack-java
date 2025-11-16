package io.fullerstack.ocpp.reporters;

import io.humainary.substrates.Channel;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.Registrar;
import io.humainary.substrates.Subject;
import io.humainary.substrates.Subscription;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Layer 3 (DECIDE) component that assesses charger health urgency.
 * <p>
 * Subscribes to Monitor signals from chargers and emits Reporter signals
 * indicating urgency levels (NORMAL, WARNING, CRITICAL).
 * </p>
 * <p>
 * Pattern follows ClusterHealthReporter - subscribe to monitors, assess urgency,
 * emit reporter signals.
 * </p>
 */
public class ChargerHealthReporter implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ChargerHealthReporter.class);

    private final Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private final Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private final Subscription subscription;

    public ChargerHealthReporter(
        Conduit<Monitors.Monitor, Monitors.Sign> monitors,
        Conduit<Reporters.Reporter, Reporters.Sign> reporters
    ) {
        this.monitors = monitors;
        this.reporters = reporters;

        // Subscribe to all charger monitor signals
        this.subscription = monitors.subscribe(
            cortex().subscriber(
                cortex().name("charger-health-reporter"),
                this::handleMonitorSignal
            )
        );

        logger.info("ChargerHealthReporter initialized");
    }

    /**
     * Handle monitor signals from chargers and assess urgency.
     */
    private void handleMonitorSignal(
        Subject<Channel<Monitors.Sign>> subject,
        Registrar<Monitors.Sign> registrar
    ) {
        String chargerName = subject.name().path();

        // Only monitor base charger names (not sub-components like .connector.1)
        if (chargerName.contains(".connector") ||
            chargerName.contains(".firmware") ||
            chargerName.contains(".diagnostics")) {
            return;  // Skip sub-components
        }

        registrar.register(sign -> {
            logger.debug("Charger {} monitor sign: {}", chargerName, sign);

            Reporters.Sign urgency = assessUrgency(sign);

            // Emit reporter signal
            Reporters.Reporter healthReporter = reporters.percept(
                cortex().name(chargerName + ".health")
            );

            switch (urgency) {
                case CRITICAL -> {
                    logger.warn("Charger {} health CRITICAL: monitor sign = {}",
                        chargerName, sign);
                    healthReporter.critical();
                }
                case WARNING -> {
                    logger.info("Charger {} health WARNING: monitor sign = {}",
                        chargerName, sign);
                    healthReporter.warning();
                }
                case NORMAL -> {
                    logger.debug("Charger {} health NORMAL: monitor sign = {}",
                        chargerName, sign);
                    healthReporter.normal();
                }
            }
        });
    }

    /**
     * Map Monitor.Sign to Reporter.Sign (urgency assessment).
     */
    private Reporters.Sign assessUrgency(Monitors.Sign sign) {
        return switch (sign) {
            // CRITICAL: Complete failure or defective
            case DOWN, DEFECTIVE -> Reporters.Sign.CRITICAL;

            // WARNING: Degraded performance or erratic behavior
            case DEGRADED, ERRATIC, DIVERGING -> Reporters.Sign.WARNING;

            // NORMAL: Healthy operation
            case STABLE, CONVERGING -> Reporters.Sign.NORMAL;
        };
    }

    @Override
    public void close() {
        logger.info("Closing ChargerHealthReporter");
        if (subscription != null) {
            subscription.cancel();
        }
    }
}
