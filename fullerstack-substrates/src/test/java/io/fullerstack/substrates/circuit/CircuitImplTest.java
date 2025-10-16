package io.fullerstack.substrates.circuit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.name.LinkedName;
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
        circuit = new CircuitImpl(new LinkedName("test-circuit", null));

        assertThat((Object) circuit).isNotNull();
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat(circuit.subject().type()).isEqualTo(Subject.Type.CIRCUIT);
    }

    @Test
    void shouldProvideStateSource() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        Source<State> source = circuit.source();

        assertThat((Object) source).isNotNull();
    }

    @Test
    void shouldProvideQueue() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        Queue queue = circuit.queue();

        assertThat((Object) queue).isNotNull();
    }

    @Test
    void shouldProvideDefaultClock() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        Clock clock = circuit.clock();

        assertThat((Object) clock).isNotNull();
    }

    @Test
    void shouldProvideNamedClock() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        Clock clock = circuit.clock(new LinkedName("custom-clock", null));

        assertThat((Object) clock).isNotNull();
    }

    @Test
    void shouldCacheClocksByName() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        Clock clock1 = circuit.clock(new LinkedName("shared", null));
        Clock clock2 = circuit.clock(new LinkedName("shared", null));

        assertThat((Object) clock1).isSameAs(clock2);
    }

    @Test
    void shouldCreateIndependentClocksForDifferentNames() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        Clock clock1 = circuit.clock(new LinkedName("clock1", null));
        Clock clock2 = circuit.clock(new LinkedName("clock2", null));

        assertThat((Object) clock1).isNotSameAs(clock2);
    }

    @Test
    void shouldSupportTapPattern() {
        circuit = new CircuitImpl(new LinkedName("test", null));

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
        circuit = new CircuitImpl(new LinkedName("test", null));

        assertThatThrownBy(() -> circuit.clock(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Clock name cannot be null");
    }

    @Test
    void shouldRequireNonNullConduitName() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        assertThatThrownBy(() -> circuit.conduit(null, composer -> null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Conduit name cannot be null");
    }

    @Test
    void shouldRequireNonNullComposer() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        assertThatThrownBy(() -> circuit.conduit(new LinkedName("test", null), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Composer cannot be null");
    }

    @Test
    void shouldRequireNonNullTapConsumer() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        assertThatThrownBy(() -> circuit.tap(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Consumer cannot be null");
    }

    @Test
    void shouldPreventOperationsAfterClose() {
        circuit = new CircuitImpl(new LinkedName("test", null));
        circuit.close();

        assertThatThrownBy(() -> circuit.queue())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Circuit is closed");

        assertThatThrownBy(() -> circuit.clock())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Circuit is closed");

        assertThatThrownBy(() -> circuit.tap(c -> {}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Circuit is closed");
    }

    @Test
    void shouldAllowMultipleCloses() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        circuit.close();
        circuit.close(); // Should not throw

        assertThat((Object) circuit).isNotNull();
    }

    @Test
    void shouldCloseAllClocksOnCircuitClose() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        Clock clock1 = circuit.clock(new LinkedName("clock1", null));
        Clock clock2 = circuit.clock(new LinkedName("clock2", null));

        circuit.close();

        // Clocks should be closed - verify by checking they throw on consume
        assertThatThrownBy(() -> ((io.fullerstack.substrates.clock.ClockImpl) clock1).consume(
            new LinkedName("test", null),
            Clock.Cycle.SECOND,
            instant -> {}
        ))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldProvideAccessToAllComponents() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        // Verify all components are accessible
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat((Object) circuit.source()).isNotNull();
        assertThat((Object) circuit.queue()).isNotNull();
        assertThat((Object) circuit.clock()).isNotNull();
    }

    @Test
    void shouldCreateDifferentConduitsForDifferentComposers() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        // Same name, different composers should create DIFFERENT Conduits
        Conduit<Pipe<Long>, Long> pipes = circuit.conduit(
            new LinkedName("metrics", null),
            Composer.pipe()
        );

        Conduit<Channel<Long>, Long> channels = circuit.conduit(
            new LinkedName("metrics", null),  // ← SAME NAME
            Composer.channel()        // ← DIFFERENT COMPOSER
        );

        // Should be different Conduit instances
        assertThat((Object) pipes).isNotSameAs(channels);
    }

    @Test
    void shouldCreateDifferentConduitsForDifferentComposersWithDefaultName() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        // Using default name (unnamed), different composers should create DIFFERENT Conduits
        Conduit<Pipe<Long>, Long> pipes = circuit.conduit(Composer.pipe());
        Conduit<Channel<Long>, Long> channels = circuit.conduit(Composer.channel());

        // Should be different Conduit instances
        assertThat((Object) pipes).isNotSameAs(channels);
    }

    @Test
    void shouldCacheSameComposerWithSameName() {
        circuit = new CircuitImpl(new LinkedName("test", null));

        // Same name, same composer should return SAME Conduit
        Conduit<Pipe<Long>, Long> conduit1 = circuit.conduit(
            new LinkedName("metrics", null),
            Composer.pipe()
        );

        Conduit<Pipe<Long>, Long> conduit2 = circuit.conduit(
            new LinkedName("metrics", null),
            Composer.pipe()
        );

        // Should be the same Conduit instance (cached)
        assertThat((Object) conduit1).isSameAs(conduit2);
    }
}
