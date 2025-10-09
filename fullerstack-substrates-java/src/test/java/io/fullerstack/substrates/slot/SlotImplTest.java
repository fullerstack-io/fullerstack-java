package io.fullerstack.substrates.slot;

import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Slot;
import io.humainary.substrates.api.Substrates.State;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.util.NameImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlotImplTest {

    @Test
    void shouldCreateBooleanSlot() {
        Name name = NameImpl.of("enabled");
        Slot<Boolean> slot = SlotImpl.of(name, true);

        assertThat((Object) slot.name()).isEqualTo(name);
        assertThat(slot.type()).isEqualTo(Boolean.class);
        assertThat(slot.value()).isTrue();
    }

    @Test
    void shouldCreateIntegerSlot() {
        Name name = NameImpl.of("count");
        Slot<Integer> slot = SlotImpl.of(name, 42);

        assertThat(slot.type()).isEqualTo(Integer.class);
        assertThat(slot.value()).isEqualTo(42);
    }

    @Test
    void shouldCreateLongSlot() {
        Name name = NameImpl.of("timestamp");
        Slot<Long> slot = SlotImpl.of(name, 123456L);

        assertThat(slot.type()).isEqualTo(Long.class);
        assertThat(slot.value()).isEqualTo(123456L);
    }

    @Test
    void shouldCreateDoubleSlot() {
        Name name = NameImpl.of("percentage");
        Slot<Double> slot = SlotImpl.of(name, 75.5);

        assertThat(slot.type()).isEqualTo(Double.class);
        assertThat(slot.value()).isEqualTo(75.5);
    }

    @Test
    void shouldCreateFloatSlot() {
        Name name = NameImpl.of("ratio");
        Slot<Float> slot = SlotImpl.of(name, 0.5f);

        assertThat(slot.type()).isEqualTo(Float.class);
        assertThat(slot.value()).isEqualTo(0.5f);
    }

    @Test
    void shouldCreateStringSlot() {
        Name name = NameImpl.of("message");
        Slot<String> slot = SlotImpl.of(name, "hello");

        assertThat(slot.type()).isEqualTo(String.class);
        assertThat(slot.value()).isEqualTo("hello");
    }

    @Test
    void shouldCreateNameSlot() {
        Name name = NameImpl.of("key");
        Name value = NameImpl.of("value");
        Slot<Name> slot = SlotImpl.of(name, value, Name.class);

        assertThat(slot.type()).isEqualTo(Name.class);
        assertThat((Object) slot.value()).isEqualTo(value);
    }

    @Test
    void shouldCreateStateSlot() {
        Name name = NameImpl.of("nested");
        State value = StateImpl.of(NameImpl.of("inner"), 42);
        Slot<State> slot = SlotImpl.of(name, value, State.class);

        assertThat(slot.type()).isEqualTo(State.class);
        assertThat((Object) slot.value()).isEqualTo(value);
    }

    @Test
    void shouldSupportValueUpdate() {
        Name name = NameImpl.of("count");
        SlotImpl<Integer> slot = (SlotImpl<Integer>) SlotImpl.of(name, 42);

        slot.value(100);

        assertThat(slot.value()).isEqualTo(100);
    }

    @Test
    void shouldReturnNamePart() {
        Name name = NameImpl.of("kafka", "broker", "count");
        Slot<Integer> slot = SlotImpl.of(name, 42);

        assertThat(name.part()).isEqualTo("count");
    }

    @Test
    void shouldSupportEquality() {
        Name name1 = NameImpl.of("test");
        Name name2 = NameImpl.of("test");

        Slot<Integer> slot1 = SlotImpl.of(name1, 42);
        Slot<Integer> slot2 = SlotImpl.of(name2, 42);

        assertThat(slot1).isEqualTo(slot2);
        assertThat(slot1.hashCode()).isEqualTo(slot2.hashCode());
    }
}
