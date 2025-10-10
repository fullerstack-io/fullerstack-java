package io.fullerstack.substrates.circuit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.name.NameImpl;
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
        circuit = new CircuitImpl(NameImpl.of("test-circuit"));

        assertThat((Object) circuit).isNotNull();
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat(circuit.subject().type()).isEqualTo(Subject.Type.CIRCUIT);
    }

    @Test
    void shouldProvideStateSource() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        Source<State> source = circuit.source();

        assertThat((Object) source).isNotNull();
    }

    @Test
    void shouldProvideQueue() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        Queue queue = circuit.queue();

        assertThat((Object) queue).isNotNull();
    }

    @Test
    void shouldProvideDefaultClock() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        Clock clock = circuit.clock();

        assertThat((Object) clock).isNotNull();
    }

    @Test
    void shouldProvideNamedClock() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        Clock clock = circuit.clock(NameImpl.of("custom-clock"));

        assertThat((Object) clock).isNotNull();
    }

    @Test
    void shouldCacheClocksByName() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        Clock clock1 = circuit.clock(NameImpl.of("shared"));
        Clock clock2 = circuit.clock(NameImpl.of("shared"));

        assertThat((Object) clock1).isSameAs(clock2);
    }

    @Test
    void shouldCreateIndependentClocksForDifferentNames() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        Clock clock1 = circuit.clock(NameImpl.of("clock1"));
        Clock clock2 = circuit.clock(NameImpl.of("clock2"));

        assertThat((Object) clock1).isNotSameAs(clock2);
    }

    @Test
    void shouldSupportTapPattern() {
        circuit = new CircuitImpl(NameImpl.of("test"));

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
        circuit = new CircuitImpl(NameImpl.of("test"));

        assertThatThrownBy(() -> circuit.clock(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Clock name cannot be null");
    }

    @Test
    void shouldRequireNonNullConduitName() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        assertThatThrownBy(() -> circuit.conduit(null, composer -> null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Conduit name cannot be null");
    }

    @Test
    void shouldRequireNonNullComposer() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        assertThatThrownBy(() -> circuit.conduit(NameImpl.of("test"), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Composer cannot be null");
    }

    @Test
    void shouldRequireNonNullTapConsumer() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        assertThatThrownBy(() -> circuit.tap(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Consumer cannot be null");
    }

    @Test
    void shouldPreventOperationsAfterClose() {
        circuit = new CircuitImpl(NameImpl.of("test"));
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
        circuit = new CircuitImpl(NameImpl.of("test"));

        circuit.close();
        circuit.close(); // Should not throw

        assertThat((Object) circuit).isNotNull();
    }

    @Test
    void shouldCloseAllClocksOnCircuitClose() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        Clock clock1 = circuit.clock(NameImpl.of("clock1"));
        Clock clock2 = circuit.clock(NameImpl.of("clock2"));

        circuit.close();

        // Clocks should be closed - verify by checking they throw on consume
        assertThatThrownBy(() -> ((io.fullerstack.substrates.clock.ClockImpl) clock1).consume(
            NameImpl.of("test"),
            Clock.Cycle.SECOND,
            instant -> {}
        ))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldProvideAccessToAllComponents() {
        circuit = new CircuitImpl(NameImpl.of("test"));

        // Verify all components are accessible
        assertThat((Object) circuit.subject()).isNotNull();
        assertThat((Object) circuit.source()).isNotNull();
        assertThat((Object) circuit.queue()).isNotNull();
        assertThat((Object) circuit.clock()).isNotNull();
    }
}
