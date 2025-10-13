package io.fullerstack.substrates.functional;

import io.fullerstack.substrates.CortexRuntime;
import io.humainary.substrates.api.Substrates.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Composers}.
 *
 * <p>These tests demonstrate the CORRECT way to use Substrates via the Cortex API.
 */
class ComposersTest {

    // ========== Hierarchical Routing Tests ==========

    @Test
    void hierarchicalPipes_createsAllLevels() {
        // Given: A circuit created via Cortex API
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit(cortex.name("test"));

        Composer<Pipe<String>, String> composer = Channel::pipe;

        // Pre-create conduits at each level
        circuit.conduit(cortex.name("broker"), composer);
        circuit.conduit(cortex.name(List.of("broker", "1")), composer);
        circuit.conduit(cortex.name(List.of("broker", "1", "heap")), composer);

        // When: Creating subject via Cortex API
        Subject subject = cortex.capture(
            circuit.subject(),
            cortex.name(List.of("broker", "1", "heap"))
        ).subject();

        List<Pipe<String>> pipes = Composers.<String>hierarchicalPipes(circuit, subject, composer)
            .collect(Collectors.toList());

        // Then: Pipes created for all hierarchical levels
        assertThat(pipes).hasSize(3);
        assertThat(pipes).allMatch(pipe -> pipe != null);
    }

    @Test
    void hierarchicalConduits_returnsAllLevels() {
        // Given: Cortex and circuit
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit(cortex.name("test"));
        Name name = cortex.name(List.of("broker", "1", "heap"));
        Composer<Pipe<String>, String> composer = Channel::pipe;

        // When: Getting hierarchical conduits
        List<Conduit<Pipe<String>, String>> conduits = Composers.hierarchicalConduits(circuit, name, composer)
            .collect(Collectors.toList());

        // Then: Conduits returned for all levels
        assertThat(conduits).hasSize(3);
        assertThat(conduits.get(0).subject().name().path().toString()).isEqualTo("broker");
        assertThat(conduits.get(1).subject().name().path().toString()).isEqualTo("broker.1");
        assertThat(conduits.get(2).subject().name().path().toString()).isEqualTo("broker.1.heap");
    }

    @Test
    void hierarchicalConduits_withSingleLevel_returnsOne() {
        // Given: Single-level name
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit(cortex.name("test"));
        Name name = cortex.name("broker");
        Composer<Pipe<String>, String> composer = Channel::pipe;

        // When: Getting hierarchical conduits
        List<Conduit<Pipe<String>, String>> conduits = Composers.hierarchicalConduits(circuit, name, composer)
            .collect(Collectors.toList());

        // Then: Only one conduit
        assertThat(conduits).hasSize(1);
        assertThat(conduits.get(0).subject().name().path().toString()).isEqualTo("broker");
    }

    // ========== Filter/Transform Tests ==========

    @Test
    void filter_appliesPredicateToEmissions() {
        // Given: Circuit with filtered conduit
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit(cortex.name("test"));

        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("positive"),
            Channel::pipe,
            Composers.filter(n -> n > 0)
        );

        // When: Emitting positive and negative values
        List<Integer> received = new CopyOnWriteArrayList<>();
        conduit.source().subscribe((subject, registrar) ->
            registrar.register(conduit.get(cortex.name("test")))
        );

        conduit.source().subscribe(emission -> received.add(emission));

        Pipe<Integer> pipe = conduit.get(cortex.name("test"));
        pipe.emit(-5);
        pipe.emit(10);
        pipe.emit(-3);
        pipe.emit(20);

