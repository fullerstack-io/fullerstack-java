package io.fullerstack.substrates.circuit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.clock.ClockImpl;
import io.fullerstack.substrates.container.ContainerImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.pool.PoolImpl;
import io.fullerstack.substrates.queue.QueueImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.util.NameImpl;

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
 *   <li>Conduit caching by name (stub for Story 4.12)</li>
 *   <li>Container composition of Pool and Source</li>
 *   <li>State event sourcing via SourceImpl</li>
 *   <li>Resource lifecycle management</li>
 * </ul>
 *
 * @see Circuit
 */
public class CircuitImpl implements Circuit {
    private final Name name;
    private final SourceImpl<State> stateSource;
    private final QueueImpl queue;
    private final Map<Name, Clock> clocks = new ConcurrentHashMap<>();
    private final Map<Name, Conduit<?, ?>> conduits = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    /**
     * Creates a circuit with the specified name.
     *
     * @param name circuit name
     */
    public CircuitImpl(Name name) {
        this.name = Objects.requireNonNull(name, "Circuit name cannot be null");
        this.stateSource = new SourceImpl<>(name);
        this.queue = new QueueImpl();
    }

    @Override
    public Subject subject() {
        return new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Subject.Type.CIRCUIT
        );
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
        return clock(NameImpl.of("default"));
    }

    @Override
    public Clock clock(Name name) {
        checkClosed();
        Objects.requireNonNull(name, "Clock name cannot be null");
        return clocks.computeIfAbsent(name, ClockImpl::new);
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Composer<? extends P, E> composer) {
        return conduit(NameImpl.of("default"), composer);
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer) {
        checkClosed();
        Objects.requireNonNull(name, "Conduit name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");

        @SuppressWarnings("unchecked")
        Conduit<P, E> conduit = (Conduit<P, E>) conduits.computeIfAbsent(
            name,
            n -> new io.fullerstack.substrates.conduit.ConduitImpl<>(this.name, n, composer)
        );
        return conduit;
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Name name, Composer<? extends P, E> composer, Sequencer<Segment<E>> sequencer) {
        checkClosed();
        Objects.requireNonNull(name, "Conduit name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");
        Objects.requireNonNull(sequencer, "Sequencer cannot be null");

        // TODO: Implement sequencer support
        // For now, just create conduit without sequencer
        return conduit(name, composer);
    }

    @Override
    public <P, E> Container<Pool<P>, Source<E>> container(Composer<P, E> composer) {
        return container(NameImpl.of("default"), composer);
    }

    @Override
    public <P, E> Container<Pool<P>, Source<E>> container(Name name, Composer<P, E> composer) {
        checkClosed();
        Objects.requireNonNull(name, "Container name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");

        // Create a Conduit that will use the Composer to create percepts
        Conduit<P, E> conduit = conduit(name, composer);

        // Container wraps the Conduit, exposing it as Pool + Source
        return new ContainerImpl<>(conduit, conduit.source());
    }

    @Override
    public <P, E> Container<Pool<P>, Source<E>> container(Name name, Composer<P, E> composer, Sequencer<Segment<E>> sequencer) {
        checkClosed();
        Objects.requireNonNull(name, "Container name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");
        Objects.requireNonNull(sequencer, "Sequencer cannot be null");

        // Create conduit with sequencer
        Conduit<P, E> conduit = conduit(name, composer, sequencer);

        return new ContainerImpl<>(conduit, conduit.source());
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

            // Close queue
            try {
                queue.shutdown();
            } catch (Exception e) {
                // Log but continue
            }

            clocks.clear();
            conduits.clear();
        }
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("Circuit is closed");
        }
    }
}
