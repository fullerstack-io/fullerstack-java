package io.fullerstack.substrates.integration;

import io.fullerstack.substrates.CortexRuntime;
import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests demonstrating hierarchical naming.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Type-based defaults are used ("circuit", "conduit", "channel" instead of "default")</li>
 *   <li>Names are built hierarchically (circuit.conduit.channel)</li>
 *   <li>Subject paths reflect the full hierarchy</li>
 * </ul>
 */
class HierarchicalNamingTest {
    private Cortex cortex;
    private Circuit circuit;

    @AfterEach
    void cleanup() {
        if (circuit != null) {
            circuit.close();
        }
    }

    @Test
    void shouldUseTypeBasedDefaultNames() {
        cortex = new CortexRuntime();

        // Default circuit should use "circuit" name
        circuit = cortex.circuit();
        assertThat(circuit.subject().path().toString()).isEqualTo("circuit");

        // Default conduit should use "conduit" name (but hierarchical)
        Conduit<Pipe<String>, String> conduit = circuit.conduit(Composer.pipe());
        assertThat(conduit.subject().path().toString()).isEqualTo("circuit.conduit");

        // Default clock should use "clock" name
        Clock clock = circuit.clock();
        assertThat(clock.subject().path().toString()).isEqualTo("clock");
    }

    @Test
    void shouldBuildHierarchicalNamesForCircuitConduitChannel() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit(cortex.name("observability"));

        // Create conduit - should be circuit.conduit
        Conduit<Pipe<Long>, Long> conduit = circuit.conduit(
            cortex.name("metrics"),
            Composer.pipe()
        );

        // Create channel via Pipe (Pipe internally creates Channel)
        Pipe<Long> pipe = conduit.get(cortex.name("requests"));

        // Verify hierarchical paths
        assertThat(circuit.subject().path().toString())
            .isEqualTo("observability");

        assertThat(conduit.subject().path().toString())
            .isEqualTo("observability.metrics");

        // Channel path is built from conduit's already-hierarchical name
        // So it should be: observability.metrics.requests
        String channelPath = conduit.subject().name().name(cortex.name("requests")).path().toString();
        assertThat(channelPath)
            .isEqualTo("observability.metrics.requests");
    }

    @Test
    void shouldBuildHierarchicalNamesWithDefaultCircuit() {
        cortex = new CortexRuntime();

        // Use default circuit (name = "circuit")
        circuit = cortex.circuit();

        // Use default conduit (name = "conduit", but hierarchical)
        Conduit<Pipe<String>, String> conduit = circuit.conduit(Composer.pipe());

        // Create named channel
        Pipe<String> pipe = conduit.get(cortex.name("app"));

        // Verify hierarchical path: circuit.conduit.app
        assertThat(circuit.subject().path().toString())
            .isEqualTo("circuit");

        assertThat(conduit.subject().path().toString())
            .isEqualTo("circuit.conduit");

        // Channel name should be hierarchical
        String channelPath = conduit.subject().name().name(cortex.name("app")).path().toString();
        assertThat(channelPath)
            .isEqualTo("circuit.conduit.app");
    }

    @Test
    void shouldBuildDeepHierarchy() {
        cortex = new CortexRuntime();

        // Create multi-level hierarchy
        circuit = cortex.circuit(cortex.name("kafka"));
        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("broker-1"),
            Composer.pipe()
        );
        Pipe<String> pipe = conduit.get(cortex.name("partition-0"));

        // Verify each level
        assertThat(circuit.subject().path().toString())
            .isEqualTo("kafka");

        assertThat(conduit.subject().path().toString())
            .isEqualTo("kafka.broker-1");

        // Channel path
        String channelPath = conduit.subject().name().name(cortex.name("partition-0")).path().toString();
        assertThat(channelPath)
            .isEqualTo("kafka.broker-1.partition-0");
    }

    @Test
    void shouldBuildPathFromSubject() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit(cortex.name("system"));

        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("counters"),
            Composer.pipe()
        );

        // Use Subject.path() method
        assertThat(circuit.subject().path().toString())
            .isEqualTo("system");

        assertThat(conduit.subject().path().toString())
            .isEqualTo("system.counters");
    }

    @Test
    void shouldSupportMultipleConduitsWithDifferentComposers() {
        cortex = new CortexRuntime();
        circuit = cortex.circuit(cortex.name("app"));

        // Create two conduits with same name but different composers
        Conduit<Pipe<Long>, Long> pipes = circuit.conduit(
            cortex.name("streams"),
            Composer.pipe()
        );

        Conduit<Channel<Long>, Long> channels = circuit.conduit(
            cortex.name("streams"),
            Composer.channel()
        );

        // Should be different instances (different Composers)
        assertThat((Object) pipes).isNotSameAs(channels);

        // But both should have same hierarchical name
        assertThat(pipes.subject().path().toString())
            .isEqualTo("app.streams");

        assertThat(channels.subject().path().toString())
            .isEqualTo("app.streams");
    }
}
