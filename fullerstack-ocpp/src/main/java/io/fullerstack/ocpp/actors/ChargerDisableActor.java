package io.fullerstack.ocpp.actors;

import io.fullerstack.ocpp.server.OcppCommand;
import io.fullerstack.ocpp.server.OcppCommandExecutor;
import io.humainary.substrates.Channel;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.Name;
import io.humainary.substrates.Registrar;
import io.humainary.substrates.Subject;
import io.humainary.substrates.Subscription;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static io.humainary.substrates.Substrates.cortex;

/**
 * Actor that disables chargers when they become critically unhealthy.
 * <p>
 * Semantic Command: CHARGER_DISABLE
 * - Subscribes to ChargerHealthReporter CRITICAL signals
 * - Executes ChangeAvailability(Inoperative) OCPP command
 * - Emits DELIVER/DENY speech acts for observability
 * </p>
 * <p>
 * Pattern follows ThrottleActor - subscribe to reporters with filtering,
 * execute semantic command with protection.
 * </p>
 */
public class ChargerDisableActor extends BaseActor {
    private static final Logger logger = LoggerFactory.getLogger(ChargerDisableActor.class);
    private static final long RATE_LIMIT_MS = 300_000;  // 5 minutes

    private final OcppCommandExecutor commandExecutor;
    private final Subscription subscription;

    public ChargerDisableActor(
        Conduit<Reporters.Reporter, Reporters.Sign> reporters,
        Conduit<Actors.Actor, Actors.Sign> actors,
        OcppCommandExecutor commandExecutor
    ) {
        super(actors, "charger-disable-actor", RATE_LIMIT_MS);
        this.commandExecutor = commandExecutor;

        // Subscribe to all charger health reporters
        this.subscription = reporters.subscribe(
            cortex().subscriber(
                cortex().name("charger-disable-actor-subscriber"),
                this::handleReporterSignal
            )
        );

        logger.info("ChargerDisableActor initialized");
    }

    /**
     * Handle reporter signals and filter for CRITICAL charger health.
     */
    private void handleReporterSignal(
        Subject<Channel<Reporters.Sign>> subject,
        Registrar<Reporters.Sign> registrar
    ) {
        Name reporterName = subject.name();

        // Filter: Only register for charger health reporters (pattern: "{chargerId}.health")
        if (isChargerHealthReporter(reporterName)) {
            registrar.register(sign -> {
                if (sign == Reporters.Sign.CRITICAL) {
                    handleChargerCritical(reporterName);
                }
            });
        }
    }

    /**
     * Check if reporter name matches charger health pattern.
     */
    private boolean isChargerHealthReporter(Name reporterName) {
        return reporterName.depth() == 2 &&
               "health".equals(reporterName.value());
    }

    /**
     * Handle CRITICAL health signal by disabling the charger.
     */
    private void handleChargerCritical(Name reporterName) {
        String chargerId = extractChargerId(reporterName);
        String actionKey = "disable:" + chargerId;

        executeWithProtection(actionKey, () -> {
            logger.warn("Disabling charger {} due to critical health", chargerId);

            // Create OCPP ChangeAvailability command (Inoperative)
            OcppCommand.ChangeAvailability command = new OcppCommand.ChangeAvailability(
                chargerId,
                UUID.randomUUID().toString(),
                0,  // Connector 0 = entire charger
                "Inoperative"
            );

            // Execute command
            boolean success = commandExecutor.executeCommand(command);
            if (!success) {
                throw new RuntimeException("Failed to send ChangeAvailability command to charger");
            }

            logger.info("Successfully disabled charger {}", chargerId);
        });
    }

    /**
     * Extract charger ID from reporter name.
     * Example: "charger-001.health" → "charger-001"
     */
    private String extractChargerId(Name reporterName) {
        // Get enclosing name (parent)
        Name enclosure = reporterName.enclosure();
        return enclosure != null ? enclosure.value() : reporterName.value();
    }

    @Override
    public void close() {
        logger.info("Closing ChargerDisableActor");
        if (subscription != null) {
            subscription.cancel();
        }
        super.close();
    }
}
