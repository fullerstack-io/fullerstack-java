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
        Name name = new NameImpl("count", null);
        State state = StateImpl.of(name, 42);

        assertThat(state.stream().count()).isEqualTo(1);
    }

    @Test
    void shouldStoreMultipleValues() {
        // State is immutable - each state() returns a NEW State
        State state = new StateImpl()
            .state(new NameImpl("count", null), 42)
            .state(new NameImpl("name", null), "test")
            .state(new NameImpl("active", null), true);

        assertThat(state.stream().count()).isEqualTo(3);
    }

    @Test
    void shouldSupportMethodChaining() {
        State state = new StateImpl()
            .state(new NameImpl("count", null), 42)
            .state(new NameImpl("name", null), "test");

        assertThat(state.stream().count()).isEqualTo(2);
    }

    @Test
    void shouldStoreAllPrimitiveTypes() {
        Name name = new NameImpl("test", null);

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
        State s2 = s1.state(new NameImpl("count", null), 1);
        State s3 = s2.state(new NameImpl("count", null), 2);

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
            .state(new NameImpl("timeout", null), 30)   // First value
            .state(new NameImpl("retries", null), 3)
            .state(new NameImpl("timeout", null), 60);  // Duplicate name!

        assertThat(state.stream().count()).isEqualTo(3);  // 3 slots (2 have same name)
    }

    @Test
    void shouldCompactRemoveDuplicates() {
        // compact() removes duplicates, keeping last occurrence
        State state = new StateImpl()
            .state(new NameImpl("timeout", null), 30)   // First value
            .state(new NameImpl("retries", null), 3)
            .state(new NameImpl("timeout", null), 60);  // Override

        State compacted = state.compact();

        assertThat(state.stream().count()).isEqualTo(3);      // Original has 3
        assertThat(compacted.stream().count()).isEqualTo(2);  // Compacted has 2
    }

    @Test
    void shouldReturnLastValueWhenDuplicates() {
        Name timeoutName = new NameImpl("timeout", null);

        State state = new StateImpl()
            .state(timeoutName, 30)
            .state(timeoutName, 60);

        // value() should return the LAST occurrence
        Integer timeout = state.value(io.fullerstack.substrates.slot.SlotImpl.of(timeoutName, 0));
        assertThat(timeout).isEqualTo(60);  // Last value wins
    }

    @Test
    void shouldReturnAllValuesForDuplicates() {
        Name timeoutName = new NameImpl("timeout", null);

        State state = new StateImpl()
            .state(timeoutName, 30)
            .state(timeoutName, 45)
            .state(timeoutName, 60);

        // values() should return ALL occurrences
        var timeouts = state.values(io.fullerstack.substrates.slot.SlotImpl.of(timeoutName, 0))
            .toList();

        assertThat(timeouts).containsExactly(30, 45, 60);  // All values
    }

    @Test
    void shouldReturnFallbackValueWhenNotFound() {
        // API: "Returns the value of a slot matching the specified slot
        //      or the value of the specified slot when not found"

        Name missingName = new NameImpl("missing", null);
        State state = new StateImpl()
            .state(new NameImpl("existing", null), 100);

        // Slot provides fallback value (42)
        Integer result = state.value(io.fullerstack.substrates.slot.SlotImpl.of(missingName, 42));

        assertThat(result).isEqualTo(42);  // Should return fallback, not null
    }

    @Test
    void shouldSupportNestedState() {
        // State can contain State (hierarchical config)
        Name dbConfigName = new NameImpl("database", null);

        State innerState = new StateImpl()
            .state(new NameImpl("host", null), "localhost")
            .state(new NameImpl("port", null), 3306);

        State outerState = new StateImpl()
            .state(dbConfigName, innerState)
            .state(new NameImpl("timeout", null), 30);

        // Retrieve nested State
        State retrieved = outerState.value(
            io.fullerstack.substrates.slot.SlotImpl.of(dbConfigName, StateImpl.empty(), State.class)
        );

        assertThat(retrieved).isSameAs(innerState);
    }

    @Test
    void shouldMatchByBothNameAndType() {
        // Per article: "A State stores the type with the name, only matching when both are exact matches"
        Name XYZ = new NameImpl("XYZ", null);

        // Create slots with different types
        var intSlot = io.fullerstack.substrates.slot.SlotImpl.of(XYZ, 0);
        var stringSlot = io.fullerstack.substrates.slot.SlotImpl.of(XYZ, "");

        // Build state with same name, different types
        State state = new StateImpl()
            .state(XYZ, 3)      // Integer
            .state(XYZ, "4");   // String - does NOT override Integer!

        // Query by type - Integer slot returns Integer value
        Integer intValue = state.value(intSlot);
        assertThat(intValue).isEqualTo(3);  // Integer value unchanged

        // Query by type - String slot returns String value
        String stringValue = state.value(stringSlot);
        assertThat(stringValue).isEqualTo("4");  // String value found

        // Both coexist because types differ
        assertThat(state.stream().count()).isEqualTo(2);
    }

    @Test
    void shouldCompactByNameAndType() {
        // compact() should only remove duplicates with same (name, type) pair
        Name XYZ = new NameImpl("XYZ", null);

        State state = new StateImpl()
            .state(XYZ, 1)      // Integer #1
            .state(XYZ, 2)      // Integer #2 (duplicate)
            .state(XYZ, "a")    // String #1 (different type)
            .state(XYZ, "b");   // String #2 (duplicate)

        State compacted = state.compact();

        // Should keep last Integer and last String
        assertThat(compacted.stream().count()).isEqualTo(2);

        // Verify values
        var intSlot = io.fullerstack.substrates.slot.SlotImpl.of(XYZ, 0);
        var stringSlot = io.fullerstack.substrates.slot.SlotImpl.of(XYZ, "");

        assertThat(compacted.value(intSlot)).isEqualTo(2);     // Last Integer
        assertThat(compacted.value(stringSlot)).isEqualTo("b"); // Last String
    }
}
