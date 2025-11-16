package io.fullerstack.ocpp.integration;

import io.fullerstack.ocpp.server.OcppMessage;
import io.fullerstack.ocpp.system.OcppObservabilitySystem;
import io.humainary.substrates.ext.serventis.ext.Actors;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.humainary.substrates.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Complete OODA Loop integration test.
 * <p>
 * Test scenario: Charger FAULTED → CRITICAL → DISABLE
 * 1. OBSERVE: Charger reports fault via StatusNotification
 * 2. ORIENT: Observer emits Monitor.DEFECTIVE signal
 * 3. DECIDE: Reporter assesses as Reporter.CRITICAL
 * 4. ACT: ChargerDisableActor executes ChangeAvailability command
 * 5. Verify: Actor emits DELIVER speech act
 * </p>
 */
@DisplayName("Complete OODA Loop: Charger Fault Detection and Adaptive Response")
class ChargerHealthOodaLoopIT {

    private OcppObservabilitySystem system;
    private CopyOnWriteArrayList<Monitors.Sign> monitorSigns;
    private CopyOnWriteArrayList<Reporters.Sign> reporterSigns;
    private CopyOnWriteArrayList<Actors.Sign> actorSigns;

    @BeforeEach
    void setUp() {
        system = new OcppObservabilitySystem("ooda-test-system", 8081);
        system.start();

        // Capture all signal emissions
        monitorSigns = new CopyOnWriteArrayList<>();
        reporterSigns = new CopyOnWriteArrayList<>();
        actorSigns = new CopyOnWriteArrayList<>();

        system.getMonitors().subscribe(cortex().subscriber(
            cortex().name("test-monitor-sub"),
            (subject, registrar) -> registrar.register(monitorSigns::add)
        ));

        system.getReporters().subscribe(cortex().subscriber(
            cortex().name("test-reporter-sub"),
            (subject, registrar) -> registrar.register(reporterSigns::add)
        ));

        system.getActors().subscribe(cortex().subscriber(
            cortex().name("test-actor-sub"),
            (subject, registrar) -> registrar.register(actorSigns::add)
        ));
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
    }

    @Test
    @DisplayName("Complete OODA Loop: Fault → DEFECTIVE → CRITICAL → DISABLE → DELIVER")
    void testCompleteOodaLoop() {
        String chargerId = "charger-critical-001";

        // STEP 1: Register charger (OBSERVE)
        OcppMessage.BootNotification boot = new OcppMessage.BootNotification(
            chargerId,
            Instant.now(),
            "boot-001",
            "ChargePoint-Pro",
            "ACME",
            "SN-001",
            "v2.0"
        );
        system.getCentralSystem().simulateIncomingMessage(boot);
        system.awaitSignalProcessing();

        // Clear initial signals
        monitorSigns.clear();
        reporterSigns.clear();
        actorSigns.clear();

        // STEP 2: Charger reports FAULT (OBSERVE)
        OcppMessage.StatusNotification faultStatus = new OcppMessage.StatusNotification(
            chargerId,
            Instant.now(),
            "status-001",
            1,  // Connector 1
            "Faulted",
            "GroundFailure"  // Inoperative error
        );

        system.getCentralSystem().simulateIncomingMessage(faultStatus);

        // STEP 3: Wait for complete signal flow
        system.awaitSignalProcessing();

        // VERIFY: ORIENT - Monitor emits DOWN signal (GroundFailure is inoperative)
        assertThat(monitorSigns)
            .as("Monitor should emit DOWN for GroundFailure")
            .contains(Monitors.Sign.DOWN);

        // VERIFY: DECIDE - Reporter assesses as CRITICAL
        assertThat(reporterSigns)
            .as("Reporter should assess DOWN as CRITICAL urgency")
            .contains(Reporters.Sign.CRITICAL);

        // VERIFY: ACT - Actor executes disable command and emits DELIVER
        assertThat(actorSigns)
            .as("Actor should emit DELIVER after successful command execution")
            .contains(Actors.Sign.DELIVER);
    }

    @Test
    @DisplayName("DEGRADED status triggers WARNING but not actor intervention")
    void testDegradedStatusWarningOnly() {
        String chargerId = "charger-degraded-001";

        // Register charger
        OcppMessage.BootNotification boot = new OcppMessage.BootNotification(
            chargerId,
            Instant.now(),
            "boot-002",
            "ChargePoint-Pro",
            "ACME",
            "SN-002",
            "v2.0"
        );
        system.getCentralSystem().simulateIncomingMessage(boot);
        system.awaitSignalProcessing();

        // Clear signals
        monitorSigns.clear();
        reporterSigns.clear();
        actorSigns.clear();

        // Charger reports UNAVAILABLE (non-critical degradation)
        OcppMessage.StatusNotification unavailableStatus = new OcppMessage.StatusNotification(
            chargerId,
            Instant.now(),
            "status-002",
            1,
            "Unavailable",
            "NoError"
        );

        system.getCentralSystem().simulateIncomingMessage(unavailableStatus);
        system.awaitSignalProcessing();

        // VERIFY: Monitor emits DEGRADED
        assertThat(monitorSigns)
            .as("Monitor should emit DEGRADED for Unavailable status")
            .contains(Monitors.Sign.DEGRADED);

        // VERIFY: Reporter assesses as WARNING (not CRITICAL)
        assertThat(reporterSigns)
            .as("Reporter should assess DEGRADED as WARNING")
            .contains(Reporters.Sign.WARNING);

        // VERIFY: No actor intervention (no DELIVER/DENY)
        assertThat(actorSigns)
            .as("Actor should NOT intervene for WARNING level")
            .isEmpty();
    }
}
