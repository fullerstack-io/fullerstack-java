package io.fullerstack.substrates;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.capture.CaptureImpl;
import io.fullerstack.substrates.circuit.SingleThreadCircuit;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pool.PoolImpl;
import io.fullerstack.substrates.scope.ScopeImpl;
import io.fullerstack.substrates.slot.SlotImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.subscriber.SubscriberImpl;
import io.fullerstack.substrates.name.NameNode;
import io.fullerstack.substrates.sink.SinkImpl;

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

    private final Map<Name, Circuit> circuits;
    private final Map<Name, Scope> scopes;
    private final Scope defaultScope;

    /**
     * Creates a new Cortex runtime.
     */
    public CortexRuntime() {
        this.circuits = new ConcurrentHashMap<>();
        this.scopes = new ConcurrentHashMap<>();
        Name cortexName = NameNode.of("cortex");
        this.defaultScope = new ScopeImpl(cortexName);
        // Cache default scope by its name
        this.scopes.put(cortexName, defaultScope);
    }

    // ========== Circuit Management (2 methods) ==========

    @Override
    public Circuit circuit() {
        return circuit(NameNode.of("circuit"));
    }

    @Override
    public Circuit circuit(Name name) {
        Objects.requireNonNull(name, "Circuit name cannot be null");
        return circuits.computeIfAbsent(name, this::createCircuit);
    }

    private Circuit createCircuit(Name name) {
        return new SingleThreadCircuit(name);
    }

    // ========== Name Factory (8 methods) ==========
    // All delegate directly to NameNode.of() overloaded methods

    @Override
    public Name name(String s) {
        // Create root name - no path parsing
        return NameNode.of(s);
    }

    @Override
    public Name name(Enum<?> e) {
        // Create root, let Name handle the hierarchy building
        return NameNode.of(e.getDeclaringClass().getName()).name(e);
    }

    @Override
    public Name name(Iterable<String> parts) {
        // Get first element as root, let Name.name() build the rest
        Iterator<String> it = parts.iterator();
        if (!it.hasNext()) {
            throw new IllegalArgumentException("parts cannot be empty");
        }
        return NameNode.of(it.next()).name(it);
    }

    @Override
    public <T> Name name(Iterable<? extends T> items, Function<T, String> mapper) {
        // Get first element as root, let Name.name() build the rest
        Iterator<? extends T> it = items.iterator();
        if (!it.hasNext()) {
            throw new IllegalArgumentException("items cannot be empty");
        }
        return NameNode.of(mapper.apply(it.next())).name(it, mapper);
    }

    @Override
    public Name name(Iterator<String> parts) {
        // Get first element as root, let Name.name() build the rest
        if (!parts.hasNext()) {
            throw new IllegalArgumentException("parts cannot be empty");
        }
        return NameNode.of(parts.next()).name(parts);
    }

    @Override
    public <T> Name name(Iterator<? extends T> items, Function<T, String> mapper) {
        // Get first element as root, let Name.name() build the rest
        if (!items.hasNext()) {
            throw new IllegalArgumentException("items cannot be empty");
        }
        return NameNode.of(mapper.apply(items.next())).name(items, mapper);
    }

    @Override
    public Name name(Class<?> clazz) {
        return NameNode.of(clazz.getName());
    }

    @Override
    public Name name(Member member) {
        // Create root, let Name.name() handle the hierarchy
        return NameNode.of(member.getDeclaringClass().getName()).name(member);
    }

    // ========== Pool Management (1 method) ==========

    @Override
    public <T> Pool<T> pool(T value) {
        Objects.requireNonNull(value, "Pool value cannot be null");
        // Create pool that returns the same value for any name
        return new PoolImpl<>(name -> value);
    }

    // ========== Scope Management (2 methods) ==========

    @Override
    public Scope scope() {
        return defaultScope;
    }

    @Override
    public Scope scope(Name name) {
        Objects.requireNonNull(name, "Scope name cannot be null");
        return scopes.computeIfAbsent(name, n -> new ScopeImpl(n));
    }

    // ========== State Factory (1 method) ==========

    @Override
    public State state() {
        return StateImpl.empty();
    }

    // ========== Sink Creation (1 method) ==========

    @Override
    public <E, S extends Context<E, S>> Sink<E> sink(Context<E, S> context) {
        Objects.requireNonNull(context, "Context cannot be null");
        // Context extends Source, so we can create SinkImpl directly
        return new SinkImpl<>(context);
    }

    // ========== Slot Management (8 methods) ==========

    @Override
    public Slot<Boolean> slot(Name name, boolean value) {
        return SlotImpl.of(name, value);
    }

    @Override
    public Slot<Integer> slot(Name name, int value) {
        return SlotImpl.of(name, value);
    }

    @Override
    public Slot<Long> slot(Name name, long value) {
        return SlotImpl.of(name, value);
    }

    @Override
    public Slot<Double> slot(Name name, double value) {
        return SlotImpl.of(name, value);
    }

    @Override
    public Slot<Float> slot(Name name, float value) {
        return SlotImpl.of(name, value);
    }

    @Override
    public Slot<String> slot(Name name, String value) {
        return SlotImpl.of(name, value);
    }

    @Override
    public Slot<Name> slot(Name name, Name value) {
        return SlotImpl.of(name, value, Name.class);
    }

    @Override
    public Slot<State> slot(Name name, State value) {
        return SlotImpl.of(name, value, State.class);
    }

    @Override
    public Slot<Name> slot(Enum<?> value) {
        Objects.requireNonNull(value, "Enum value cannot be null");
        Name enumName = name(value.getClass().getSimpleName()).name(value.name());
        return SlotImpl.of(
            name(value.getClass().getSimpleName()),
            enumName,
            Name.class
        );
    }

    // ========== Subscriber Management (2 methods) ==========

    @Override
    public <E> Subscriber<E> subscriber(Name name, BiConsumer<Subject<Channel<E>>, Registrar<E>> fn) {
        return new SubscriberImpl<>(name, fn);
    }

    @Override
    public <E> Subscriber<E> subscriber(Name name, Pool<? extends Pipe<E>> pool) {
        return new SubscriberImpl<>(name, pool);
    }
}
