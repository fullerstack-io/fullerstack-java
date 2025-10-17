package io.fullerstack.substrates;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.capture.CaptureImpl;
import io.fullerstack.substrates.circuit.CircuitImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pool.PoolImpl;
import io.fullerstack.substrates.scope.ScopeImpl;
import io.fullerstack.substrates.slot.SlotImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.subscriber.SubscriberImpl;
import io.fullerstack.substrates.name.NameFactory;
import io.fullerstack.substrates.name.InternedNameFactory;
import io.fullerstack.substrates.queue.QueueFactory;
import io.fullerstack.substrates.queue.LinkedBlockingQueueFactory;
import io.fullerstack.substrates.sink.SinkImpl;
import io.fullerstack.substrates.registry.LazyTrieRegistry;
import io.fullerstack.substrates.registry.RegistryFactory;
import io.fullerstack.substrates.registry.LazyTrieRegistryFactory;

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
 * <p><b>Factory Injection:</b>
 * CortexRuntime uses constructor injection for pluggable implementations:
 * <ul>
 *   <li>{@link NameFactory} - Pluggable Name implementations (default: {@link InternedNameFactory})</li>
 *   <li>{@link QueueFactory} - Pluggable Queue implementations (default: {@link LinkedBlockingQueueFactory})</li>
 *   <li>{@link RegistryFactory} - Pluggable Registry implementations (default: {@link LazyTrieRegistryFactory})</li>
 * </ul>
 *
 * @see Cortex
 * @see NameFactory
 * @see QueueFactory
 * @see RegistryFactory
 */
public class CortexRuntime implements Cortex {

    private final NameFactory nameFactory;
    private final QueueFactory queueFactory;
    private final RegistryFactory registryFactory;
    private final Map<Name, Circuit> circuits;
    private final Map<Name, Scope> scopes;
    private final Scope defaultScope;

    /**
     * Creates a new Cortex runtime with defaults:
     * {@link InternedNameFactory}, {@link LinkedBlockingQueueFactory}, and {@link LazyTrieRegistryFactory}.
     *
     * <p>This is the recommended default constructor.
     */
    public CortexRuntime() {
        this(InternedNameFactory.getInstance(), LinkedBlockingQueueFactory.getInstance(), LazyTrieRegistryFactory.getInstance());
    }

    /**
     * Creates a new Cortex runtime with a custom {@link NameFactory},
     * using default {@link LinkedBlockingQueueFactory} and {@link LazyTrieRegistryFactory}.
     *
     * <p>This constructor allows you to inject a different Name implementation,
     * useful for testing or when you need specific Name characteristics.
     *
     * <p><b>Available Name factories:</b>
     * <ul>
     *   <li>{@link InternedNameFactory} - Recommended default (weak interning)</li>
     *   <li>{@link io.fullerstack.substrates.name.LinkedNameFactory} - Simple baseline</li>
     *   <li>{@link io.fullerstack.substrates.name.SegmentArrayNameFactory} - Array-backed</li>
     *   <li>{@link io.fullerstack.substrates.name.LRUCachedNameFactory} - LRU cache</li>
     * </ul>
     *
     * @param nameFactory the factory to use for creating Name instances
     * @throws NullPointerException if nameFactory is null
     */
    public CortexRuntime(NameFactory nameFactory) {
        this(nameFactory, LinkedBlockingQueueFactory.getInstance(), LazyTrieRegistryFactory.getInstance());
    }

    /**
     * Creates a new Cortex runtime with custom factories.
     *
     * <p>This constructor provides full control over pluggable implementations.
     *
     * <p><b>Available Queue factories:</b>
     * <ul>
     *   <li>{@link LinkedBlockingQueueFactory} - Unbounded FIFO (recommended)</li>
     * </ul>
     *
     * <p><b>Available Registry factories:</b>
     * <ul>
     *   <li>{@link LazyTrieRegistryFactory} - Lazy trie construction (recommended)</li>
     *   <li>{@link io.fullerstack.substrates.registry.EagerTrieRegistryFactory} - Eager trie</li>
     *   <li>{@link io.fullerstack.substrates.registry.FlatMapRegistryFactory} - Simple HashMap</li>
     * </ul>
     *
     * @param nameFactory the factory to use for creating Name instances
     * @param queueFactory the factory to use for creating Queue instances
     * @param registryFactory the factory to use for creating Registry instances
     * @throws NullPointerException if any factory is null
     */
    @SuppressWarnings("unchecked")
    public CortexRuntime(NameFactory nameFactory, QueueFactory queueFactory, RegistryFactory registryFactory) {
        this.nameFactory = Objects.requireNonNull(nameFactory, "NameFactory cannot be null");
        this.queueFactory = Objects.requireNonNull(queueFactory, "QueueFactory cannot be null");
        this.registryFactory = Objects.requireNonNull(registryFactory, "RegistryFactory cannot be null");
        this.circuits = (Map<Name, Circuit>) registryFactory.create();
        this.scopes = (Map<Name, Scope>) registryFactory.create();
        Name cortexName = nameFactory.createRoot("cortex");
        this.defaultScope = new ScopeImpl(cortexName, registryFactory);
        // Cache default scope by its name
        this.scopes.put(cortexName, defaultScope);
    }

    // ========== Circuit Management (2 methods) ==========

    @Override
    public Circuit circuit() {
        return circuit(nameFactory.createRoot("circuit"));
    }

    @Override
    public Circuit circuit(Name name) {
        Objects.requireNonNull(name, "Circuit name cannot be null");
        return circuits.computeIfAbsent(name, this::createCircuit);
    }

    private Circuit createCircuit(Name name) {
        return new CircuitImpl(name, nameFactory, queueFactory, registryFactory);
    }

    // ========== Name Factory (8 methods) ==========

    @Override
    public Name name(String s) {
        return nameFactory.create(s);
    }

    @Override
    public Name name(Enum<?> e) {
        return nameFactory.create(e);
    }

    @Override
    public Name name(Iterable<String> parts) {
        return nameFactory.create(parts);
    }

    @Override
    public <T> Name name(Iterable<? extends T> items, Function<T, String> mapper) {
        return nameFactory.create(items, mapper);
    }

    @Override
    public Name name(Iterator<String> parts) {
        return nameFactory.create(parts);
    }

    @Override
    public <T> Name name(Iterator<? extends T> items, Function<T, String> mapper) {
        return nameFactory.create(items, mapper);
    }

    @Override
    public Name name(Class<?> clazz) {
        return nameFactory.create(clazz);
    }

    @Override
    public Name name(Member member) {
        return nameFactory.create(member);
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
        return scopes.computeIfAbsent(name, n -> new ScopeImpl(n, registryFactory));
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
