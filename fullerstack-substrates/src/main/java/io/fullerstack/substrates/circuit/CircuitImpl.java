package io.fullerstack.substrates.circuit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.clock.ClockImpl;
import io.fullerstack.substrates.container.ContainerImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pool.PoolImpl;
import io.fullerstack.substrates.queue.QueueFactory;
import io.fullerstack.substrates.queue.LinkedBlockingQueueFactory;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.name.NameFactory;
import io.fullerstack.substrates.name.InternedNameFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of Substrates.Circuit for component orchestration.
 *
 * <p>Manages Queue, Clock, Conduit, and Container components with lazy initialization
 * and caching by name.
 *
 * <p>Features:
 * <ul>
 *   <li>Single Queue for backpressure management</li>
 *   <li>Clock caching by name</li>
 *   <li>Conduit caching by (name, composer type) - different composers create different conduits</li>
 *   <li>Container caching by (name, composer type) - different composers create different containers</li>
 *   <li>Container composition of Pool and Source</li>
 *   <li>State event sourcing via SourceImpl</li>
 *   <li>Resource lifecycle management</li>
 * </ul>
 *
 * @see Circuit
 */
public class CircuitImpl implements Circuit {
    private final NameFactory nameFactory;
    private final QueueFactory queueFactory;
    private final Subject circuitSubject;
    private final Source<State> stateSource;
    private final Queue queue; // Virtual thread is daemon - auto-cleanup on JVM shutdown
    private final Map<Name, Clock> clocks = new ConcurrentHashMap<>();
    private final Map<ConduitKey, Conduit<?, ?>> conduits = new ConcurrentHashMap<>();
    private final Map<ContainerKey, Container<?, ?>> containers = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Composite key for Conduit caching.
     * Conduits are cached by both name AND composer type, since different
     * composers create different percept types (Pipe vs Channel vs domain types).
     */
    private record ConduitKey(Name name, Class<?> composerClass) {}

    /**
     * Composite key for Container caching.
     * Containers are cached by both name AND composer type, since different
     * composers create different percept types (Pipe vs Channel vs domain types).
     */
    private record ContainerKey(Name name, Class<?> composerClass) {}

    /**
     * Creates a circuit with the specified name using defaults:
     * {@link InternedNameFactory} and {@link LinkedBlockingQueueFactory}.
     *
     * @param name circuit name
     */
    public CircuitImpl(Name name) {
        this(name, InternedNameFactory.getInstance(), LinkedBlockingQueueFactory.getInstance());
    }

    /**
     * Creates a circuit with the specified name and custom {@link NameFactory},
     * using default {@link LinkedBlockingQueueFactory}.
     *
     * @param name circuit name
     * @param nameFactory the factory to use for creating Name instances
     */
    public CircuitImpl(Name name, NameFactory nameFactory) {
        this(name, nameFactory, LinkedBlockingQueueFactory.getInstance());
    }

    /**
     * Creates a circuit with the specified name and custom factories.
     *
     * @param name circuit name
     * @param nameFactory the factory to use for creating Name instances
     * @param queueFactory the factory to use for creating Queue instances
     */
    public CircuitImpl(Name name, NameFactory nameFactory, QueueFactory queueFactory) {
        Objects.requireNonNull(name, "Circuit name cannot be null");
        this.nameFactory = Objects.requireNonNull(nameFactory, "NameFactory cannot be null");
        this.queueFactory = Objects.requireNonNull(queueFactory, "QueueFactory cannot be null");
        Id id = IdImpl.generate();
        this.circuitSubject = new SubjectImpl(
            id,
            name,
            StateImpl.empty(),
            Subject.Type.CIRCUIT
        );
        this.stateSource = new SourceImpl<>(name);
        this.queue = queueFactory.create();
    }

    @Override
    public Subject subject() {
        return circuitSubject;
    }

    @Override
    public Source<State> source() {
        return stateSource;
    }

    @Override
    public Queue queue() {
        checkClosed();
        return queue;
    }

    @Override
    public Clock clock() {
        return clock(nameFactory.createRoot("clock"));
    }