        // Give emissions time to propagate
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // Then: Only positive values received
        assertThat(received).containsExactly(10, 20);
    }

    @Test
    void limit_restrictsEmissionCount() {
        // Given: Circuit with limited conduit
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit(cortex.name("test"));

        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("limited"),
            Channel::pipe,
            Composers.limit(3)
        );

        // When: Emitting more than the limit
        List<String> received = new CopyOnWriteArrayList<>();
        conduit.source().subscribe(emission -> received.add(emission));

        Pipe<String> pipe = conduit.get(cortex.name("test"));
        pipe.emit("1");
        pipe.emit("2");
        pipe.emit("3");
        pipe.emit("4");  // Should be dropped
        pipe.emit("5");  // Should be dropped

        // Give emissions time to propagate
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // Then: Only first 3 emissions received
        assertThat(received).hasSize(3);
        assertThat(received).containsExactly("1", "2", "3");
    }

    @Test
    void diff_filtersConsecutiveDuplicates() {
        // Given: Circuit with diff sequencer
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit(cortex.name("test"));

        Conduit<Pipe<String>, String> conduit = circuit.conduit(
            cortex.name("diff"),
            Channel::pipe,
            Composers.diff()
        );

        // When: Emitting duplicate values
        List<String> received = new CopyOnWriteArrayList<>();
        conduit.source().subscribe(emission -> received.add(emission));

        Pipe<String> pipe = conduit.get(cortex.name("test"));
        pipe.emit("A");
        pipe.emit("A");  // Duplicate - filtered
        pipe.emit("B");
        pipe.emit("B");  // Duplicate - filtered
        pipe.emit("A");  // Different from previous - passed

        // Give emissions time to propagate
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // Then: Only changed values received
        assertThat(received).containsExactly("A", "B", "A");
    }

    @Test
    void sample_emitsEveryNth() {
        // Given: Circuit with sampling
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit(cortex.name("test"));

        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("sampled"),
            Channel::pipe,
            Composers.sample(3)  // Every 3rd emission
        );

        // When: Emitting many values
        List<Integer> received = new CopyOnWriteArrayList<>();
        conduit.source().subscribe(emission -> received.add(emission));

        Pipe<Integer> pipe = conduit.get(cortex.name("test"));
        for (int i = 1; i <= 10; i++) {
            pipe.emit(i);
        }

        // Give emissions time to propagate
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // Then: Only every 3rd value received
        assertThat(received).containsExactly(3, 6, 9);
    }

    @Test
    void compose_appliesMultipleTransformations() {
        // Given: Circuit with composed transformations
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit(cortex.name("test"));

        Sequencer<Segment<Integer>> pipeline = Composers.compose(
            Composers.filter(n -> n > 0),    // Only positive
            Composers.sample(2),              // Every 2nd
            Composers.limit(3)                // Max 3
        );

        Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
            cortex.name("pipeline"),
            Channel::pipe,
            pipeline
        );

        // When: Emitting values
        List<Integer> received = new CopyOnWriteArrayList<>();
        conduit.source().subscribe(emission -> received.add(emission));

        Pipe<Integer> pipe = conduit.get(cortex.name("test"));
        pipe.emit(-1);  // Filtered (negative)
        pipe.emit(1);   // Filtered (not 2nd)
        pipe.emit(2);   // Passed (positive, 2nd, under limit)
        pipe.emit(3);   // Filtered (not 2nd)
        pipe.emit(4);   // Passed (positive, 2nd, under limit)
        pipe.emit(5);   // Filtered (not 2nd)
        pipe.emit(6);   // Passed (positive, 2nd, under limit)
        pipe.emit(7);   // Dropped (limit reached)

        // Give emissions time to propagate
        try { Thread.sleep(100); } catch (InterruptedException e) {}

        // Then: Pipeline applied correctly
        assertThat(received).hasSize(3);
        assertThat(received).containsExactly(2, 4, 6);
    }

    // ========== Circuit Configuration Tests ==========

    @Test
    void configure_appliesConfigurationAndReturnsCircuit() {
        // Given: Cortex
        Cortex cortex = new CortexRuntime();

        // When: Configuring circuit
        Circuit circuit = Composers.configure(
            cortex.circuit(cortex.name("configured")),
            c -> {
                // Configuration applied
                assertThat(c.subject().name().path().toString()).isEqualTo("configured");
            }
        );

        // Then: Circuit returned
        assertThat(circuit).isNotNull();
        assertThat(circuit.subject().name().path().toString()).isEqualTo("configured");
    }

    @Test
    void tap_allowsSideEffectsWithoutBreakingChain() {
        // Given: Cortex and circuit
        Cortex cortex = new CortexRuntime();
        Circuit circuit = cortex.circuit(cortex.name("test"));

        // When: Tapping for side effects
        Circuit result = Composers.tap(circuit, c -> {
            // Side effect: verify name
            assertThat(c.subject().name().path().toString()).isEqualTo("test");
        });

        // Then: Same circuit returned
        assertThat(result).isSameAs(circuit);
    }
}
