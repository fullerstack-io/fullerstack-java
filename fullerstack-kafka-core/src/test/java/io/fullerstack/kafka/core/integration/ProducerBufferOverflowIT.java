package io.fullerstack.kafka.core.integration;

import io.fullerstack.kafka.core.reporters.ProducerHealthReporter;
import io.humainary.substrates.api.Substrates.*;
import io.humainary.substrates.ext.serventis.ext.Monitors;
import io.humainary.substrates.ext.serventis.ext.Situations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.humainary.substrates.api.Substrates.cortex;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for producer buffer overflow detection and reporting.
 *
 * <p>Tests the complete signal flow for producer health monitoring:
 * <pre>
 * Layer 1 (OBSERVE): Monitor emits producer-specific signs (DIVERGING, DEGRADED)
 *           ↓
 * Layer 2 (ORIENT): Producer Cell hierarchy aggregates
 *           ↓
 * Layer 3 (DECIDE): ProducerHealthReporter assesses urgency
 *           ↓
 *           Situation emits WARNING/CRITICAL based on buffer pressure
 * </pre>
 *
 * <p>Producer-specific scenarios tested:
 * <ul>
 *   <li>Single producer buffer overflow → WARNING</li>
 *   <li>Sustained buffer pressure → CRITICAL</li>
 *   <li>Multiple producers with buffer issues → Aggregated urgency</li>
 *   <li>Buffer recovery → NORMAL</li>
 *   <li>Intermittent buffer spikes → WARNING (not CRITICAL)</li>
 * </ul>
 *
 * @see ProducerHealthReporter
 */
@DisplayName("Integration: Producer Buffer Overflow Detection")
class ProducerBufferOverflowIT {

    private Circuit producerCircuit;
    private Circuit reporterCircuit;
    private Cell<Monitors.Sign, Monitors.Sign> producerRootCell;
    private Conduit<Situations.Situation, Situations.Signal> reporters;
    private ProducerHealthReporter producerReporter;
    private List<Situations.Signal> reporterEmissions;

    @BeforeEach
    void setUp() {
        // Layer 2: Create producer root cell circuit
        producerCircuit = cortex().circuit(cortex().name("producers"));
        producerRootCell = producerCircuit.cell(
            cortex().name("producer-root"),
            Composer.pipe(),  // Identity ingress
            Composer.pipe(),  // Identity egress
            cortex().pipe((Monitors.Sign sign) -> {})  // No-op outlet
        );

        // Layer 3: Create Situation circuit, conduit, and producer reporter
        reporterCircuit = cortex().circuit(cortex().name("reporters"));
        reporters = reporterCircuit.conduit(cortex().name("reporters"), Situations::composer);
        producerReporter = new ProducerHealthReporter(producerRootCell, reporters);

        // Track reporter emissions
        reporterEmissions = new ArrayList<>();
        reporters.subscribe(cortex().subscriber(
            cortex().name("test-receptor"),
            (Subject<Channel<Situations.Signal>> subject, Registrar<Situations.Signal> registrar) -> {
                registrar.register(reporterEmissions::add);
            }
        ));
    }

    @AfterEach
    void tearDown() {
        if (producerReporter != null) {
            producerReporter.close();
        }
        if (reporterCircuit != null) {
            reporterCircuit.close();
        }
        if (producerCircuit != null) {
            producerCircuit.close();
        }
    }

    @Test
    @DisplayName("Single producer buffer overflow emits WARNING")
    void testSingleProducerBufferOverflow() {
        long startTime = System.nanoTime();

        // When: Producer buffer shows DIVERGING (growing backpressure)
        producerRootCell.emit(Monitors.Sign.DIVERGING);

        producerCircuit.await();
        reporterCircuit.await();

        long endTime = System.nanoTime();
        long latencyMs = (endTime - startTime) / 1_000_000;

        // Then: Should emit WARNING for buffer pressure
        assertThat(reporterEmissions)
            .as("Producer buffer overflow should emit WARNING")
            .contains(Situations.Sign.WARNING);

        assertThat(latencyMs)
            .as("Detection should complete within 100ms")
            .isLessThan(100);
    }

