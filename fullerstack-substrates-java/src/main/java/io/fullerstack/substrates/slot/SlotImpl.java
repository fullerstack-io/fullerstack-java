package io.fullerstack.substrates.slot;

import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Slot;
import io.humainary.substrates.api.Substrates.Subject;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.state.StateImpl;

import java.util.Objects;

/**
 * Implementation of Substrates.Slot for typed query/lookup objects.
 *
 * <p><b>Immutable Design:</b> Slots are immutable query objects used to lookup
 * values in State with type safety and fallback support.
 *
 * <p><b>Pattern:</b> Create Slot once, reuse for multiple State queries:
 * <pre>
 * Slot&lt;Integer&gt; maxConn = cortex.slot(name("max-connections"), 100);
 * int value1 = state1.value(maxConn);  // Query state1
 * int value2 = state2.value(maxConn);  // Reuse for state2
 * </pre>
 *
 * <p>Based on William Louth's design: Slots are immutable query objects,
 * not mutable configuration holders.
 *
 * @param <T> the value type
 * @see Slot
 * @see State#value(Slot)
 */
public class SlotImpl<T> implements Slot<T> {
    private final Name name;
    private final Class<T> type;
    private final T value;

    /**
     * Creates a new Slot.
     *
     * @param name slot name
     * @param value initial value
     * @param type value type
     */
    public SlotImpl(Name name, T value, Class<T> type) {
        this.name = Objects.requireNonNull(name, "Slot name cannot be null");
        this.value = value;
        this.type = Objects.requireNonNull(type, "Slot type cannot be null");
    }

    @Override
    public Name name() {
        return name;
    }

    @Override
    public Class<T> type() {
        return type;
    }

    @Override
    public T value() {
        return value;
    }

    /**
     * Creates a Subject representation of this Slot.
     *
     * @return subject with slot state
     */
    public Subject subject() {
        return new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.of(name, value != null ? value.toString() : "null"),
            Subject.Type.SCOPE  // Using SCOPE as there's no SLOT type
        );
    }

    // Factory methods for common types
    public static Slot<Boolean> of(Name name, boolean value) {
        return new SlotImpl<>(name, value, Boolean.class);
    }

    public static Slot<Integer> of(Name name, int value) {
        return new SlotImpl<>(name, value, Integer.class);
    }

    public static Slot<Long> of(Name name, long value) {
        return new SlotImpl<>(name, value, Long.class);
    }

    public static Slot<Double> of(Name name, double value) {
        return new SlotImpl<>(name, value, Double.class);
    }

    public static Slot<Float> of(Name name, float value) {
        return new SlotImpl<>(name, value, Float.class);
    }

    public static Slot<String> of(Name name, String value) {
        return new SlotImpl<>(name, value, String.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> Slot<T> of(Name name, T value, Class<T> type) {
        return new SlotImpl<>(name, value, type);
    }

    @Override
    public String toString() {
        return "Slot[name=" + name + ", type=" + type.getSimpleName() + ", value=" + value + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SlotImpl<?> other)) return false;
        return name.equals(other.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
