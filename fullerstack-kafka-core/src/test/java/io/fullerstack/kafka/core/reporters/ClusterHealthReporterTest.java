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
 * Unit tests for {@link ClusterHealthReporter}.
 *
 * <p>Verifies urgency mapping from Monitor Signs to Reporter Signs:
 * <ul>
 *   <li>DEGRADED/DOWN/DEFECTIVE → CRITICAL</li>
 *   <li>ERRATIC/DIVERGING → WARNING</li>
 *   <li>STABLE/CONVERGING → NORMAL</li>
 * </ul>
 */
@DisplayName("ClusterHealthReporter")
class ClusterHealthReporterTest {

    private Circuit clusterCircuit;
    private Circuit reporterCircuit;
    private Cell<Monitors.Sign, Monitors.Sign> clusterCell;
    private Conduit<Reporters.Reporter, Reporters.Sign> reporters;
    private ClusterHealthReporter clusterReporter;
    private List<Reporters.Sign> reporterEmissions;

    @BeforeEach
    void setUp() {
        // Create cluster cell circuit
        clusterCircuit = cortex().circuit(cortex().name("test-cluster"));
        clusterCell = clusterCircuit.cell(
            cortex().name("cluster"),
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
        clusterReporter = new ClusterHealthReporter(clusterCell, reporters);

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
        if (clusterReporter != null) {
            clusterReporter.close();
        }
        if (reporterCircuit != null) {
            reporterCircuit.close();
        }
        if (clusterCircuit != null) {
            clusterCircuit.close();
        }
    }

    @Test
    @DisplayName("DEGRADED sign emits CRITICAL urgency")
    void degradedSignEmitsCritical() {
        // When: Cluster cell emits DEGRADED
        clusterCell.emit(Monitors.Sign.DEGRADED);
        clusterCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("DOWN sign emits CRITICAL urgency")
    void downSignEmitsCritical() {
        // When: Cluster cell emits DOWN
        clusterCell.emit(Monitors.Sign.DOWN);
        clusterCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("DEFECTIVE sign emits CRITICAL urgency")
    void defectiveSignEmitsCritical() {
        // When: Cluster cell emits DEFECTIVE
        clusterCell.emit(Monitors.Sign.DEFECTIVE);
        clusterCircuit.await();
        reporterCircuit.await();

        // Then: Should emit CRITICAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.CRITICAL);
    }

    @Test
    @DisplayName("ERRATIC sign emits WARNING urgency")
    void erraticSignEmitsWarning() {
        // When: Cluster cell emits ERRATIC
        clusterCell.emit(Monitors.Sign.ERRATIC);
        clusterCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.WARNING);
    }

    @Test
    @DisplayName("DIVERGING sign emits WARNING urgency")
    void divergingSignEmitsWarning() {
        // When: Cluster cell emits DIVERGING
        clusterCell.emit(Monitors.Sign.DIVERGING);
        clusterCircuit.await();
        reporterCircuit.await();

        // Then: Should emit WARNING
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.WARNING);
    }

    @Test
    @DisplayName("STABLE sign emits NORMAL urgency")
    void stableSignEmitsNormal() {
        // When: Cluster cell emits STABLE
        clusterCell.emit(Monitors.Sign.STABLE);
        clusterCircuit.await();
        reporterCircuit.await();

        // Then: Should emit NORMAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.NORMAL);
    }

    @Test
    @DisplayName("CONVERGING sign emits NORMAL urgency")
    void convergingSignEmitsNormal() {
        // When: Cluster cell emits CONVERGING
        clusterCell.emit(Monitors.Sign.CONVERGING);
        clusterCircuit.await();
        reporterCircuit.await();

        // Then: Should emit NORMAL
        assertThat(reporterEmissions).containsExactly(Reporters.Sign.NORMAL);
    }

    @Test
    @DisplayName("Multiple signs emit corresponding urgencies")
    void multipleSignsEmitCorrespondingUrgencies() {
        // When: Cluster emits sequence of signs
        clusterCell.emit(Monitors.Sign.STABLE);      // NORMAL
        clusterCell.emit(Monitors.Sign.DIVERGING);   // WARNING
        clusterCell.emit(Monitors.Sign.DEGRADED);    // CRITICAL
        clusterCircuit.await();
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
        // When: System degrades over time
        clusterCell.emit(Monitors.Sign.STABLE);     // All good
        clusterCircuit.await();
        reporterCircuit.await();

        clusterCell.emit(Monitors.Sign.DIVERGING);  // Early warning
        clusterCircuit.await();
        reporterCircuit.await();

        clusterCell.emit(Monitors.Sign.DEGRADED);   // Critical state
        clusterCircuit.await();
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
        // When: System recovers over time
        clusterCell.emit(Monitors.Sign.DEGRADED);   // Critical state
        clusterCircuit.await();
        reporterCircuit.await();

        clusterCell.emit(Monitors.Sign.ERRATIC);    // Recovering
        clusterCircuit.await();
        reporterCircuit.await();

        clusterCell.emit(Monitors.Sign.CONVERGING); // Back to normal
        clusterCircuit.await();
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
        clusterCell.emit(Monitors.Sign.STABLE);
        clusterCircuit.await();
        reporterCircuit.await();

        assertThat(reporterEmissions).hasSize(1);

        // When: Reporter is closed
        clusterReporter.close();

        // Then: No exceptions thrown and subscriptions are cleaned up
        assertThat(reporterEmissions).hasSize(1);  // No new emissions after close
    }

    @Test
    @DisplayName("All Monitor Signs map to valid Reporter Signs")
    void allMonitorSignsMapToValidReporterSigns() {
        // When: Emit all possible Monitor Signs
        for (Monitors.Sign sign : Monitors.Sign.values()) {
            clusterCell.emit(sign);
        }
        clusterCircuit.await();
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
}
