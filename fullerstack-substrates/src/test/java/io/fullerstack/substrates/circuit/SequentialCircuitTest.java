package io.fullerstack.substrates.circuit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.name.HierarchicalName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SequentialCircuitTest {
    private SequentialCircuit circuit;

    @AfterEach
    void cleanup() {
        if (circuit != null) {
            circuit.close();
        }
    }

    @Test
    void shouldCreateCircuitWithName() {
        circuit = new SequentialCircuit(HierarchicalName.of("test-circuit"));

        assertThat((Object) circuit).isNotNull();
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat(circuit.subject().type()).isEqualTo(Circuit.class);
    }

    @Test
    void shouldProvideStateSource() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        // Circuit IS-A Source<State>
        Source<State> source = circuit;

        assertThat((Object) source).isNotNull();
    }

    @Test
    void shouldProvideDefaultClock() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        Clock clock = circuit.clock();

        assertThat((Object) clock).isNotNull();
    }

    @Test
    void shouldProvideNamedClock() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        Clock clock = circuit.clock(HierarchicalName.of("custom-clock"));

        assertThat((Object) clock).isNotNull();
    }

    @Test
    void shouldCacheClocksByName() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        Clock clock1 = circuit.clock(HierarchicalName.of("shared"));
        Clock clock2 = circuit.clock(HierarchicalName.of("shared"));

        assertThat((Object) clock1).isSameAs(clock2);
    }

    @Test
    void shouldCreateIndependentClocksForDifferentNames() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        Clock clock1 = circuit.clock(HierarchicalName.of("clock1"));
        Clock clock2 = circuit.clock(HierarchicalName.of("clock2"));

        assertThat((Object) clock1).isNotSameAs(clock2);
    }

    @Test
    void shouldSupportTapPattern() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        Circuit result = circuit.tap(c -> {
            assertThat((Object) c).isSameAs(circuit);
        });

        assertThat((Object) result).isSameAs(circuit);
    }


    @Test
    void shouldRequireNonNullName() {
        assertThatThrownBy(() -> new SequentialCircuit(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Circuit name cannot be null");
    }

    @Test
    void shouldRequireNonNullClockName() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        assertThatThrownBy(() -> circuit.clock(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Clock name cannot be null");
    }

    @Test
    void shouldRequireNonNullConduitName() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        assertThatThrownBy(() -> circuit.conduit(null, composer -> null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Conduit name cannot be null");
    }

    @Test
    void shouldRequireNonNullComposer() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        assertThatThrownBy(() -> circuit.conduit(HierarchicalName.of("test"), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Composer cannot be null");
    }

    @Test
    void shouldRequireNonNullTapConsumer() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        assertThatThrownBy(() -> circuit.tap(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Consumer cannot be null");
    }

    @Test
    void shouldPreventOperationsAfterClose() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));
        circuit.close();

        assertThatThrownBy(() -> circuit.clock())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Circuit is closed");

        assertThatThrownBy(() -> circuit.tap(c -> {}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Circuit is closed");
    }

    @Test
    void shouldAllowMultipleCloses() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        circuit.close();
        circuit.close(); // Should not throw

        assertThat((Object) circuit).isNotNull();
    }

    @Test
    void shouldCloseAllClocksOnCircuitClose() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        Clock clock1 = circuit.clock(HierarchicalName.of("clock1"));
        Clock clock2 = circuit.clock(HierarchicalName.of("clock2"));

        circuit.close();

        // Clocks should be closed - verify by checking they throw on consume
        assertThatThrownBy(() -> ((io.fullerstack.substrates.clock.ScheduledClock) clock1).consume(
            HierarchicalName.of("test"),
            Clock.Cycle.SECOND,
            instant -> {}
        ))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldProvideAccessToAllComponents() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        // Verify all components are accessible
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat((Object) circuit).isNotNull();  // Circuit IS-A Source<State>
        assertThat((Object) circuit.clock()).isNotNull();
    }

    @Test
    void shouldCreateDifferentConduitsForDifferentComposers() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        // Same name, different composers should create DIFFERENT Conduits
        Conduit<Pipe<Long>, Long> pipes = circuit.conduit(
            HierarchicalName.of("metrics"),
            Composer.pipe()
        );

        Conduit<Channel<Long>, Long> channels = circuit.conduit(
            HierarchicalName.of("metrics"),  // ← SAME NAME
            Composer.channel()        // ← DIFFERENT COMPOSER
        );

        // Should be different Conduit instances
        assertThat((Object) pipes).isNotSameAs(channels);
    }

    @Test
    void shouldCreateDifferentConduitsForDifferentComposersWithDefaultName() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        // Using default name (unnamed), different composers should create DIFFERENT Conduits
        Conduit<Pipe<Long>, Long> pipes = circuit.conduit(Composer.pipe());
        Conduit<Channel<Long>, Long> channels = circuit.conduit(Composer.channel());

        // Should be different Conduit instances
        assertThat((Object) pipes).isNotSameAs(channels);
    }

    @Test
    void shouldCacheSameComposerWithSameName() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        // Same name, same composer should return SAME Conduit
        Conduit<Pipe<Long>, Long> conduit1 = circuit.conduit(
            HierarchicalName.of("metrics"),
            Composer.pipe()
        );

        Conduit<Pipe<Long>, Long> conduit2 = circuit.conduit(
            HierarchicalName.of("metrics"),
            Composer.pipe()
        );

        // Should be the same Conduit instance (cached)
        assertThat((Object) conduit1).isSameAs(conduit2);
    }

    @Test
    void shouldThrowWhenAwaitCalledFromCircuitThread() {
        circuit = new SequentialCircuit(HierarchicalName.of("test"));

        // Schedule a task that tries to call await() from within the circuit thread
        // This should throw IllegalStateException
        circuit.schedule(() -> {
            assertThatThrownBy(() -> circuit.await())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot call Circuit::await from within a circuit's thread");
        });

        // Wait for the circuit to process the task
        circuit.await();  // This is OK - called from test thread
    }
}
