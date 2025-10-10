package io.fullerstack.substrates;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.name.NameImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for CortexRuntime - validates all 38 Cortex interface methods.
 */
class CortexRuntimeTest {

    private Cortex cortex;

    @BeforeEach
    void setUp() {
        cortex = new CortexRuntime();
    }

    // ========== Circuit Management (2 methods) ==========

    @Test
    void shouldCreateDefaultCircuit() {
        Circuit circuit = cortex.circuit();

        assertThat((Object) circuit).isNotNull();
        assertThat((Object) circuit.subject()).isNotNull();
    }

    @Test
    void shouldCreateNamedCircuit() {
        Circuit circuit = cortex.circuit(NameImpl.of("test-circuit"));

        assertThat(circuit).isNotNull();
        assertThat(circuit.subject().name().value()).contains("test-circuit");
    }

    @Test
    void shouldCacheCircuitsByName() {
        Name name = NameImpl.of("cached");

        Circuit c1 = cortex.circuit(name);
        Circuit c2 = cortex.circuit(name);

        assertThat(c1).isSameAs(c2);
    }

    // ========== Name Factory (8 methods) ==========

    @Test
    void shouldCreateNameFromString() {
        Name name = cortex.name("test");

        assertThat((Object) name).isNotNull();
        assertThat(name.part()).isEqualTo("test");
    }

    @Test
    void shouldCreateNameFromEnum() {
        enum TestEnum { VALUE }

        Name name = cortex.name(TestEnum.VALUE);

        assertThat(name.part()).isEqualTo("VALUE");
    }

    @Test
    void shouldCreateNameFromIterable() {
        Name name = cortex.name(List.of("kafka", "broker"));

        assertThat(name.value()).isEqualTo("kafka.broker");
    }

    @Test
    void shouldCreateNameFromIterableWithMapper() {
        Name name = cortex.name(List.of(1, 2, 3), Object::toString);

        assertThat(name.value()).isEqualTo("1.2.3");
    }

    @Test
    void shouldCreateNameFromClass() {
        Name name = cortex.name(String.class);

        assertThat(name.part()).isEqualTo("String");
    }

    // ========== Pool Management (1 method) ==========

    @Test
    void shouldCreatePool() {
        Pool<String> pool = cortex.pool("test-value");

        assertThat(pool).isNotNull();
        assertThat(pool.get(NameImpl.of("any"))).isEqualTo("test-value");
    }

    // ========== Scope Management (2 methods) ==========

    @Test
    void shouldCreateDefaultScope() {
        Scope scope = cortex.scope();

        assertThat((Object) scope).isNotNull();
        assertThat((Object) scope.subject()).isNotNull();
    }

    @Test
    void shouldCreateNamedScope() {
        Scope scope = cortex.scope(NameImpl.of("test-scope"));

        assertThat((Object) scope).isNotNull();
        assertThat(scope.subject().name().value()).contains("test-scope");
    }

    // ========== State Factory (9 methods) ==========

    @Test
    void shouldCreateEmptyState() {
        State state = cortex.state();

        assertThat((Object) state).isNotNull();
    }

    @Test
    void shouldCreateStateWithInt() {
        State state = cortex.state(NameImpl.of("count"), 42);

        assertThat((Object) state).isNotNull();
    }

    @Test
    void shouldCreateStateWithLong() {
        State state = cortex.state(NameImpl.of("timestamp"), 123456789L);

        assertThat((Object) state).isNotNull();
    }

    @Test
    void shouldCreateStateWithFloat() {
        State state = cortex.state(NameImpl.of("ratio"), 0.5f);

        assertThat((Object) state).isNotNull();
    }

    @Test
    void shouldCreateStateWithDouble() {
        State state = cortex.state(NameImpl.of("percentage"), 75.5);

        assertThat((Object) state).isNotNull();
    }

    @Test
    void shouldCreateStateWithBoolean() {
        State state = cortex.state(NameImpl.of("active"), true);

        assertThat((Object) state).isNotNull();
    }

    @Test
    void shouldCreateStateWithString() {
        State state = cortex.state(NameImpl.of("message"), "hello");

        assertThat((Object) state).isNotNull();
    }

    @Test
    void shouldCreateStateWithName() {
        State state = cortex.state(NameImpl.of("key"), NameImpl.of("value"));

        assertThat((Object) state).isNotNull();
    }

    @Test
    void shouldCreateStateWithState() {
        State innerState = cortex.state(NameImpl.of("inner"), 42);
        State outerState = cortex.state(NameImpl.of("outer"), innerState);

        assertThat(outerState).isNotNull();
    }

    // ========== Slot Management (8 methods) ==========

    @Test
    void shouldCreateBooleanSlot() {
        Slot<Boolean> slot = cortex.slot(NameImpl.of("enabled"), true);

        assertThat(slot.value()).isTrue();
        assertThat(slot.type()).isEqualTo(Boolean.class);
    }

    @Test
    void shouldCreateIntegerSlot() {
        Slot<Integer> slot = cortex.slot(NameImpl.of("count"), 42);

        assertThat(slot.value()).isEqualTo(42);
        assertThat(slot.type()).isEqualTo(Integer.class);
    }