    @Test
    @DisplayName("Sustained buffer pressure emits CRITICAL")
    void testSustainedBufferPressure() {
        // When: Producer shows sustained DEGRADED (can't keep up)
        producerRootCell.emit(Monitors.Sign.DEGRADED);

        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL for sustained issue
        assertThat(reporterEmissions)
            .as("Sustained buffer pressure should emit CRITICAL")
            .contains(Situations.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Producer DOWN emits CRITICAL immediately")
    void testProducerDown() {
        // When: Producer goes DOWN (cannot produce)
        producerRootCell.emit(Monitors.Sign.DOWN);

        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL immediately
        assertThat(reporterEmissions)
            .as("Producer DOWN should emit CRITICAL")
            .contains(Situations.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Multiple producers aggregate to worst-case urgency")
    void testMultipleProducersBufferIssues() {
        // When: Multiple producers show buffer pressure (aggregated to DEGRADED)
        producerRootCell.emit(Monitors.Sign.DEGRADED);

        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL (worst-case aggregation)
        assertThat(reporterEmissions)
            .as("Multiple producer issues should aggregate to worst-case CRITICAL")
            .contains(Situations.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Buffer recovery emits NORMAL")
    void testBufferRecovery() {
        // Given: Initial buffer pressure
        producerRootCell.emit(Monitors.Sign.DIVERGING);

        producerCircuit.await();
        reporterCircuit.await();

        reporterEmissions.clear(); // Clear initial WARNING

        // When: Producer recovers (buffer draining)
        producerRootCell.emit(Monitors.Sign.CONVERGING);

        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit NORMAL
        assertThat(reporterEmissions)
            .as("Buffer recovery should emit NORMAL")
            .contains(Situations.Sign.NORMAL);
    }

    @Test
    @DisplayName("Intermittent buffer spikes emit WARNING not CRITICAL")
    void testIntermittentBufferSpikes() {
        // When: Producer shows intermittent ERRATIC behavior
        producerRootCell.emit(Monitors.Sign.ERRATIC);

        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING (not CRITICAL)
        assertThat(reporterEmissions)
            .as("Intermittent spikes should emit WARNING")
            .contains(Situations.Sign.WARNING);

        assertThat(reporterEmissions)
            .as("Intermittent spikes should NOT emit CRITICAL")
            .doesNotContain(Situations.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Producer DEFECTIVE emits CRITICAL")
    void testProducerDefective() {
        // When: Producer is DEFECTIVE (misconfigured/broken)
        producerRootCell.emit(Monitors.Sign.DEFECTIVE);

        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(reporterEmissions)
            .as("Producer DEFECTIVE should emit CRITICAL")
            .contains(Situations.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Complete scenario: Buffer overflow progression")
    void testCompleteBufferOverflowProgression() {
        // Phase 1: Normal operation
        producerRootCell.emit(Monitors.Sign.STABLE);
        producerCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions)
            .as("Phase 1: Normal operation should emit NORMAL")
            .contains(Situations.Sign.NORMAL);

        reporterEmissions.clear();

        // Phase 2: Buffer starts growing (early warning)
        producerRootCell.emit(Monitors.Sign.DIVERGING);
        producerCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions)
            .as("Phase 2: Buffer growth should emit WARNING")
            .contains(Situations.Sign.WARNING);

        reporterEmissions.clear();

        // Phase 3: Buffer overflow sustained (critical)
        producerRootCell.emit(Monitors.Sign.DEGRADED);
        producerCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions)
            .as("Phase 3: Sustained overflow should emit CRITICAL")
            .contains(Situations.Sign.CRITICAL);

        reporterEmissions.clear();

        // Phase 4: Recovery
        producerRootCell.emit(Monitors.Sign.CONVERGING);
        producerCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions)
            .as("Phase 4: Recovery should emit NORMAL")
            .contains(Situations.Sign.NORMAL);
    }

    @Test
    @DisplayName("Latency meets <100ms requirement for all scenarios")
    void testLatencyRequirement() {
        long startTime = System.nanoTime();

        // When: Emit worst-case signal (DEGRADED)
        producerRootCell.emit(Monitors.Sign.DEGRADED);

        producerCircuit.await();
        reporterCircuit.await();

        long endTime = System.nanoTime();
        long latencyMs = (endTime - startTime) / 1_000_000;

        // Then: All processing should complete within 100ms
        assertThat(latencyMs)
            .as("Producer signal processing should complete within 100ms")
            .isLessThan(100);

        assertThat(reporterEmissions)
            .as("Should emit CRITICAL signal")
            .contains(Situations.Sign.CRITICAL);
    }
}
