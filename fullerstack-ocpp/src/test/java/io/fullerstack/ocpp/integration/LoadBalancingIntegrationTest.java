package io.fullerstack.ocpp.integration;

import io.fullerstack.ocpp.agents.LoadBalancingAgent;
import io.fullerstack.ocpp.observers.OcppMessageObserver;
import io.fullerstack.ocpp.reporters.LoadBalancingReporter;
import io.fullerstack.ocpp.server.OcppCommand;
import io.fullerstack.ocpp.server.OcppCommandExecutor;
import io.fullerstack.ocpp.server.OcppMessage;
import io.humainary.substrates.Circuit;
import io.humainary.substrates.Conduit;
import io.humainary.substrates.ext.serventis.ext.agents.Agents;
import io.humainary.substrates.ext.serventis.ext.agents.Agents.Agent;
import io.humainary.substrates.ext.serventis.ext.counters.Counters;
import io.humainary.substrates.ext.serventis.ext.gauges.Gauges;
import io.humainary.substrates.ext.serventis.ext.monitors.Monitors;
import io.humainary.substrates.ext.serventis.ext.reporters.Reporters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.humainary.substrates.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test for load balancing across multiple chargers.
 * <p>
 * Tests the complete signal flow:
 * MeterValues → Gauges → LoadBalancingReporter → LoadBalancingAgent → SetChargingProfile
 */
@DisplayName("Load Balancing Integration Test")
class LoadBalancingIntegrationTest {

    private static final double GRID_CAPACITY_KW = 100.0;
    private static final double MAX_CHARGER_POWER_KW = 50.0;
    private static final double MIN_CHARGER_POWER_KW = 5.0;

    // Circuits
    private Circuit monitorCircuit;
    private Circuit counterCircuit;
    private Circuit gaugeCircuit;
    private Circuit reporterCircuit;
    private Circuit agentCircuit;

    // Conduits
    private Conduit<Monitors.Monitor, Monitors.Sign> monitors;
    private Conduit<Counters.Counter, Counters.Sign> counters;
    private Conduit<Gauges.Gauge, Gauges.Sign> gauges;
    private Conduit<Reporters.Reporter, Reporters.Signal> reporters;
    private Conduit<Agent, Agents.Signal> agents;

    // Components under test
    private OcppMessageObserver messageObserver;
    private LoadBalancingReporter loadBalancingReporter;
    private LoadBalancingAgent loadBalancingAgent;

    // Test infrastructure
    private List<OcppCommand> executedCommands;
    private OcppCommandExecutor mockExecutor;

    @BeforeEach
    void setUp() {
        // Create circuits
        monitorCircuit = cortex().circuit(cortex().name("test-monitors"));
        monitors = monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);

        counterCircuit = cortex().circuit(cortex().name("test-counters"));
        counters = counterCircuit.conduit(cortex().name("counters"), Counters::composer);

        gaugeCircuit = cortex().circuit(cortex().name("test-gauges"));
        gauges = gaugeCircuit.conduit(cortex().name("gauges"), Gauges::composer);

        reporterCircuit = cortex().circuit(cortex().name("test-reporters"));
        reporters = reporterCircuit.conduit(cortex().name("reporters"), Situations::composer);

        agentCircuit = cortex().circuit(cortex().name("test-agents"));
        agents = agentCircuit.conduit(cortex().name("agents"), Agents::composer);

        // Track executed commands
        executedCommands = new CopyOnWriteArrayList<>();
        mockExecutor = command -> {
            executedCommands.add(command);
            return true;
        };

