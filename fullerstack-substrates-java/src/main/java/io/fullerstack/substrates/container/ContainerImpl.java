package io.fullerstack.substrates.container;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;

import java.util.Objects;

/**
 * Implementation of Substrates.Container combining Pool and Component.
 *
 * <p>Container is typically created by wrapping a Conduit, which provides:
 * <ul>
 *   <li>Pool behavior - Conduit.get(Name) returns percepts composed from Channels</li>
 *   <li>Source behavior - Conduit.source() emits events from those percepts</li>
 * </ul>
 *
 * <p>The Container API exposes Container<Pool<P>, Source<E>>, meaning:
 * <ul>
 *   <li>get(Name) returns the Pool (usually the Conduit itself)</li>
 *   <li>source() returns Source<Source<E>> for nested subscription pattern</li>
 * </ul>
 *
 * @param <P> percept type (what the Pool contains)
 * @param <E> event emission type (what the Source emits)
 * @see Container
 */
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    private final Pool<P> pool;
    private final Source<E> eventSource;
    private final Source<Source<E>> containerSource;
    private final Pipe<Source<E>> emitter; // Producer API
    private final Name name;

    /**
     * Creates a container with the specified pool and source.
     *
     * <p>Typically called with a Conduit:
     * <pre>
     * Conduit<P, E> conduit = circuit.conduit(name, composer);
     * Container<Pool<P>, Source<E>> container = new ContainerImpl<>(conduit, conduit.source());
     * </pre>
     *
     * @param pool the pool implementation (usually a Conduit)
     * @param source the source implementation for events
     */
    public ContainerImpl(Pool<P> pool, Source<E> source) {
        this.pool = Objects.requireNonNull(pool, "Pool cannot be null");
        this.eventSource = Objects.requireNonNull(source, "Source cannot be null");
        this.name = source.subject().name();

        // Container<Pool<P>, Source<E>> means source() returns Source<Source<E>>
        // This enables the nested subscription pattern from William Louth's examples:
        // container.source().subscribe(subject -> source -> source.subscribe(...))
        SourceImpl<Source<E>> nestedSource = new SourceImpl<>(name);
        this.containerSource = nestedSource;
        this.emitter = nestedSource; // SourceImpl implements both Source and Pipe
        // Emit the eventSource so subscribers can get it
        this.emitter.emit(eventSource);
    }

    @Override
    public Subject subject() {
        return new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Subject.Type.CONTAINER
        );
    }

    @Override
    public Pool<P> get(Name name) {
        return pool;
    }

    @Override
    public Source<Source<E>> source() {
        return containerSource;
    }

    /**
     * Provides access to the event source for subscribers.
     * This is a convenience method since Container API requires Source<Source<E>>.
     *
     * @return the event source
     */
    public Source<E> eventSource() {
        return eventSource;
    }

    @Override
    public void close() {
        // Container doesn't manage pool/source lifecycle
        // They are passed in and managed externally
    }
}
