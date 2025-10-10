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
        // State is immutable - each state() returns a NEW State
        State state = new StateImpl()
            .state(NameImpl.of("count"), 42)
            .state(NameImpl.of("name"), "test")
            .state(NameImpl.of("active"), true);

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

    @Test
    void shouldBeImmutable() {
        // State is immutable - each state() returns a NEW instance
        State s1 = new StateImpl();
        State s2 = s1.state(NameImpl.of("count"), 1);
        State s3 = s2.state(NameImpl.of("count"), 2);

        assertThat(s1).isNotSameAs(s2);
        assertThat(s2).isNotSameAs(s3);
        assertThat(s1.stream().count()).isZero();
        assertThat(s2.stream().count()).isEqualTo(1);
        assertThat(s3.stream().count()).isEqualTo(2);
    }

    @Test
    void shouldAllowDuplicateNames() {
        // State allows duplicate names (uses List, not Map)
        State state = new StateImpl()
            .state(NameImpl.of("timeout"), 30)   // First value
            .state(NameImpl.of("retries"), 3)
            .state(NameImpl.of("timeout"), 60);  // Duplicate name!

        assertThat(state.stream().count()).isEqualTo(3);  // 3 slots (2 have same name)
    }

    @Test
    void shouldCompactRemoveDuplicates() {
        // compact() removes duplicates, keeping last occurrence
        State state = new StateImpl()
            .state(NameImpl.of("timeout"), 30)   // First value
            .state(NameImpl.of("retries"), 3)
            .state(NameImpl.of("timeout"), 60);  // Override

        State compacted = state.compact();

        assertThat(state.stream().count()).isEqualTo(3);      // Original has 3
        assertThat(compacted.stream().count()).isEqualTo(2);  // Compacted has 2
    }

    @Test
    void shouldReturnLastValueWhenDuplicates() {
        Name timeoutName = NameImpl.of("timeout");

        State state = new StateImpl()
            .state(timeoutName, 30)
            .state(timeoutName, 60);

        // value() should return the LAST occurrence
        Integer timeout = state.value(io.fullerstack.substrates.slot.SlotImpl.of(timeoutName, 0));
        assertThat(timeout).isEqualTo(60);  // Last value wins
    }

    @Test
    void shouldReturnAllValuesForDuplicates() {
        Name timeoutName = NameImpl.of("timeout");

        State state = new StateImpl()
            .state(timeoutName, 30)
            .state(timeoutName, 45)
            .state(timeoutName, 60);

        // values() should return ALL occurrences
        var timeouts = state.values(io.fullerstack.substrates.slot.SlotImpl.of(timeoutName, 0))
            .toList();

        assertThat(timeouts).containsExactly(30, 45, 60);  // All values
    }
}
