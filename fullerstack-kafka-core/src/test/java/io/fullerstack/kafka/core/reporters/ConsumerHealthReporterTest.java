package io.fullerstack.kafka.core.reporters;

import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConsumerHealthReporter}.
 *
 * <p>Tests verify:
 * <ul>
 *   <li>CRITICAL situation detection (DOWN, DEFECTIVE, sustained lag growth)</li>
 *   <li>WARNING situation detection (DEGRADED, ERRATIC, single DIVERGING)</li>
 *   <li>NORMAL situation (STABLE, CONVERGING)</li>
 *   <li>Sustained DIVERGING pattern detection (3+ consecutive)</li>
 *   <li>Event-driven synchronization (circuit.await())</li>
 * </ul>
 */
class ConsumerHealthReporterTest {

    private Circuit monitorCircuit;
    private Circuit reporterCircuit;
    private Conduit<Monitors.Monitor, Monitors.Signal> monitors;
    private Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private ConsumerHealthReporter reporter;
    private List<Reporters.Sign> emittedSigns;

    @BeforeEach
    void setUp() {
        // Create monitor circuit and conduit
        monitorCircuit = cortex().circuit(cortex().name("test-monitors"));
        monitors = monitorCircuit.conduit(
            cortex().name("consumer-monitors"),
            Monitors::composer
        );

        // Create reporter circuit and conduit
        reporterCircuit = cortex().circuit(cortex().name("test-reporters"));
        reporters = reporterCircuit.conduit(
            cortex().name("reporters"),
            Reporters::composer
        );

        // Create reporter
        reporter = new ConsumerHealthReporter(monitors, reporters);

        // Track emitted reporter signs
        emittedSigns = new ArrayList<>();
        reporters.subscribe(cortex().subscriber(
            cortex().name("test-collector"),
            (subject, registrar) -> registrar.register(sign -> emittedSigns.add(sign))
        ));
    }

    @AfterEach
    void tearDown() {
        if (reporter != null) {
            reporter.close();
        }
        if (reporterCircuit != null) {
            reporterCircuit.close();
        }
        if (monitorCircuit != null) {
            monitorCircuit.close();
        }
    }

