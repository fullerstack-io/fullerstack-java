package io.fullerstack.substrates.state;

import io.fullerstack.substrates.name.NameNode;
import io.fullerstack.substrates.slot.SlotImpl;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Slot;
import io.humainary.substrates.api.Substrates.State;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Implementation of Substrates.State for storing typed values.
 *
 * <p><b>Immutable Design:</b> State objects are immutable. Each call to {@code state()}
 * returns a NEW State instance with the slot appended. This allows duplicate names
 * to exist in the slot list.
 *
 * <p><b>Type Matching:</b> Per William Louth's design, "A State stores the type with
 * the name, only matching when both are exact matches." This allows the same name to
 * hold different types simultaneously:
 * <pre>
 * State state = cortex.state()
 *     .state(name("port"), 8080)        // Integer
 *     .state(name("port"), "HTTP/1.1"); // String (does NOT override Integer!)
 *
 * Integer port = state.value(slot(name("port"), 0));     // 8080
 * String protocol = state.value(slot(name("port"), "")); // "HTTP/1.1"
 * </pre>
 *
 * <p><b>Duplicate Handling:</b> Uses a List internally, which allows multiple slots
 * with the same (name, type) pair. Call {@code compact()} to remove duplicates, keeping
 * the last occurrence of each (name, type) pair.
 *
 * <p><b>Pattern:</b> Builder pattern with override support:
 * <pre>
 * State config = cortex.state()
 *     .state(name("timeout"), 30)   // Default value
 *     .state(name("timeout"), 60)   // Override (both exist until compact)
 *     .compact();                   // Deduplicate (keeps last: 60)
 * </pre>
 *
 * @see State
 */
public class StateImpl implements State {
    private final List<Slot<?>> slots;

    /**
     * Creates an empty State.
     */
    public StateImpl() {
        this.slots = new ArrayList<>();
    }

    /**
     * Private constructor for creating new State with slots.
     */
    private StateImpl(List<Slot<?>> slots) {
        this.slots = new ArrayList<>(slots);
    }

    @Override
    public State compact() {
        // Remove duplicates, keeping last occurrence of each (name, type) pair
        // Per article: "A State stores the type with the name, only matching when both are exact matches"
        Map<NameTypePair, Slot<?>> deduped = new LinkedHashMap<>();
        for (Slot<?> slot : slots) {
            deduped.put(new NameTypePair(slot.name(), slot.type()), slot);  // Last occurrence wins
        }
        return new StateImpl(new ArrayList<>(deduped.values()));
    }

    /**
     * Composite key for deduplication: (name, type) pair.
     * State matches slots by both name AND type.
     */
    private record NameTypePair(Name name, Class<?> type) {
    }

    @Override
    public State state(Name name, int value) {
        StateImpl newState = new StateImpl(this.slots);
        newState.slots.add(SlotImpl.of(name, value));
        return newState;
    }

    @Override
    public State state(Name name, long value) {
        StateImpl newState = new StateImpl(this.slots);
        newState.slots.add(SlotImpl.of(name, value));
        return newState;
    }

    @Override
    public State state(Name name, float value) {
        StateImpl newState = new StateImpl(this.slots);
        newState.slots.add(SlotImpl.of(name, value));
        return newState;
    }

    @Override
    public State state(Name name, double value) {
        StateImpl newState = new StateImpl(this.slots);
        newState.slots.add(SlotImpl.of(name, value));
        return newState;
    }

    @Override
    public State state(Name name, boolean value) {
        StateImpl newState = new StateImpl(this.slots);
        newState.slots.add(SlotImpl.of(name, value));
        return newState;
    }

    @Override
    public State state(Name name, String value) {
        StateImpl newState = new StateImpl(this.slots);
        newState.slots.add(SlotImpl.of(name, value));
        return newState;
    }

    @Override
    public State state(Name name, Name value) {
        StateImpl newState = new StateImpl(this.slots);
        newState.slots.add(SlotImpl.of(name, value));
        return newState;
    }

    @Override
    public State state(Name name, State value) {
        StateImpl newState = new StateImpl(this.slots);
        newState.slots.add(SlotImpl.of(name, value));
        return newState;
    }

    @Override
    public State state(Enum<?> value) {
        // Store enum by its class name and the enum constant name
        // This allows for type-safe enum retrieval
        StateImpl newState = new StateImpl(this.slots);
        // Use the enum's declaring class simple name as the slot name
        Name enumName = NameNode.of(value.getClass().getSimpleName());
        newState.slots.add(SlotImpl.of(enumName, value.name()));
        return newState;
    }

    @Override
    public State state(Slot<?> slot) {
        // Returns a state that includes the Slot specified.
        // The slot is inserted before existing entries; if an equal slot
        // (same name and value) already exists this state instance is returned.

        // Check if equal slot already exists
        for (Slot<?> existing : slots) {
            if (existing.name().equals(slot.name()) &&
                existing.type().equals(slot.type()) &&
                java.util.Objects.equals(existing.value(), slot.value())) {
                // Equal slot exists, return this instance
                return this;
            }
        }

        // Insert slot before existing entries (new state with slot prepended)
        List<Slot<?>> newSlots = new ArrayList<>();
        newSlots.add(slot);
        newSlots.addAll(this.slots);
        return new StateImpl(newSlots);
    }

    @Override
    public Stream<Slot<?>> stream() {
        return slots.stream();
    }

    @Override
    public <T> T value(Slot<T> slot) {
        // API: "Returns the value of a slot matching the specified slot
        //      or the value of the specified slot when not found"
        // Start with fallback value from query slot
        T result = slot.value();

        // Search for matching name AND type, override with LAST occurrence
        // Per article: "A State stores the type with the name, only matching when both are exact matches"
        for (Slot<?> s : slots) {
            if (s.name().equals(slot.name()) && typesMatch(slot.type(), s.type())) {
                @SuppressWarnings("unchecked")
                T value = ((Slot<T>) s).value();
                result = value;  // Keep updating with later occurrences
            }
        }

        // Returns slot.value() fallback if name and type not found
        return result;
    }

    @Override
    public <T> Stream<T> values(Slot<? extends T> slot) {
        // Return ALL values with this name AND type (for duplicate handling)
        // Per article: "A State stores the type with the name, only matching when both are exact matches"
        return slots.stream()
            .filter(s -> s.name().equals(slot.name()) && typesMatch(slot.type(), s.type()))
            .map(s -> {
                @SuppressWarnings("unchecked")
                Slot<T> typed = (Slot<T>) s;
                return typed.value();
            });
    }

    /**
     * Check if types match for slot lookup.
     * Handles both exact matches and interface/subclass relationships.
     *
     * @param queryType the type being queried (e.g., State.class)
     * @param storedType the type stored in the slot (e.g., StateImpl.class)
     * @return true if types are compatible
     */
    private boolean typesMatch(Class<?> queryType, Class<?> storedType) {
        return queryType.equals(storedType) || queryType.isAssignableFrom(storedType);
    }

    @Override
    public Iterator<Slot<?>> iterator() {
        return slots.iterator();
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
}
