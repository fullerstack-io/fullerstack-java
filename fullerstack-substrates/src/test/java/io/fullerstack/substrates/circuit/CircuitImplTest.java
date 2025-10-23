package io.fullerstack.substrates.circuit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.name.NameNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitImplTest {
    private CircuitImpl circuit;

    @AfterEach
    void cleanup() {
        if (circuit != null) {
            circuit.close();
        }
    }

    @Test
    void shouldCreateCircuitWithName() {
        circuit = new CircuitImpl(NameNode.of("test-circuit"));

        assertThat((Object) circuit).isNotNull();
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat(circuit.subject().type()).isEqualTo(Circuit.class);
    }

    @Test
    void shouldProvideStateSource() {
        circuit = new CircuitImpl(NameNode.of("test"));

        // Circuit IS-A Source<State>
        Source<State> source = circuit;

        assertThat((Object) source).isNotNull();
    }

    @Test
    void shouldProvideDefaultClock() {
        circuit = new CircuitImpl(NameNode.of("test"));

        Clock clock = circuit.clock();

        assertThat((Object) clock).isNotNull();
    }

    @Test
    void shouldProvideNamedClock() {
        circuit = new CircuitImpl(NameNode.of("test"));

        Clock clock = circuit.clock(NameNode.of("custom-clock"));

        assertThat((Object) clock).isNotNull();
    }

    @Test
    void shouldCacheClocksByName() {
        circuit = new CircuitImpl(NameNode.of("test"));

        Clock clock1 = circuit.clock(NameNode.of("shared"));
        Clock clock2 = circuit.clock(NameNode.of("shared"));

        assertThat((Object) clock1).isSameAs(clock2);
    }

    @Test
    void shouldCreateIndependentClocksForDifferentNames() {
        circuit = new CircuitImpl(NameNode.of("test"));

        Clock clock1 = circuit.clock(NameNode.of("clock1"));
        Clock clock2 = circuit.clock(NameNode.of("clock2"));

        assertThat((Object) clock1).isNotSameAs(clock2);
    }

    @Test
    void shouldSupportTapPattern() {
        circuit = new CircuitImpl(NameNode.of("test"));

        Circuit result = circuit.tap(c -> {
            assertThat((Object) c).isSameAs(circuit);
        });

        assertThat((Object) result).isSameAs(circuit);
    }


    @Test
    void shouldRequireNonNullName() {
        assertThatThrownBy(() -> new CircuitImpl(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Circuit name cannot be null");
    }

    @Test
    void shouldRequireNonNullClockName() {
        circuit = new CircuitImpl(NameNode.of("test"));

        assertThatThrownBy(() -> circuit.clock(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Clock name cannot be null");
    }

    @Test
    void shouldRequireNonNullConduitName() {
        circuit = new CircuitImpl(NameNode.of("test"));

        assertThatThrownBy(() -> circuit.conduit(null, composer -> null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Conduit name cannot be null");
    }

    @Test
    void shouldRequireNonNullComposer() {
        circuit = new CircuitImpl(NameNode.of("test"));

        assertThatThrownBy(() -> circuit.conduit(NameNode.of("test"), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Composer cannot be null");
    }

    @Test
    void shouldRequireNonNullTapConsumer() {
        circuit = new CircuitImpl(NameNode.of("test"));

        assertThatThrownBy(() -> circuit.tap(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Consumer cannot be null");
    }

    @Test
    void shouldPreventOperationsAfterClose() {
        circuit = new CircuitImpl(NameNode.of("test"));
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
        circuit = new CircuitImpl(NameNode.of("test"));

        circuit.close();
        circuit.close(); // Should not throw

        assertThat((Object) circuit).isNotNull();
    }

    @Test
    void shouldCloseAllClocksOnCircuitClose() {
        circuit = new CircuitImpl(NameNode.of("test"));

        Clock clock1 = circuit.clock(NameNode.of("clock1"));
        Clock clock2 = circuit.clock(NameNode.of("clock2"));

        circuit.close();

        // Clocks should be closed - verify by checking they throw on consume
        assertThatThrownBy(() -> ((io.fullerstack.substrates.clock.ClockImpl) clock1).consume(
            NameNode.of("test"),
            Clock.Cycle.SECOND,
            instant -> {}
        ))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldProvideAccessToAllComponents() {
        circuit = new CircuitImpl(NameNode.of("test"));

        // Verify all components are accessible
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat((Object) circuit).isNotNull();  // Circuit IS-A Source<State>
        assertThat((Object) circuit.clock()).isNotNull();
    }

    @Test
    void shouldCreateDifferentConduitsForDifferentComposers() {
        circuit = new CircuitImpl(NameNode.of("test"));

        // Same name, different composers should create DIFFERENT Conduits
        Conduit<Pipe<Long>, Long> pipes = circuit.conduit(
            NameNode.of("metrics"),
            Composer.pipe()
        );

        Conduit<Channel<Long>, Long> channels = circuit.conduit(
            NameNode.of("metrics"),  // ← SAME NAME
            Composer.channel()        // ← DIFFERENT COMPOSER
        );

        // Should be different Conduit instances
        assertThat((Object) pipes).isNotSameAs(channels);
    }

    @Test
    void shouldCreateDifferentConduitsForDifferentComposersWithDefaultName() {
        circuit = new CircuitImpl(NameNode.of("test"));

        // Using default name (unnamed), different composers should create DIFFERENT Conduits
        Conduit<Pipe<Long>, Long> pipes = circuit.conduit(Composer.pipe());
        Conduit<Channel<Long>, Long> channels = circuit.conduit(Composer.channel());

        // Should be different Conduit instances
        assertThat((Object) pipes).isNotSameAs(channels);
    }

    @Test
    void shouldCacheSameComposerWithSameName() {
        circuit = new CircuitImpl(NameNode.of("test"));

        // Same name, same composer should return SAME Conduit
        Conduit<Pipe<Long>, Long> conduit1 = circuit.conduit(
            NameNode.of("metrics"),
            Composer.pipe()
        );

        Conduit<Pipe<Long>, Long> conduit2 = circuit.conduit(
            NameNode.of("metrics"),
            Composer.pipe()
        );

        // Should be the same Conduit instance (cached)
        assertThat((Object) conduit1).isSameAs(conduit2);
    }

    @Test
    void shouldThrowWhenAwaitCalledFromCircuitThread() {
        circuit = new CircuitImpl(NameNode.of("test"));

        // Schedule a task that tries to call await() from within the circuit thread
        // This should throw IllegalStateException
        circuit.schedule(() -> {
            assertThatThrownBy(() -> circuit.await())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be called from within the circuit's own thread")
                .hasMessageContaining("deadlock");
        });

        // Wait for the circuit to process the task
        circuit.await();  // This is OK - called from test thread
    }
}
