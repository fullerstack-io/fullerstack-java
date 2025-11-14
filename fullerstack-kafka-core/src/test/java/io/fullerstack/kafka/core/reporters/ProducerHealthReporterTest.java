package io.fullerstack.kafka.core.reporters;

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
 * Unit tests for {@link ProducerHealthReporter}.
 *
 * <p>Verifies urgency mapping from Monitor Signs to Reporter Signs for producer health:
 * <ul>
 *   <li>DEGRADED/DOWN/DEFECTIVE → CRITICAL (buffer overflow, producer failure)</li>
 *   <li>ERRATIC/DIVERGING → WARNING (buffer pressure, latency increasing)</li>
 *   <li>STABLE/CONVERGING → NORMAL (healthy operation)</li>
 * </ul>
 */
@DisplayName("ProducerHealthReporter")
class ProducerHealthReporterTest {

    private Circuit producerCircuit;
    private Circuit reporterCircuit;
    private Cell<Monitors.Sign, Monitors.Sign> producerRootCell;
    private Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private ProducerHealthReporter producerReporter;
    private List<Reporters.Sign> reporterEmissions;

    @BeforeEach
    void setUp() {
        // Create producer root cell circuit
        producerCircuit = cortex().circuit(cortex().name("test-producers"));
        producerRootCell = producerCircuit.cell(
            cortex().name("producer-root"),
            Composer.pipe(),  // Identity ingress
            Composer.pipe(),  // Identity egress
            cortex().pipe((Monitors.Sign sign) -> {})  // No-op outlet
        );

        // Create reporter conduit circuit
        reporterCircuit = cortex().circuit(cortex().name("reporters"));
        reporters = reporterCircuit.conduit(
            cortex().name("reporters"),
            Reporters::composer
        );

        // Create reporter
        producerReporter = new ProducerHealthReporter(producerRootCell, reporters);

        // Track reporter emissions
        reporterEmissions = new ArrayList<>();
        reporters.subscribe(cortex().subscriber(
            cortex().name("test-receptor"),
            (Subject<Channel<Reporters.Sign>> subject, Registrar<Reporters.Sign> registrar) -> {
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
    @DisplayName("DEGRADED sign emits CRITICAL urgency (buffer overflow)")
    void degradedSignEmitsCritical() {
        // When: Producer root cell emits DEGRADED (buffer overflow detected)
        producerRootCell.emit(Monitors.Sign.DEGRADED);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("DOWN sign emits CRITICAL urgency (producer failure)")
    void downSignEmitsCritical() {
        // When: Producer root cell emits DOWN (producer completely failed)
        producerRootCell.emit(Monitors.Sign.DOWN);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("DEFECTIVE sign emits CRITICAL urgency")
    void defectiveSignEmitsCritical() {
        // When: Producer root cell emits DEFECTIVE
        producerRootCell.emit(Monitors.Sign.DEFECTIVE);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("ERRATIC sign emits WARNING urgency (buffer pressure)")
    void erraticSignEmitsWarning() {
        // When: Producer root cell emits ERRATIC (buffer pressure building)
        producerRootCell.emit(Monitors.Sign.ERRATIC);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.WARNING);
    }

    @Test
    @DisplayName("DIVERGING sign emits WARNING urgency (latency increasing)")
    void divergingSignEmitsWarning() {
        // When: Producer root cell emits DIVERGING (send latency trending up)
        producerRootCell.emit(Monitors.Sign.DIVERGING);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.WARNING);
    }

    @Test
    @DisplayName("STABLE sign emits NORMAL urgency")
    void stableSignEmitsNormal() {
        // When: Producer root cell emits STABLE (healthy operation)
        producerRootCell.emit(Monitors.Sign.STABLE);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit NORMAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.NORMAL);
    }

    @Test
    @DisplayName("CONVERGING sign emits NORMAL urgency")
    void convergingSignEmitsNormal() {
        // When: Producer root cell emits CONVERGING (recovering to normal)
        producerRootCell.emit(Monitors.Sign.CONVERGING);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit NORMAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.NORMAL);
    }

    @Test
    @DisplayName("Multiple signs emit corresponding urgencies")
    void multipleSignsEmitCorrespondingUrgencies() {
        // When: Producer emits sequence of signs
        producerRootCell.emit(Monitors.Sign.STABLE);      // NORMAL
        producerRootCell.emit(Monitors.Sign.DIVERGING);   // WARNING
        producerRootCell.emit(Monitors.Sign.DEGRADED);    // CRITICAL
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit corresponding urgencies in order
        assertThat(reporterEmissions).containsExactly(
            Reporters.Sign.NORMAL,
            Reporters.Sign.WARNING,
            Reporters.Sign.CRITICAL
        );
    }

    @Test
    @DisplayName("Escalation path: NORMAL → WARNING → CRITICAL")
    void escalationPath() {
        // When: Producer degrades over time
        producerRootCell.emit(Monitors.Sign.STABLE);     // All good
        producerCircuit.await();
        reporterCircuit.await();

        producerRootCell.emit(Monitors.Sign.DIVERGING);  // Buffer filling
        producerCircuit.await();
        reporterCircuit.await();

        producerRootCell.emit(Monitors.Sign.DEGRADED);   // Buffer overflow
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should follow escalation path
        assertThat(reporterEmissions).containsExactly(
            Reporters.Sign.NORMAL,
            Reporters.Sign.WARNING,
            Reporters.Sign.CRITICAL
        );
    }

    @Test
    @DisplayName("De-escalation path: CRITICAL → WARNING → NORMAL")
    void deEscalationPath() {
        // When: Producer recovers over time
        producerRootCell.emit(Monitors.Sign.DEGRADED);   // Buffer overflow
        producerCircuit.await();
        reporterCircuit.await();

        producerRootCell.emit(Monitors.Sign.ERRATIC);    // Buffer draining
        producerCircuit.await();
        reporterCircuit.await();

        producerRootCell.emit(Monitors.Sign.CONVERGING); // Back to normal
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should follow de-escalation path
        assertThat(reporterEmissions).containsExactly(
            Reporters.Sign.CRITICAL,
            Reporters.Sign.WARNING,
            Reporters.Sign.NORMAL
        );
    }

    @Test
    @DisplayName("Reporter can be closed safely")
    void reporterCanBeClosedSafely() {
        // Given: Reporter is active
        producerRootCell.emit(Monitors.Sign.STABLE);
        producerCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions).hasSize(1);

        // When: Reporter is closed
        producerReporter.close();

        // Then: No exceptions thrown and subscriptions are cleaned up
        assertThat(reporterEmissions).hasSize(1);  // No new emissions after close
    }

    @Test
    @DisplayName("All Monitor Signs map to valid Reporter Signs")
    void allMonitorSignsMapToValidReporterSigns() {
        // When: Emit all possible Monitor Signs
        for (Monitors.Sign sign : Monitors.Sign.values()) {
            producerRootCell.emit(sign);
        }
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit exactly 7 Reporter Signs (one per Monitor Sign)
        assertThat(reporterEmissions).hasSize(7);

        // Verify all are valid Reporter Signs
        for (Reporters.Sign reporterSign : reporterEmissions) {
            assertThat(reporterSign).isIn(
                Reporters.Sign.NORMAL,
                Reporters.Sign.WARNING,
                Reporters.Sign.CRITICAL
            );
        }

        // Verify mapping correctness
        assertThat(reporterEmissions).contains(
            Reporters.Sign.CRITICAL,  // DOWN, DEFECTIVE, DEGRADED
            Reporters.Sign.WARNING,   // ERRATIC, DIVERGING
            Reporters.Sign.NORMAL     // STABLE, CONVERGING
        );
    }

    @Test
    @DisplayName("Producer hierarchy with multiple children - worst case aggregation")
    void producerHierarchyWithMultipleChildren() {
        // Given: 3 producer children
        Cell<Monitors.Sign, Monitors.Sign> producer1 = producerRootCell.get(cortex().name("producer-1"));
        Cell<Monitors.Sign, Monitors.Sign> producer2 = producerRootCell.get(cortex().name("producer-2"));
        Cell<Monitors.Sign, Monitors.Sign> producer3 = producerRootCell.get(cortex().name("producer-3"));

        // When: Emit mixed signs (STABLE, DEGRADED, STABLE)
        producer1.emit(Monitors.Sign.STABLE);
        producer2.emit(Monitors.Sign.DEGRADED);  // Buffer overflow on producer-2
        producer3.emit(Monitors.Sign.STABLE);

        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should aggregate to worst-case (DEGRADED → CRITICAL)
        // NOTE: This assumes identity composers propagate all child emissions
        // The reporter receives DEGRADED from producer-2
        assertThat(reporterEmissions).contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Producer buffer overflow scenario")
    void producerBufferOverflowScenario() {
        // Given: Producer operating normally
        producerRootCell.emit(Monitors.Sign.STABLE);
        producerCircuit.await();
        reporterCircuit.await();

        reporterEmissions.clear();

        // When: Buffer starts filling (diverging from normal)
        producerRootCell.emit(Monitors.Sign.DIVERGING);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(reporterEmissions).contains(Reporters.Sign.WARNING);

        reporterEmissions.clear();

        // When: Buffer overflows (critical state)
        producerRootCell.emit(Monitors.Sign.DEGRADED);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should escalate to CRITICAL
        assertThat(reporterEmissions).contains(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("Producer recovery scenario")
    void producerRecoveryScenario() {
        // Given: Producer in critical state (buffer overflow)
        producerRootCell.emit(Monitors.Sign.DEGRADED);
        producerCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions).contains(Reporters.Sign.CRITICAL);
        reporterEmissions.clear();

        // When: Producer starts recovering (buffer draining)
        producerRootCell.emit(Monitors.Sign.CONVERGING);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should de-escalate to NORMAL
        assertThat(reporterEmissions).contains(Reporters.Sign.NORMAL);
    }

    @Test
    @DisplayName("Intermittent issues pattern")
    void intermittentIssuesPattern() {
        // When: Producer alternates between stable and erratic
        producerRootCell.emit(Monitors.Sign.STABLE);
        producerRootCell.emit(Monitors.Sign.ERRATIC);
        producerRootCell.emit(Monitors.Sign.STABLE);
        producerRootCell.emit(Monitors.Sign.ERRATIC);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit NORMAL-WARNING-NORMAL-WARNING pattern
        assertThat(reporterEmissions).containsExactly(
            Reporters.Sign.NORMAL,
            Reporters.Sign.WARNING,
            Reporters.Sign.NORMAL,
            Reporters.Sign.WARNING
        );
    }

    @Test
    @DisplayName("Sustained degradation")
    void sustainedDegradation() {
        // When: Producer remains degraded for extended period
        producerRootCell.emit(Monitors.Sign.DEGRADED);
        producerRootCell.emit(Monitors.Sign.DEGRADED);
        producerRootCell.emit(Monitors.Sign.DEGRADED);
        producerCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL for each DEGRADED signal
        assertThat(reporterEmissions).containsExactly(
            Reporters.Sign.CRITICAL,
            Reporters.Sign.CRITICAL,
            Reporters.Sign.CRITICAL
        );
    }
}
