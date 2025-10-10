package io.fullerstack.substrates.state;

import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.State;
import io.fullerstack.substrates.name.NameImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StateImplTest {

    @Test
    void shouldCreateEmptyState() {
        State state = StateImpl.empty();

        assertThat((Object) state).isNotNull();
        assertThat(state.stream().count()).isZero();
    }

    @Test
    void shouldStoreIntegerValue() {
        Name name = NameImpl.of("count");
        State state = StateImpl.of(name, 42);

        assertThat(state.stream().count()).isEqualTo(1);
    }

    @Test
    void shouldStoreMultipleValues() {
        State state = new StateImpl();
        state.state(NameImpl.of("count"), 42);
        state.state(NameImpl.of("name"), "test");
        state.state(NameImpl.of("active"), true);

        assertThat(state.stream().count()).isEqualTo(3);
    }

    @Test
    void shouldSupportMethodChaining() {
        State state = new StateImpl()
            .state(NameImpl.of("count"), 42)
            .state(NameImpl.of("name"), "test");

        assertThat(state.stream().count()).isEqualTo(2);
    }

    @Test
    void shouldStoreAllPrimitiveTypes() {
        Name name = NameImpl.of("test");

        State intState = StateImpl.of(name, 42);
        State longState = StateImpl.of(name, 42L);
        State floatState = StateImpl.of(name, 42.0f);
        State doubleState = StateImpl.of(name, 42.0);
        State boolState = StateImpl.of(name, true);
        State strState = StateImpl.of(name, "test");

        assertThat((Object) intState).isNotNull();
        assertThat((Object) longState).isNotNull();
        assertThat((Object) floatState).isNotNull();
        assertThat((Object) doubleState).isNotNull();
        assertThat((Object) boolState).isNotNull();
        assertThat((Object) strState).isNotNull();
    }
}
