package io.fullerstack.kafka.core.integration;

import io.fullerstack.kafka.core.reporters.ConsumerHealthReporter;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Reporters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for consumer lag detection and sustained lag growth patterns.
 *
 * <p>Tests the complete signal flow for consumer health monitoring:
 * <pre>
 * Layer 1 (OBSERVE): Monitor emits consumer-specific signs (DIVERGING, DEGRADED)
 *           ↓
 * Layer 3 (DECIDE): ConsumerHealthReporter tracks patterns and assesses urgency
 *           ↓
 *           Reporter emits WARNING/CRITICAL based on lag growth trends
 * </pre>
 *
 * <p>Consumer-specific scenarios tested:
 * <ul>
 *   <li>Single DIVERGING → WARNING (early lag growth detection)</li>
 *   <li>Sustained DIVERGING (3+) → CRITICAL (lag growth trend)</li>
 *   <li>Intermittent DIVERGING → WARNING (not sustained)</li>
 *   <li>Consumer DEGRADED → WARNING (performance degradation)</li>
 *   <li>Consumer DOWN → CRITICAL (consumer unavailable)</li>
 *   <li>Lag recovery (CONVERGING) → NORMAL</li>
 *   <li>Multiple consumers with independent lag patterns</li>
 * </ul>
 *
 * <p>This test validates the unique stateful pattern detection in ConsumerHealthReporter
 * that tracks consecutive DIVERGING signals per consumer.
 *
 * @see ConsumerHealthReporter
 */
@DisplayName("Integration: Consumer Lag Detection and Pattern Tracking")
class ConsumerLagIT {

    private Circuit monitorCircuit;
    private Circuit reporterCircuit;
    private Conduit<Monitors.Monitor, Monitors.Signal> monitors;
    private Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private ConsumerHealthReporter consumerReporter;
    private List<Reporters.Sign> reporterEmissions;

    @BeforeEach
    void setUp() {
        // Layer 1: Create Monitor circuit and conduit
        monitorCircuit = cortex().circuit(cortex().name("monitors"));
        monitors = monitorCircuit.conduit(cortex().name("monitors"), Monitors::composer);

        // Layer 3: Create Reporter circuit, conduit, and consumer reporter
        reporterCircuit = cortex().circuit(cortex().name("reporters"));
        reporters = reporterCircuit.conduit(cortex().name("reporters"), Reporters::composer);
        consumerReporter = new ConsumerHealthReporter(monitors, reporters);

        // Track reporter emissions
        reporterEmissions = new ArrayList<>();
        reporters.subscribe(cortex().subscriber(
            cortex().name("test-observer"),
            (Subject<Channel<Reporters.Sign>> subject, Registrar<Reporters.Sign> registrar) -> {
                registrar.register(reporterEmissions::add);
            }
        ));
    }

    @AfterEach
    void tearDown() {
        if (consumerReporter != null) {
            consumerReporter.close();
        }
        if (reporterCircuit != null) {
            reporterCircuit.close();
        }
        if (monitorCircuit != null) {
            monitorCircuit.close();
        }
    }

