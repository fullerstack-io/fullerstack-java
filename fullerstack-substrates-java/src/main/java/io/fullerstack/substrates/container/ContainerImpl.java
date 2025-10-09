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
 * <p>Provides unified access to pooled resources and event sourcing through
 * composition of Pool and Source implementations.
 *
 * <p>Features:
 * <ul>
 *   <li>Pool interface delegation for resource lookup via get()</li>
 *   <li>Component interface for source() access</li>
 *   <li>Proper Subject with CONTAINER type</li>
 * </ul>
 *
 * @param <P> pool type
 * @param <E> event emission type
 * @see Container
 */
public class ContainerImpl<P, E> implements Container<Pool<P>, Source<E>> {
    private final Pool<P> pool;
    private final Source<E> eventSource;
    private final SourceImpl<Source<E>> containerSource;
    private final Name name;

    /**
     * Creates a container with the specified pool and source.
     *
     * @param pool the pool implementation
     * @param source the source implementation for events
     */
    public ContainerImpl(Pool<P> pool, Source<E> source) {
        this.pool = Objects.requireNonNull(pool, "Pool cannot be null");
        this.eventSource = Objects.requireNonNull(source, "Source cannot be null");
        this.name = source.subject().name();

        // Container<Pool<P>, Source<E>> means source() returns Source<Source<E>>
        // Create a wrapper that emits the event source itself
        this.containerSource = new SourceImpl<>(name);
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
