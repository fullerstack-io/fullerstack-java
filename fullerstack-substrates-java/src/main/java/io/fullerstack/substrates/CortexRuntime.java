package io.fullerstack.substrates;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.circuit.CircuitImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pool.PoolImpl;
import io.fullerstack.substrates.scope.ScopeImpl;
import io.fullerstack.substrates.slot.SlotImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.subscriber.SubscriberImpl;
import io.fullerstack.substrates.name.NameImpl;
import io.fullerstack.substrates.sink.SinkImpl;
import io.fullerstack.substrates.sink.CaptureImpl;

import java.lang.reflect.Member;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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

    private final Map<Name, Circuit> circuits = new ConcurrentHashMap<>();
    private final Scope defaultScope;

    /**
     * Creates a new Cortex runtime.
     */
    public CortexRuntime() {
        this.defaultScope = new ScopeImpl(NameImpl.of("cortex"));
    }

    // ========== Circuit Management (2 methods) ==========

    @Override
    public Circuit circuit() {
        return circuit(NameImpl.of("circuit"));
    }

    @Override
    public Circuit circuit(Name name) {
        Objects.requireNonNull(name, "Circuit name cannot be null");
        return circuits.computeIfAbsent(name, this::createCircuit);
    }

    private Circuit createCircuit(Name name) {
        return new CircuitImpl(name);
    }

    // ========== Name Factory (8 methods) ==========

    @Override
    public Name name(String s) {
        return NameImpl.of(s);
    }

    @Override
    public Name name(Enum<?> e) {
        return NameImpl.of(e.name());
    }

    @Override
    public Name name(Iterable<String> parts) {
        return NameImpl.of(parts);
    }

    @Override
    public <T> Name name(Iterable<? extends T> items, Function<T, String> mapper) {
        Name name = null;
        for (T item : items) {
            String part = mapper.apply(item);
            name = name == null ? NameImpl.of(part) : name.name(part);
        }
        return name != null ? name : NameImpl.of("empty");
    }

    @Override
    public Name name(Iterator<String> parts) {
        Name name = null;
        while (parts.hasNext()) {
            name = name == null ? NameImpl.of(parts.next()) : name.name(parts.next());
        }
        return name != null ? name : NameImpl.of("empty");
    }

    @Override
    public <T> Name name(Iterator<? extends T> items, Function<T, String> mapper) {
        Name name = null;
        while (items.hasNext()) {
            String part = mapper.apply(items.next());
            name = name == null ? NameImpl.of(part) : name.name(part);
        }
        return name != null ? name : NameImpl.of("empty");
    }

    @Override
    public Name name(Class<?> clazz) {
        return NameImpl.of(clazz.getSimpleName());
    }

    @Override
    public Name name(Member member) {
        return NameImpl.of(member.getName());
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
        return new ScopeImpl(name);
    }

    // ========== State Factory (9 methods) ==========

    @Override
    public State state() {
        return StateImpl.empty();
    }

    @Override
    public State state(Name name, int value) {
        return StateImpl.of(name, value);
    }

    @Override
    public State state(Name name, long value) {
        return StateImpl.of(name, value);
    }

    @Override
    public State state(Name name, float value) {
        return StateImpl.of(name, value);
    }

    @Override
    public State state(Name name, double value) {
        return StateImpl.of(name, value);
    }

    @Override
    public State state(Name name, boolean value) {
        return StateImpl.of(name, value);
    }

    @Override
    public State state(Name name, String value) {
        return StateImpl.of(name, value);
    }

    @Override
    public State state(Name name, Name value) {
        return StateImpl.of(name, value);
    }

    @Override
    public State state(Name name, State value) {
        return StateImpl.of(name, value);
    }

    // ========== Sink Creation (2 methods) ==========

    @Override
    public <E> Sink<E> sink(Source<E> source) {
        Objects.requireNonNull(source, "Source cannot be null");
        return new SinkImpl<>(source);
    }

    @Override
    public <E> Sink<E> sink(Context<E> context) {
        Objects.requireNonNull(context, "Context cannot be null");
        return sink(context.source());
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

    // ========== Subscriber Management (2 methods) ==========

    @Override
    public <E> Subscriber<E> subscriber(Name name, BiConsumer<Subject, Registrar<E>> fn) {
        return new SubscriberImpl<>(name, fn);
    }

    @Override
    public <E> Subscriber<E> subscriber(Name name, Pool<? extends Pipe<E>> pool) {
        return new SubscriberImpl<>(name, pool);
    }

    // ========== Capture Creation (1 method) ==========

    @Override
    public <E> Capture<E> capture(Subject subject, E emission) {
        Objects.requireNonNull(subject, "Capture subject cannot be null");
        return new CaptureImpl<>(subject, emission);
    }
}