        // Create components
        messageObserver = new OcppMessageObserver(monitors, counters, gauges);
        loadBalancingReporter = new LoadBalancingReporter(gauges, reporters, GRID_CAPACITY_KW);
        loadBalancingAgent = new LoadBalancingAgent(
            gauges,
            reporters,
            agents,
            mockExecutor,
            GRID_CAPACITY_KW,
            MAX_CHARGER_POWER_KW,
            MIN_CHARGER_POWER_KW
        );
    }

    @AfterEach
    void tearDown() {
        if (loadBalancingAgent != null) {
            loadBalancingAgent.close();
        }
        if (loadBalancingReporter != null) {
            loadBalancingReporter.close();
        }
        if (messageObserver != null) {
            messageObserver.close();
        }
    }

    @Test
    @DisplayName("Should rebalance power when grid capacity exceeded")
    void testLoadBalancingWhenCapacityExceeded() {
        // Given: 5 chargers, each consuming 25kW
        // Total: 125kW > 100kW grid capacity
        String[] chargerIds = {"CP001", "CP002", "CP003", "CP004", "CP005"};
        double powerPerChargerW = 25_000.0;  // 25kW

        // When: Each charger sends MeterValues with 25kW power
        for (String chargerId : chargerIds) {
            sendMeterValues(chargerId, powerPerChargerW);
        }

        // Wait for signals to propagate through circuits
        awaitCircuits();

        // Then: LoadBalancingAgent should rebalance to 20kW each
        // Total: 5 × 20kW = 100kW (within capacity)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            // Should have sent SetChargingProfile to all 5 chargers
            List<OcppCommand.SetChargingProfile> profileCommands = executedCommands.stream()
                .filter(cmd -> cmd instanceof OcppCommand.SetChargingProfile)
                .map(cmd -> (OcppCommand.SetChargingProfile) cmd)
                .toList();

            assertThat(profileCommands).hasSize(5);

            // Each charger should be limited to 20kW (20,000W)
            double expectedPowerLimitW = 20_000.0;
            for (OcppCommand.SetChargingProfile cmd : profileCommands) {
                double limitW = cmd.chargingProfile().chargingSchedule()
                    .chargingSchedulePeriod().get(0).limit();
                assertThat(limitW).isCloseTo(expectedPowerLimitW, org.assertj.core.data.Offset.offset(100.0));
            }
        });
    }

    @Test
    @DisplayName("Should not rebalance when within capacity")
    void testNoRebalancingWhenWithinCapacity() {
        // Given: 4 chargers, each consuming 20kW
        // Total: 80kW < 100kW grid capacity
        String[] chargerIds = {"CP001", "CP002", "CP003", "CP004"};
        double powerPerChargerW = 20_000.0;  // 20kW

        // When: Each charger sends MeterValues with 20kW power
        for (String chargerId : chargerIds) {
            sendMeterValues(chargerId, powerPerChargerW);
        }

        // Wait for signals to propagate
        awaitCircuits();

        // Then: No rebalancing should occur (within capacity)
        await().during(2, TimeUnit.SECONDS).atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OcppCommand.SetChargingProfile> profileCommands = executedCommands.stream()
                .filter(cmd -> cmd instanceof OcppCommand.SetChargingProfile)
                .map(cmd -> (OcppCommand.SetChargingProfile) cmd)
                .toList();

            // No SetChargingProfile commands should be sent (system is stable)
            assertThat(profileCommands).isEmpty();
        });
    }

    @Test
    @DisplayName("Should handle new charger joining when at capacity")
    void testNewChargerJoinsAtCapacity() {
        // Given: 4 chargers at 25kW each (100kW total, at capacity)
        String[] initialChargers = {"CP001", "CP002", "CP003", "CP004"};
        double powerPerChargerW = 25_000.0;  // 25kW

        for (String chargerId : initialChargers) {
            sendMeterValues(chargerId, powerPerChargerW);
        }

        awaitCircuits();

        // When: 5th charger joins and starts charging at 25kW
        sendMeterValues("CP005", powerPerChargerW);

        // Total: 125kW > 100kW capacity → should rebalance

        awaitCircuits();

        // Then: All 5 chargers should be reduced to 20kW
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OcppCommand.SetChargingProfile> profileCommands = executedCommands.stream()
                .filter(cmd -> cmd instanceof OcppCommand.SetChargingProfile)
                .map(cmd -> (OcppCommand.SetChargingProfile) cmd)
                .toList();

            // All 5 chargers should receive new limits
            assertThat(profileCommands).hasSizeGreaterThanOrEqualTo(5);

            // Fair allocation: 100kW / 5 chargers = 20kW each
            double expectedPowerLimitW = 20_000.0;
            for (OcppCommand.SetChargingProfile cmd : profileCommands) {
                double limitW = cmd.chargingProfile().chargingSchedule()
                    .chargingSchedulePeriod().get(0).limit();
                assertThat(limitW).isCloseTo(expectedPowerLimitW, org.assertj.core.data.Offset.offset(100.0));
            }
        });
    }

    @Test
    @DisplayName("Should respect minimum charger power limit")
    void testMinimumPowerLimit() {
        // Given: 25 chargers (extreme case)
        // Fair allocation would be 100kW / 25 = 4kW
        // But minimum is 5kW, so not all can charge simultaneously
        List<String> chargerIds = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            chargerIds.add(String.format("CP%03d", i));
        }

        double powerPerChargerW = 10_000.0;  // 10kW each

        // When: All chargers send MeterValues
        for (String chargerId : chargerIds) {
            sendMeterValues(chargerId, powerPerChargerW);
        }

        awaitCircuits();

        // Then: Each charger should be limited to minimum 5kW
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<OcppCommand.SetChargingProfile> profileCommands = executedCommands.stream()
                .filter(cmd -> cmd instanceof OcppCommand.SetChargingProfile)
                .map(cmd -> (OcppCommand.SetChargingProfile) cmd)
                .toList();

            assertThat(profileCommands).isNotEmpty();

            // No charger should be below minimum (5kW = 5000W)
            for (OcppCommand.SetChargingProfile cmd : profileCommands) {
                double limitW = cmd.chargingProfile().chargingSchedule()
                    .chargingSchedulePeriod().get(0).limit();
                assertThat(limitW).isGreaterThanOrEqualTo(MIN_CHARGER_POWER_KW * 1000.0);
            }
        });
    }

    // =================================================================
    // Helper Methods
    // =================================================================

    /**
     * Simulate a charger sending MeterValues with power consumption.
     */
    private void sendMeterValues(String chargerId, double powerW) {
        OcppMessage.MeterValues meterValues = new OcppMessage.MeterValues(
            chargerId,
            1,  // connectorId
            null,  // transactionId
            Map.of(
                "Power.Active.Import", powerW,
                "Energy.Active.Import.Register", 1000.0,
                "Voltage", 230.0,
                "Current.Import", powerW / 230.0
            ),
            Instant.now()
        );

        messageObserver.handleMessage(meterValues);
    }

    /**
     * Wait for all circuits to process pending signals.
     */
    private void awaitCircuits() {
        monitorCircuit.await();
        counterCircuit.await();
        gaugeCircuit.await();
        reporterCircuit.await();
        agentCircuit.await();

        // Additional small delay for async processing
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
