package io.fullerstack.ocpp.observers;

import io.fullerstack.ocpp.server.OcppMessage;
import io.humainary.substrates.Circuit;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Gauges;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.humainary.substrates.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OcppMessageObserver.
 */
@DisplayName("OCPP Message Observer Tests")
class OcppMessageObserverTest {

    private Circuit monitorCircuit;
    private Circuit counterCircuit;
    private Circuit gaugeCircuit;
    private Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private Conduit<Counters.Counter, Counters.Sign> counters;
    private Conduit<Gauges.Gauge, Gauges.Sign> gauges;
    private OcppMessageObserver observer;

    private CopyOnWriteArrayList<Monitors.Sign> monitorSigns;
    private CopyOnWriteArrayList<Counters.Sign> counterSigns;

    @BeforeEach
    void setUp() {
        monitorCircuit = cortex().circuit(cortex().name("test-monitors"));
        monitors = monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);

        counterCircuit = cortex().circuit(cortex().name("test-counters"));
        counters = counterCircuit.conduit(cortex().name("counters"), Counters::composer);

        gaugeCircuit = cortex().circuit(cortex().name("test-gauges"));
        gauges = gaugeCircuit.conduit(cortex().name("gauges"), Gauges::composer);

        observer = new OcppMessageObserver(monitors, counters, gauges);

        // Capture emissions
        monitorSigns = new CopyOnWriteArrayList<>();
        counterSigns = new CopyOnWriteArrayList<>();

        monitors.subscribe(cortex().subscriber(
            cortex().name("test-sub"),
            (subject, registrar) -> registrar.register(monitorSigns::add)
        ));

        counters.subscribe(cortex().subscriber(
            cortex().name("test-counter-sub"),
            (subject, registrar) -> registrar.register(counterSigns::add)
        ));
    }

    @AfterEach
    void tearDown() {
        if (observer != null) {
            observer.close();
        }
    }

    @Test
    @DisplayName("BootNotification emits STABLE and increments counter")
    void testBootNotificationEmitsStable() {
        // GIVEN
        OcppMessage.BootNotification boot = new OcppMessage.BootNotification(
            "charger-001",
            Instant.now(),
            "msg-001",
            "Model-X",
            "Vendor-Y",
            "SN-123",
            "v1.0"
        );

        // WHEN
        observer.handleMessage(boot);
        monitorCircuit.await();
        counterCircuit.await();

        // THEN
        assertThat(monitorSigns).contains(Monitors.Sign.STABLE);
        assertThat(counterSigns).contains(Counters.Sign.INCREASE);
    }

    @Test
    @DisplayName("StatusNotification with NoError emits appropriate signs")
    void testStatusNotificationNoError() {
        // GIVEN: Charging status
        OcppMessage.StatusNotification status = new OcppMessage.StatusNotification(
            "charger-001",
            Instant.now(),
            "msg-002",
            1,
            "Charging",
            "NoError"
        );

        // WHEN
        observer.handleMessage(status);
        monitorCircuit.await();

        // THEN: Charging is STABLE
        assertThat(monitorSigns).contains(Monitors.Sign.STABLE);
    }

    @Test
    @DisplayName("StatusNotification with fault emits DEFECTIVE")
    void testStatusNotificationWithFault() {
        // GIVEN: Faulted connector
        OcppMessage.StatusNotification fault = new OcppMessage.StatusNotification(
            "charger-001",
            Instant.now(),
            "msg-003",
            1,
            "Faulted",
            "HighTemperature"
        );

        // WHEN
        observer.handleMessage(fault);
        monitorCircuit.await();

        // THEN: Should emit DOWN (inoperative error)
        assertThat(monitorSigns).contains(Monitors.Sign.DOWN);
    }

    @Test
    @DisplayName("StartTransaction increments counter")
    void testStartTransactionIncrementsCounter() {
        // GIVEN
        OcppMessage.StartTransaction start = new OcppMessage.StartTransaction(
            "charger-001",
            Instant.now(),
            "msg-004",
            1,
            "user-001",
            0.0
        );

        // WHEN
        observer.handleMessage(start);
        counterCircuit.await();

        // THEN
        assertThat(counterSigns).contains(Counters.Sign.INCREASE);
    }

    @Test
    @DisplayName("StopTransaction with EmergencyStop emits ERRATIC")
    void testStopTransactionEmergencyStop() {
        // GIVEN
        OcppMessage.StopTransaction stop = new OcppMessage.StopTransaction(
            "charger-001",
            Instant.now(),
            "msg-005",
            "txn-001",
            "user-001",
            15.5,
            "EmergencyStop"
        );

        // WHEN
        observer.handleMessage(stop);
        monitorCircuit.await();
        counterCircuit.await();

        // THEN
        assertThat(monitorSigns).contains(Monitors.Sign.ERRATIC);
        assertThat(counterSigns).contains(Counters.Sign.INCREASE);
    }
}
