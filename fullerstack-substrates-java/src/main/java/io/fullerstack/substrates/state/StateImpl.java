package io.fullerstack.substrates.state;

import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Slot;
import io.humainary.substrates.api.Substrates.State;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Implementation of Substrates.State for storing typed values.
 *
 * <p>This implementation supports storing multiple named slots with typed values.
 * State objects are mutable and thread-safe.
 *
 * @see State
 */
public class StateImpl implements State {
    private final Map<Name, Slot<?>> slots = new ConcurrentHashMap<>();

    public StateImpl() {
    }

    @Override
    public State compact() {
        // Return this - no compaction needed for basic impl
        return this;
    }

    @Override
    public State state(Name name, int value) {
        slots.put(name, SlotImpl.of(name, value));
        return this;
    }

    @Override
    public State state(Name name, long value) {
        slots.put(name, SlotImpl.of(name, value));
        return this;
    }

    @Override
    public State state(Name name, float value) {
        slots.put(name, SlotImpl.of(name, value));
        return this;
    }

    @Override
    public State state(Name name, double value) {
        slots.put(name, SlotImpl.of(name, value));
        return this;
    }

    @Override
    public State state(Name name, boolean value) {
        slots.put(name, SlotImpl.of(name, value));
        return this;
    }

    @Override
    public State state(Name name, String value) {
        slots.put(name, SlotImpl.of(name, value));
        return this;
    }

    @Override
    public State state(Name name, Name value) {
        slots.put(name, SlotImpl.of(name, value));
        return this;
    }

    @Override
    public State state(Name name, State value) {
        slots.put(name, SlotImpl.of(name, value));
        return this;
    }

    @Override
    public Stream<Slot<?>> stream() {
        return slots.values().stream();
    }

    @Override
    public <T> T value(Slot<T> slot) {
        @SuppressWarnings("unchecked")
        Slot<T> stored = (Slot<T>) slots.get(slot.name());
        return stored != null ? stored.value() : null;
    }

    @Override
    public <T> Stream<T> values(Slot<? extends T> slot) {
        T value = value((Slot<T>) slot);
        return value != null ? Stream.of(value) : Stream.empty();
    }

    @Override
    public Iterator<Slot<?>> iterator() {
        return slots.values().iterator();
    }

    // Factory methods for creating states
    public static State empty() {
        return new StateImpl();
    }

    public static State of(Name name, int value) {
        return new StateImpl().state(name, value);
    }

    public static State of(Name name, long value) {
        return new StateImpl().state(name, value);
    }

    public static State of(Name name, float value) {
        return new StateImpl().state(name, value);
    }

    public static State of(Name name, double value) {
        return new StateImpl().state(name, value);
    }

    public static State of(Name name, boolean value) {
        return new StateImpl().state(name, value);
    }

    public static State of(Name name, String value) {
        return new StateImpl().state(name, value);
    }

    public static State of(Name name, Name value) {
        return new StateImpl().state(name, value);
    }

    public static State of(Name name, State value) {
        return new StateImpl().state(name, value);
    }

    @Override
    public String toString() {
        return "State[slots=" + slots.size() + "]";
    }

    /**
     * Internal Slot implementation.
     */
    private static class SlotImpl<T> implements Slot<T> {
        private final Name name;
        private final T value;
        private final Class<T> type;

        private SlotImpl(Name name, T value, Class<T> type) {
            this.name = name;
            this.value = value;
            this.type = type;
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

        @SuppressWarnings("unchecked")
        static <T> Slot<T> of(Name name, T value) {
            return new SlotImpl<>(name, value, (Class<T>) value.getClass());
        }
    }
}