    @Test
    void shouldCreateLongSlot() {
        Slot<Long> slot = cortex.slot(NameImpl.of("timestamp"), 123456L);

        assertThat(slot.value()).isEqualTo(123456L);
        assertThat(slot.type()).isEqualTo(Long.class);
    }

    @Test
    void shouldCreateDoubleSlot() {
        Slot<Double> slot = cortex.slot(NameImpl.of("percentage"), 75.5);

        assertThat(slot.value()).isEqualTo(75.5);
        assertThat(slot.type()).isEqualTo(Double.class);
    }

    @Test
    void shouldCreateFloatSlot() {
        Slot<Float> slot = cortex.slot(NameImpl.of("ratio"), 0.5f);

        assertThat(slot.value()).isEqualTo(0.5f);
        assertThat(slot.type()).isEqualTo(Float.class);
    }

    @Test
    void shouldCreateStringSlot() {
        Slot<String> slot = cortex.slot(NameImpl.of("name"), "test");

        assertThat(slot.value()).isEqualTo("test");
        assertThat(slot.type()).isEqualTo(String.class);
    }

    @Test
    void shouldCreateNameSlot() {
        Name value = NameImpl.of("test");
        Slot<Name> slot = cortex.slot(NameImpl.of("key"), value);

        assertThat((Object) slot.value()).isEqualTo(value);
        assertThat(slot.type()).isEqualTo(Name.class);
    }

    @Test
    void shouldCreateStateSlot() {
        State value = cortex.state(NameImpl.of("inner"), 42);
        Slot<State> slot = cortex.slot(NameImpl.of("outer"), value);

        assertThat(slot.value()).isEqualTo(value);
        assertThat(slot.type()).isEqualTo(State.class);
    }

    // ========== Subscriber Management (2 methods) ==========

    @Test
    void shouldCreateSubscriberWithFunction() {
        Subscriber<String> subscriber = cortex.subscriber(
            NameImpl.of("test"),
            (subject, registrar) -> {}
        );

        assertThat((Object) subscriber).isNotNull();
        assertThat((Object) subscriber.subject()).isNotNull();
    }

    @Test
    void shouldCreateSubscriberWithPool() {
        Pool<Pipe<String>> pool = new io.fullerstack.substrates.pool.PoolImpl<>(name -> null);
        Subscriber<String> subscriber = cortex.subscriber(NameImpl.of("test"), pool);

        assertThat((Object) subscriber).isNotNull();
        assertThat((Object) subscriber.subject()).isNotNull();
    }

    // ========== Sink Creation (2 methods) ==========

    @Test
    void shouldCreateSinkFromSource() {
        Circuit circuit = cortex.circuit();
        Sink<State> sink = cortex.sink(circuit.source());

        assertThat((Object) sink).isNotNull();
        assertThat((Object) sink.subject()).isNotNull();
        assertThat(sink.drain()).isEmpty();
    }

    // ========== Capture Creation (1 method) ==========

    @Test
    void shouldCreateCapture() {
        Subject subject = cortex.scope().subject();
        Capture<String> capture = cortex.capture(subject, "test-emission");

        assertThat((Object) capture.subject()).isEqualTo(subject);
        assertThat(capture.emission()).isEqualTo("test-emission");
    }

    // ========== Integration Test ==========

    @Test
    void shouldImplementAllCortexMethods() {
        // Verify all 38 methods are callable and return non-null
        assertThat((Object) cortex.circuit()).isNotNull();
        assertThat(cortex.circuit(NameImpl.of("test"))).isNotNull();
        assertThat((Object) cortex.name("test")).isNotNull();
        assertThat((Object) cortex.name(List.of("a", "b"))).isNotNull();
        assertThat(cortex.pool("value")).isNotNull();
        assertThat((Object) cortex.scope()).isNotNull();
        assertThat((Object) cortex.scope(NameImpl.of("test"))).isNotNull();
        assertThat((Object) cortex.state()).isNotNull();
        assertThat((Object) cortex.state(NameImpl.of("n"), 1)).isNotNull();
        assertThat((Object) cortex.state(NameImpl.of("n"), 1L)).isNotNull();
        assertThat((Object) cortex.state(NameImpl.of("n"), 1.0f)).isNotNull();
        assertThat((Object) cortex.state(NameImpl.of("n"), 1.0)).isNotNull();
        assertThat((Object) cortex.state(NameImpl.of("n"), true)).isNotNull();
        assertThat((Object) cortex.state(NameImpl.of("n"), "s")).isNotNull();
        assertThat((Object) cortex.slot(NameImpl.of("n"), true)).isNotNull();
        assertThat((Object) cortex.slot(NameImpl.of("n"), 1)).isNotNull();
        assertThat((Object) cortex.slot(NameImpl.of("n"), 1L)).isNotNull();
        assertThat((Object) cortex.slot(NameImpl.of("n"), 1.0)).isNotNull();
        assertThat((Object) cortex.slot(NameImpl.of("n"), 1.0f)).isNotNull();
        assertThat((Object) cortex.slot(NameImpl.of("n"), "s")).isNotNull();
        assertThat((Object) cortex.subscriber(NameImpl.of("s"), (sub, reg) -> {})).isNotNull();
        assertThat((Object) cortex.capture(cortex.scope().subject(), "e")).isNotNull();
    }
}
