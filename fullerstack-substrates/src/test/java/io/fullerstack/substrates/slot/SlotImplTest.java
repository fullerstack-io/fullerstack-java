package io.fullerstack.substrates.slot;

import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Slot;
import io.humainary.substrates.api.Substrates.State;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.name.LinkedName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlotImplTest {

    @Test
    void shouldCreateBooleanSlot() {
        Name name = new LinkedName("enabled", null);
        Slot<Boolean> slot = SlotImpl.of(name, true);

        assertThat((Object) slot.name()).isEqualTo(name);
        assertThat(slot.type()).isEqualTo(Boolean.class);
        assertThat(slot.value()).isTrue();
    }

    @Test
    void shouldCreateIntegerSlot() {
        Name name = new LinkedName("count", null);
        Slot<Integer> slot = SlotImpl.of(name, 42);

        assertThat(slot.type()).isEqualTo(Integer.class);
        assertThat(slot.value()).isEqualTo(42);
    }

    @Test
    void shouldCreateLongSlot() {
        Name name = new LinkedName("timestamp", null);
        Slot<Long> slot = SlotImpl.of(name, 123456L);

        assertThat(slot.type()).isEqualTo(Long.class);
        assertThat(slot.value()).isEqualTo(123456L);
    }

    @Test
    void shouldCreateDoubleSlot() {
        Name name = new LinkedName("percentage", null);
        Slot<Double> slot = SlotImpl.of(name, 75.5);

        assertThat(slot.type()).isEqualTo(Double.class);
        assertThat(slot.value()).isEqualTo(75.5);
    }

    @Test
    void shouldCreateFloatSlot() {
        Name name = new LinkedName("ratio", null);
        Slot<Float> slot = SlotImpl.of(name, 0.5f);

        assertThat(slot.type()).isEqualTo(Float.class);
        assertThat(slot.value()).isEqualTo(0.5f);
    }

    @Test
    void shouldCreateStringSlot() {
        Name name = new LinkedName("message", null);
        Slot<String> slot = SlotImpl.of(name, "hello");

        assertThat(slot.type()).isEqualTo(String.class);
        assertThat(slot.value()).isEqualTo("hello");
    }

    @Test
    void shouldCreateNameSlot() {
        Name name = new LinkedName("key", null);
        Name value = new LinkedName("value", null);
        Slot<Name> slot = SlotImpl.of(name, value, Name.class);

        assertThat(slot.type()).isEqualTo(Name.class);
        assertThat((Object) slot.value()).isEqualTo(value);
    }

    @Test
    void shouldCreateStateSlot() {
        Name name = new LinkedName("nested", null);
        State value = StateImpl.of(new LinkedName("inner", null), 42);
        Slot<State> slot = SlotImpl.of(name, value, State.class);

        assertThat(slot.type()).isEqualTo(State.class);
        assertThat((Object) slot.value()).isEqualTo(value);
    }

    @Test
    void shouldBeImmutable() {
        // Slots are immutable - value cannot change after creation
        Name name = new LinkedName("count", null);
        Slot<Integer> slot = SlotImpl.of(name, 42);

        assertThat(slot.value()).isEqualTo(42);

        // To get a different value, create a new Slot
        Slot<Integer> newSlot = SlotImpl.of(name, 100);
        assertThat(newSlot.value()).isEqualTo(100);

        // Original unchanged
        assertThat(slot.value()).isEqualTo(42);
    }

    @Test
    void shouldReturnNamePart() {
        Name name = new LinkedName("count", new LinkedName("broker", new LinkedName("kafka", null)));
        Slot<Integer> slot = SlotImpl.of(name, 42);

        assertThat(name.part()).isEqualTo("count");
    }

    @Test
    void shouldSupportEquality() {
        Name name1 = new LinkedName("test", null);
        Name name2 = new LinkedName("test", null);

        Slot<Integer> slot1 = SlotImpl.of(name1, 42);
        Slot<Integer> slot2 = SlotImpl.of(name2, 42);

        assertThat(slot1).isEqualTo(slot2);
        assertThat(slot1.hashCode()).isEqualTo(slot2.hashCode());
    }
}
