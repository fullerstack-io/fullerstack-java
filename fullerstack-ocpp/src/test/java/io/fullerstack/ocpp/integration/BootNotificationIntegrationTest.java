package io.fullerstack.ocpp.integration;

import io.fullerstack.ocpp.server.OcppMessage;
import io.fullerstack.ocpp.system.OcppObservabilitySystem;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.humainary.substrates.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test demonstrating BootNotification flow through signal architecture.
 * <p>
 * Test scenario:
 * 1. Charger sends BootNotification
 * 2. Observer translates to Monitor.STABLE signal
 * 3. Reporter assesses as NORMAL urgency
 * 4. System emits CHARGER_REGISTERED semantic signal
 * </p>
 */
@DisplayName("OCPP BootNotification Integration Test")
class BootNotificationIntegrationTest {

    private OcppObservabilitySystem system;
    private List<Monitors.Sign> monitorEmissions;

    @BeforeEach
    void setUp() {
        system = new OcppObservabilitySystem("test-system", 8080);
        system.start();

        // Capture monitor signal emissions
        monitorEmissions = new CopyOnWriteArrayList<>();
        system.getMonitors().subscribe(cortex().subscriber(
            cortex().name("test-monitor-subscriber"),
            (subject, registrar) -> {
                registrar.register(monitorEmissions::add);
            }
        ));
    }

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
    }

    @Test
    @DisplayName("BootNotification should emit STABLE monitor signal")
    void testBootNotificationEmitsStable() {
        // GIVEN: Charger is booting up
        String chargerId = "charger-001";

        // WHEN: Charger sends BootNotification
        OcppMessage.BootNotification bootMessage = new OcppMessage.BootNotification(
            chargerId,
            Instant.now(),
            "msg-001",
            "ChargePoint-Pro",
            "ACME Corporation",
            "SN-12345",
            "v2.0.1"
        );

        system.getCentralSystem().simulateIncomingMessage(bootMessage);

        // Wait for signal propagation
        system.awaitSignalProcessing();

        // THEN: Monitor should emit STABLE signal
        assertThat(monitorEmissions)
            .as("Monitor should emit STABLE for successful boot")
            .contains(Monitors.Sign.STABLE);
    }

    @Test
    @DisplayName("Multiple chargers can register independently")
    void testMultipleChargerRegistrations() {
        // GIVEN: Three chargers
        List<String> chargerIds = List.of("charger-001", "charger-002", "charger-003");

        // WHEN: All chargers send BootNotification
        for (String chargerId : chargerIds) {
            OcppMessage.BootNotification bootMessage = new OcppMessage.BootNotification(
                chargerId,
                Instant.now(),
                "msg-" + chargerId,
                "ChargePoint-Pro",
                "ACME Corporation",
                "SN-" + chargerId,
                "v2.0.1"
            );

            system.getCentralSystem().simulateIncomingMessage(bootMessage);
        }

        // Wait for all signals
        system.awaitSignalProcessing();

        // THEN: Should emit STABLE for each charger
        long stableCount = monitorEmissions.stream()
            .filter(sign -> sign == Monitors.Sign.STABLE)
            .count();

        assertThat(stableCount)
            .as("Should emit STABLE for all three chargers")
            .isGreaterThanOrEqualTo(3);
    }
}