    @Override
    public Clock clock(Name name) {
        checkClosed();
        Objects.requireNonNull(name, "Clock name cannot be null");
        return clocks.computeIfAbsent(name, ClockImpl::new);
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Composer<? extends P, E> composer) {
        return conduit(nameFactory.createRoot("conduit"), composer);
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer) {
        checkClosed();
        Objects.requireNonNull(name, "Conduit name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");

        // Build hierarchical name: circuit.conduit using factory for compatibility
        String circuitPath = circuitSubject.name().value();
        String conduitPath = name.value();
        Name hierarchicalName = nameFactory.create(circuitPath + "." + conduitPath);

        ConduitKey key = new ConduitKey(name, composer.getClass());
        @SuppressWarnings("unchecked")
        Conduit<P, E> conduit = (Conduit<P, E>) conduits.computeIfAbsent(
            key,
            k -> new io.fullerstack.substrates.conduit.ConduitImpl<>(hierarchicalName, composer, queue)
        );
        return conduit;
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer, Sequencer<Segment<E>> sequencer) {
        checkClosed();
        Objects.requireNonNull(name, "Conduit name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");
        Objects.requireNonNull(sequencer, "Sequencer cannot be null");

        // Build hierarchical name: circuit.conduit using factory for compatibility
        String circuitPath = circuitSubject.name().value();
        String conduitPath = name.value();
        Name hierarchicalName = nameFactory.create(circuitPath + "." + conduitPath);

        // Create Conduit with Sequencer - transformations apply to ALL channels in this Conduit
        ConduitKey key = new ConduitKey(name, composer.getClass());
        @SuppressWarnings("unchecked")
        Conduit<P, E> conduit = (Conduit<P, E>) conduits.computeIfAbsent(
            key,
            k -> new io.fullerstack.substrates.conduit.ConduitImpl<>(hierarchicalName, composer, queue, sequencer)
        );
        return conduit;
    }

    @Override
    public <P, E> Container<Pool<P>, Source<E>> container(Composer<P, E> composer) {
        return container(nameFactory.createRoot("container"), composer);
    }

    @Override
    public <P, E> Container<Pool<P>, Source<E>> container(Name name, Composer<P, E> composer) {
        checkClosed();
        Objects.requireNonNull(name, "Container name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");

        // Container manages a collection of Conduits
        // Cached by (name, composer) - same as Conduit caching pattern
        ContainerKey key = new ContainerKey(name, composer.getClass());
        @SuppressWarnings("unchecked")
        Container<Pool<P>, Source<E>> container = (Container<Pool<P>, Source<E>>) containers.computeIfAbsent(
            key,
            k -> new ContainerImpl<>(name, this, composer)
        );
        return container;
    }

    @Override
    public <P, E> Container<Pool<P>, Source<E>> container(Name name, Composer<P, E> composer, Sequencer<Segment<E>> sequencer) {
        checkClosed();
        Objects.requireNonNull(name, "Container name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");
        Objects.requireNonNull(sequencer, "Sequencer cannot be null");

        // Container with Sequencer - applies transformations to all Conduits created by Container
        // Cached by (name, composer) - same as Conduit caching pattern
        ContainerKey key = new ContainerKey(name, composer.getClass());
        @SuppressWarnings("unchecked")
        Container<Pool<P>, Source<E>> container = (Container<Pool<P>, Source<E>>) containers.computeIfAbsent(
            key,
            k -> new ContainerImpl<>(name, this, composer, sequencer)
        );
        return container;
    }

    @Override
    public Circuit tap(java.util.function.Consumer<? super Circuit> consumer) {
        checkClosed();
        Objects.requireNonNull(consumer, "Consumer cannot be null");
        consumer.accept(this);
        return this;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;

            // Close all clocks
            clocks.values().forEach(clock -> {
                try {
                    clock.close();
                } catch (Exception e) {
                    // Log but continue
                }
            });

            // Close all containers
            containers.values().forEach(container -> {
                try {
                    container.close();
                } catch (Exception e) {
                    // Log but continue
                }
            });

            // Note: Queue uses daemon virtual thread - no explicit shutdown needed
            // Virtual threads are automatically cleaned up on JVM shutdown

            clocks.clear();
            conduits.clear();
            containers.clear();
        }
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("Circuit is closed");
        }
    }
}