    @Test
    void testConsumerDownEmitsCritical() {
        // Given: Consumer monitor
        Monitors.Monitor consumerMonitor = monitors.percept(cortex().name("order-processor"));

        // When: Consumer goes DOWN
        consumerMonitor.down(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(emittedSigns).contains(Reporters.Sign.CRITICAL);
    }

    @Test
    void testConsumerDefectiveEmitsCritical() {
        // Given: Consumer monitor
        Monitors.Monitor consumerMonitor = monitors.percept(cortex().name("payment-processor"));

        // When: Consumer becomes DEFECTIVE
        consumerMonitor.defective(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(emittedSigns).contains(Reporters.Sign.CRITICAL);
    }

    @Test
    void testSustainedLagGrowthEmitsCritical() {
        // Given: Consumer monitor
        Monitors.Monitor lagMonitor = monitors.percept(cortex().name("analytics-consumer.lag"));

        // When: Consumer shows sustained lag growth (3 consecutive DIVERGING)
        emittedSigns.clear();

        // First DIVERGING → WARNING
        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();
        assertThat(emittedSigns).containsExactly(Reporters.Sign.WARNING);

        // Second DIVERGING → WARNING
        emittedSigns.clear();
        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();
        assertThat(emittedSigns).containsExactly(Reporters.Sign.WARNING);

        // Third DIVERGING → CRITICAL (sustained pattern)
        emittedSigns.clear();
        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();
        assertThat(emittedSigns).containsExactly(Reporters.Sign.CRITICAL);

        // Fourth DIVERGING → Still CRITICAL
        emittedSigns.clear();
        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();
        assertThat(emittedSigns).containsExactly(Reporters.Sign.CRITICAL);
    }

    @Test
    void testDivergingCountResetsOnOtherSign() {
        // Given: Consumer monitor with 2 consecutive DIVERGING
        Monitors.Monitor lagMonitor = monitors.percept(cortex().name("inventory-consumer.lag"));

        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        emittedSigns.clear();

        // When: Consumer emits STABLE (resets counter)
        lagMonitor.stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Counter should reset
        emittedSigns.clear();

        // When: DIVERGING again (count=1, not 3)
        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING (not CRITICAL)
        assertThat(emittedSigns).containsExactly(Reporters.Sign.WARNING);
    }

    @Test
    void testConsumerDegradedEmitsWarning() {
        // Given: Consumer monitor
        Monitors.Monitor consumerMonitor = monitors.percept(cortex().name("notification-consumer"));

        // When: Consumer becomes DEGRADED
        consumerMonitor.degraded(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(emittedSigns).contains(Reporters.Sign.WARNING);
    }

    @Test
    void testErraticConsumptionEmitsWarning() {
        // Given: Consumer monitor
        Monitors.Monitor consumerMonitor = monitors.percept(cortex().name("audit-consumer"));

        // When: Consumer shows ERRATIC consumption
        consumerMonitor.erratic(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(emittedSigns).contains(Reporters.Sign.WARNING);
    }

    @Test
    void testSingleDivergingEmitsWarning() {
        // Given: Consumer lag monitor
        Monitors.Monitor lagMonitor = monitors.percept(cortex().name("reporting-consumer.lag"));

        // When: Lag shows single DIVERGING (early detection)
        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING (not CRITICAL yet)
        assertThat(emittedSigns).containsExactly(Reporters.Sign.WARNING);
    }

    @Test
    void testStableConsumerEmitsNormal() {
        // Given: Consumer monitor
        Monitors.Monitor consumerMonitor = monitors.percept(cortex().name("email-consumer"));

        // When: Consumer is STABLE
        consumerMonitor.stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit NORMAL
        assertThat(emittedSigns).contains(Reporters.Sign.NORMAL);
    }

    @Test
    void testConvergingLagEmitsNormal() {
        // Given: Consumer lag monitor
        Monitors.Monitor lagMonitor = monitors.percept(cortex().name("search-consumer.lag"));

        // When: Lag is CONVERGING (reducing)
        lagMonitor.converging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit NORMAL (healthy recovery)
        assertThat(emittedSigns).contains(Reporters.Sign.NORMAL);
    }

    @Test
    void testMultipleConsumersTrackedIndependently() {
        // Given: Two consumer monitors
        Monitors.Monitor consumer1 = monitors.percept(cortex().name("consumer-1"));
        Monitors.Monitor consumer2 = monitors.percept(cortex().name("consumer-2"));

        // When: Consumer1 has 2 DIVERGING, Consumer2 has 2 DIVERGING
        consumer1.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer1.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer2.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer2.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        emittedSigns.clear();

        // When: Both emit 3rd DIVERGING
        consumer1.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer2.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Both should emit CRITICAL (independent tracking)
        assertThat(emittedSigns)
            .hasSize(2)
            .containsOnly(Reporters.Sign.CRITICAL);
    }

    @Test
    void testClearTrackingResetsCounter() {
        // Given: Consumer with 2 consecutive DIVERGING
        Monitors.Monitor lagMonitor = monitors.percept(cortex().name("test-consumer"));

        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // When: Clear tracking
        reporter.clearTracking("test-consumer");
        emittedSigns.clear();

        // When: DIVERGING again (count should be 1, not 3)
        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING (not CRITICAL)
        assertThat(emittedSigns).containsExactly(Reporters.Sign.WARNING);
    }

    @Test
    void testClearAllTrackingResetsAllCounters() {
        // Given: Multiple consumers with DIVERGING counts
        Monitors.Monitor consumer1 = monitors.percept(cortex().name("consumer-1"));
        Monitors.Monitor consumer2 = monitors.percept(cortex().name("consumer-2"));

        consumer1.diverging(Monitors.Dimension.CONFIRMED);
        consumer2.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer1.diverging(Monitors.Dimension.CONFIRMED);
        consumer2.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // When: Clear all tracking
        reporter.clearAllTracking();
        emittedSigns.clear();

        // When: Both emit DIVERGING again
        consumer1.diverging(Monitors.Dimension.CONFIRMED);
        consumer2.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Both should emit WARNING (counts reset to 1)
        assertThat(emittedSigns)
            .hasSize(2)
            .containsOnly(Reporters.Sign.WARNING);
    }

    @Test
    void testEventDrivenSynchronization() {
        // Given: Consumer monitor
        Monitors.Monitor consumerMonitor = monitors.percept(cortex().name("sync-test-consumer"));

        long startTime = System.nanoTime();

        // When: Emit signal and use event-driven await (NO Thread.sleep!)
        consumerMonitor.degraded(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        long duration = System.nanoTime() - startTime;

        // Then: Should complete quickly (sub-millisecond, not sleep-based latency)
        assertThat(emittedSigns).contains(Reporters.Sign.WARNING);

        // Verify sub-10ms latency (event-driven, not polling)
        assertThat(duration).isLessThan(10_000_000L); // 10ms in nanoseconds
    }

    @Test
    void testCompleteScenario_LagSpikeThenRecovery() {
        // Given: Consumer lag monitor
        Monitors.Monitor lagMonitor = monitors.percept(cortex().name("complete-scenario-consumer"));

        // Scenario: Lag spike → recovery
        emittedSigns.clear();

        // 1. STABLE → NORMAL
        lagMonitor.stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();
        assertThat(emittedSigns.get(emittedSigns.size() - 1)).isEqualTo(Reporters.Sign.NORMAL);

        // 2. DIVERGING (lag growing) → WARNING
        lagMonitor.diverging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();
        assertThat(emittedSigns.get(emittedSigns.size() - 1)).isEqualTo(Reporters.Sign.WARNING);

        // 3. DEGRADED (performance issue) → WARNING
        lagMonitor.degraded(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();
        assertThat(emittedSigns.get(emittedSigns.size() - 1)).isEqualTo(Reporters.Sign.WARNING);

        // 4. CONVERGING (lag reducing) → NORMAL
        lagMonitor.converging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();
        assertThat(emittedSigns.get(emittedSigns.size() - 1)).isEqualTo(Reporters.Sign.NORMAL);

        // 5. STABLE (recovered) → NORMAL
        lagMonitor.stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();
        assertThat(emittedSigns.get(emittedSigns.size() - 1)).isEqualTo(Reporters.Sign.NORMAL);

        // Verify complete flow
        assertThat(emittedSigns).hasSize(5);
    }
}
