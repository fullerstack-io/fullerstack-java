package io.fullerstack.substrates;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.capture.CaptureImpl;
import io.fullerstack.substrates.circuit.SequentialCircuit;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.pool.ConcurrentPool;
import io.fullerstack.substrates.scope.ManagedScope;
import io.fullerstack.substrates.slot.TypedSlot;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import io.fullerstack.substrates.subscriber.FunctionalSubscriber;
import io.fullerstack.substrates.name.HierarchicalName;
import io.fullerstack.substrates.sink.CollectingSink;

import java.util.concurrent.ConcurrentHashMap;

import java.lang.reflect.Member;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Complete implementation of Substrates.Cortex interface.
 *
 * <p>This class implements ALL 38 methods of the Cortex interface,
 * providing full Humainary Substrates API compliance.
 *
 * <p>Methods include:
 * <ul>
 *   <li>Circuit management (2 methods)</li>
 *   <li>Name factory (8 methods)</li>
 *   <li>Pool management (1 method)</li>
 *   <li>Scope management (2 methods)</li>
 *   <li>State factory (9 methods)</li>
 *   <li>Sink creation (2 methods)</li>
 *   <li>Slot management (8 methods)</li>
 *   <li>Subscriber management (2 methods)</li>
 *   <li>Capture creation (1 method)</li>
 * </ul>
 *
 * @see Cortex
 */
public class CortexRuntime implements Cortex {

    private final Map<Name, Scope> scopes;

    // Singleton instance for SPI provider pattern
    private static final Cortex INSTANCE = new CortexRuntime();

    /**
     * Creates a new Cortex runtime.
     */
    public CortexRuntime() {
        this.scopes = new ConcurrentHashMap<>();
    }

    /**
     * SPI provider method required by Substrates API M18+.
     * This method is discovered via system property: io.humainary.substrates.spi.provider
     *
     * @return The singleton Cortex instance
     */
    public static Cortex cortex() {
        return INSTANCE;
    }

    // ========== Circuit Management (2 methods) ==========

    @Override
    public Circuit circuit() {
        // Generate unique name for each unnamed circuit (do NOT intern)
        // This ensures each call creates a new circuit with unique subject
        return createCircuit(HierarchicalName.of("circuit." + UuidIdentifier.generate()));
    }

    @Override
    public Circuit circuit(Name name) {
        Objects.requireNonNull(name, "Circuit name cannot be null");
        // Create a new circuit each time instead of caching
        // This prevents issues where a closed circuit is reused
        return createCircuit(name);
    }

    private Circuit createCircuit(Name name) {
        return new SequentialCircuit(name);
    }

    // ========== Name Factory (8 methods) ==========
    // All delegate directly to HierarchicalName.of() overloaded methods

    @Override
    public Name name(String s) {
        // Create root name - no path parsing
        return HierarchicalName.of(s);
    }

    @Override
    public Name name(Enum<?> e) {
        // Convert $ to . for proper hierarchical name, then append enum constant
        String className = e.getDeclaringClass().getName().replace('$', '.');
        return HierarchicalName.of(className).name(e.name());
    }

    @Override
    public Name name(Iterable<String> parts) {
        // Get first element as root, let Name.name() build the rest
        Iterator<String> it = parts.iterator();
        if (!it.hasNext()) {
            throw new IllegalArgumentException("parts cannot be empty");
        }
        return HierarchicalName.of(it.next()).name(it);
    }

    @Override
    public <T> Name name(Iterable<? extends T> items, Function<T, String> mapper) {
        // Get first element as root, let Name.name() build the rest
        Iterator<? extends T> it = items.iterator();
        if (!it.hasNext()) {
            throw new IllegalArgumentException("items cannot be empty");
        }
        return HierarchicalName.of(mapper.apply(it.next())).name(it, mapper);
    }