    @Test
    @DisplayName("Single DIVERGING emits WARNING (early detection)")
    void testSingleDivergingEmitsWarning() {
        long startTime = System.nanoTime();

        // When: Consumer shows single DIVERGING (lag starting to grow)
        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));
        consumer.diverging(Monitors.Dimension.MEASURED);

        monitorCircuit.await();
        reporterCircuit.await();

        long endTime = System.nanoTime();
        long latencyMs = (endTime - startTime) / 1_000_000;

        // Then: Should emit WARNING for early detection
        assertThat(reporterEmissions)
            .as("Single DIVERGING should emit WARNING for early detection")
            .contains(Reporters.Sign.WARNING);

        assertThat(latencyMs)
            .as("Detection should complete within 100ms")
            .isLessThan(100);
    }

    @Test
    @DisplayName("Sustained lag growth (3+ DIVERGING) emits CRITICAL")
    void testSustainedLagGrowthEmitsCritical() {
        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));

        // When: Consumer shows sustained DIVERGING (3 consecutive)
        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL for sustained lag growth trend
        assertThat(reporterEmissions)
            .as("Sustained DIVERGING (3+) should emit CRITICAL")
            .contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Intermittent DIVERGING resets counter and emits WARNING only")
    void testIntermittentDivergingResetsCounter() {
        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));

        // When: Consumer shows intermittent DIVERGING (not sustained)
        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Interruption: STABLE resets counter
        consumer.stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should only emit WARNING (counter was reset)
        assertThat(reporterEmissions)
            .as("Intermittent DIVERGING should emit WARNING only")
            .contains(Reporters.Sign.WARNING);

        assertThat(reporterEmissions)
            .as("Intermittent pattern should NOT reach CRITICAL")
            .doesNotContain(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Consumer DEGRADED emits WARNING")
    void testConsumerDegradedEmitsWarning() {
        // When: Consumer shows DEGRADED (performance degradation)
        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));
        consumer.degraded(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(reporterEmissions)
            .as("Consumer DEGRADED should emit WARNING")
            .contains(Reporters.Sign.WARNING);
    }

    @Test
    @DisplayName("Consumer DOWN emits CRITICAL immediately")
    void testConsumerDownEmitsCritical() {
        // When: Consumer goes DOWN (unavailable)
        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));
        consumer.down(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL immediately
        assertThat(reporterEmissions)
            .as("Consumer DOWN should emit CRITICAL")
            .contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Consumer DEFECTIVE emits CRITICAL")
    void testConsumerDefectiveEmitsCritical() {
        // When: Consumer is DEFECTIVE (misconfigured)
        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));
        consumer.defective(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(reporterEmissions)
            .as("Consumer DEFECTIVE should emit CRITICAL")
            .contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Lag recovery (CONVERGING) emits NORMAL")
    void testLagRecoveryEmitsNormal() {
        // Given: Initial lag growth
        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));
        consumer.diverging(Monitors.Dimension.MEASURED);

        monitorCircuit.await();
        reporterCircuit.await();

        reporterEmissions.clear(); // Clear initial WARNING

        // When: Consumer shows lag recovery (CONVERGING)
        consumer.converging(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit NORMAL
        assertThat(reporterEmissions)
            .as("Lag recovery should emit NORMAL")
            .contains(Reporters.Sign.NORMAL);
    }

    @Test
    @DisplayName("Multiple consumers tracked independently")
    void testMultipleConsumersTrackedIndependently() {
        Monitors.Monitor consumer1 = monitors.get(cortex().name("consumer-1"));
        Monitors.Monitor consumer2 = monitors.get(cortex().name("consumer-2"));

        // When: Consumer 1 shows sustained DIVERGING
        consumer1.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer1.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer1.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        // And: Consumer 2 shows single DIVERGING
        consumer2.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should see CRITICAL (from consumer1) and WARNING (from consumer2)
        assertThat(reporterEmissions)
            .as("Should emit CRITICAL for consumer1 sustained lag")
            .contains(Reporters.Sign.CRITICAL);

        assertThat(reporterEmissions)
            .as("Should emit WARNING for consumer2 single lag spike")
            .contains(Reporters.Sign.WARNING);
    }

    @Test
    @DisplayName("ERRATIC consumption emits WARNING")
    void testErraticConsumptionEmitsWarning() {
        // When: Consumer shows ERRATIC consumption pattern
        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));
        consumer.erratic(Monitors.Dimension.MEASURED);

        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(reporterEmissions)
            .as("ERRATIC consumption should emit WARNING")
            .contains(Reporters.Sign.WARNING);
    }

    @Test
    @DisplayName("Complete scenario: Lag spike then recovery")
    void testCompleteLagSpikeAndRecoveryScenario() {
        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));

        // Phase 1: Normal operation
        consumer.stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions)
            .as("Phase 1: Normal operation should emit NORMAL")
            .contains(Reporters.Sign.NORMAL);

        reporterEmissions.clear();

        // Phase 2: Single lag spike (early warning)
        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions)
            .as("Phase 2: Single spike should emit WARNING")
            .contains(Reporters.Sign.WARNING);

        reporterEmissions.clear();

        // Phase 3: Sustained lag growth (critical)
        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions)
            .as("Phase 3: Sustained growth (3+ DIVERGING) should emit CRITICAL")
            .contains(Reporters.Sign.CRITICAL);

        reporterEmissions.clear();

        // Phase 4: Recovery starts
        consumer.converging(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions)
            .as("Phase 4: Recovery should emit NORMAL")
            .contains(Reporters.Sign.NORMAL);

        reporterEmissions.clear();

        // Phase 5: Back to stable
        consumer.stable(Monitors.Dimension.CONFIRMED);
        monitorCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions)
            .as("Phase 5: Stable should emit NORMAL")
            .contains(Reporters.Sign.NORMAL);
    }

    @Test
    @DisplayName("Pattern detection latency meets <100ms requirement")
    void testPatternDetectionLatency() {
        long startTime = System.nanoTime();

        Monitors.Monitor consumer = monitors.get(cortex().name("consumer-1"));

        // When: Emit 3 consecutive DIVERGING to trigger pattern detection
        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        consumer.diverging(Monitors.Dimension.MEASURED);
        monitorCircuit.await();
        reporterCircuit.await();

        long endTime = System.nanoTime();
        long latencyMs = (endTime - startTime) / 1_000_000;

        // Then: Pattern detection should complete within 100ms
        assertThat(latencyMs)
            .as("Pattern detection should complete within 100ms")
            .isLessThan(100);

        assertThat(reporterEmissions)
            .as("Should emit CRITICAL after pattern detected")
            .contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Multiple consumers with different lag patterns")
    void testMultipleConsumersDifferentPatterns() {
        // When: Different consumers show different patterns
        monitors.get(cortex().name("consumer-1")).down(Monitors.Dimension.CONFIRMED);
        monitors.get(cortex().name("consumer-2")).degraded(Monitors.Dimension.CONFIRMED);
        monitors.get(cortex().name("consumer-3")).diverging(Monitors.Dimension.MEASURED);
        monitors.get(cortex().name("consumer-4")).stable(Monitors.Dimension.CONFIRMED);

        monitorCircuit.await();
        reporterCircuit.await();

        // Then: Should emit appropriate urgency for each
        assertThat(reporterEmissions)
            .as("Should emit CRITICAL for DOWN consumer")
            .contains(Reporters.Sign.CRITICAL);

        assertThat(reporterEmissions)
            .as("Should emit WARNING for DEGRADED consumer")
            .contains(Reporters.Sign.WARNING);

        assertThat(reporterEmissions)
            .as("Should emit NORMAL for STABLE consumer")
            .contains(Reporters.Sign.NORMAL);
    }
}