    @Override
    public Name name(Iterator<String> parts) {
        // Get first element as root, let Name.name() build the rest
        if (!parts.hasNext()) {
            throw new IllegalArgumentException("parts cannot be empty");
        }
        return HierarchicalName.of(parts.next()).name(parts);
    }

    @Override
    public <T> Name name(Iterator<? extends T> items, Function<T, String> mapper) {
        // Get first element as root, let Name.name() build the rest
        if (!items.hasNext()) {
            throw new IllegalArgumentException("items cannot be empty");
        }
        return HierarchicalName.of(mapper.apply(items.next())).name(items, mapper);
    }

    @Override
    public Name name(Class<?> clazz) {
        // Convert $ to . for proper hierarchical name with inner classes
        return HierarchicalName.of(clazz.getName().replace('$', '.'));
    }

    @Override
    public Name name(Member member) {
        // Create root, let Name.name() handle the hierarchy
        return HierarchicalName.of(member.getDeclaringClass().getName()).name(member);
    }

    // ========== Pool Management (1 method) ==========

    @Override
    public <T> Pool<T> pool(T value) {
        Objects.requireNonNull(value, "Pool value cannot be null");
        // Create pool that returns the same value for any name
        return new ConcurrentPool<>(name -> value);
    }

    // ========== Scope Management (2 methods) ==========

    @Override
    public Scope scope() {
        // Create a new scope with a unique name each time
        // Each scope is independent and can be closed without affecting others
        return new ManagedScope(HierarchicalName.of("scope." + UuidIdentifier.generate().toString()));
    }

    @Override
    public Scope scope(Name name) {
        Objects.requireNonNull(name, "Scope name cannot be null");
        return scopes.computeIfAbsent(name, n -> new ManagedScope(n));
    }

    // ========== State Factory (1 method) ==========

    @Override
    public State state() {
        return LinkedState.empty();
    }

    // ========== Sink Creation (1 method) ==========

    @Override
    public <E, S extends Context<E, S>> Sink<E> sink(Context<E, S> context) {
        Objects.requireNonNull(context, "Context cannot be null");
        // Context extends Source, so we can create CollectingSink directly
        return new CollectingSink<>(context);
    }

    // ========== Slot Management (8 methods) ==========

    @Override
    public Slot<Boolean> slot(Name name, boolean value) {
        return TypedSlot.of(name, value);
    }

    @Override
    public Slot<Integer> slot(Name name, int value) {
        return TypedSlot.of(name, value);
    }

    @Override
    public Slot<Long> slot(Name name, long value) {
        return TypedSlot.of(name, value);
    }

    @Override
    public Slot<Double> slot(Name name, double value) {
        return TypedSlot.of(name, value);
    }

    @Override
    public Slot<Float> slot(Name name, float value) {
        return TypedSlot.of(name, value);
    }

    @Override
    public Slot<String> slot(Name name, String value) {
        return TypedSlot.of(name, value);
    }

    @Override
    public Slot<Name> slot(Name name, Name value) {
        return TypedSlot.of(name, value);
    }

    @Override
    public Slot<State> slot(Name name, State value) {
        return TypedSlot.of(name, value);
    }

    @Override
    public Slot<Name> slot(Enum<?> value) {
        Objects.requireNonNull(value, "Enum value cannot be null");
        // Slot name = fully qualified enum class name
        // Slot value = fully qualified enum class + class name again + constant
        return TypedSlot.of(
            name(value.getDeclaringClass()),
            name(value),
            Name.class
        );
    }

    // ========== Subscriber Management (2 methods) ==========

    @Override
    public <E> Subscriber<E> subscriber(Name name, BiConsumer<Subject<Channel<E>>, Registrar<E>> fn) {
        return new FunctionalSubscriber<>(name, fn);
    }

    @Override
    public <E> Subscriber<E> subscriber(Name name, Pool<? extends Pipe<E>> pool) {
        return new FunctionalSubscriber<>(name, pool);
    }
}
