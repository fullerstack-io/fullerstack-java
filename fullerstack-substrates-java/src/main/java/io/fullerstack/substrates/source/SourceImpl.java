package io.fullerstack.substrates.source;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.name.NameImpl;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implementation of Substrates.Source for event emission and subscriber management.
 *
 * <p>SourceImpl is an event dispatcher that implements both Source and Pipe interfaces:
 * <ul>
 *   <li><b>Source interface</b> - Allows external code to subscribe and observe emissions</li>
 *   <li><b>Pipe interface</b> - Allows internal code (Conduit) to emit events to subscribers</li>
 * </ul>
 *
 * <p><b>Data Flow:</b>
 * <ol>
 *   <li>Conduit calls {@code source.emit(value)} via Pipe interface</li>
 *   <li>SourceImpl dispatches to all Subscribers</li>
 *   <li>Each Subscriber registers consumer Pipes via Registrar</li>
 *   <li>Emission is forwarded to all registered consumer Pipes</li>
 * </ol>
 *
 * <p>Manages subscribers with thread-safe CopyOnWriteArrayList, suitable for
 * read-heavy workloads (many emissions, fewer subscribe/unsubscribe operations).
 *
 * @param <E> event emission type
 * @see Source
 * @see Pipe
 * @see Subscriber
 * @see Registrar
 */
public class SourceImpl<E> implements Source<E>, Pipe<E> {
    private final List<Subscriber<E>> subscribers = new CopyOnWriteArrayList<>();
    private final Subject sourceSubject;

    /**
     * Creates a source with generated ID and default name.
     */
    public SourceImpl() {
        this(NameImpl.of("source"));
    }

    /**
     * Creates a source with the specified name.
     *
     * @param name source name
     */
    public SourceImpl(Name name) {
        Objects.requireNonNull(name, "Source name cannot be null");
        this.sourceSubject = new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Subject.Type.SOURCE
        );
    }

    @Override
    public Subject subject() {
        return sourceSubject;
    }

    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        Objects.requireNonNull(subscriber, "Subscriber cannot be null");
        subscribers.add(subscriber);

        // Return subscription that removes subscriber on close()
        return new Subscription() {
            private volatile boolean closed = false;

            @Override
            public Subject subject() {
                return new SubjectImpl(
                    IdImpl.generate(),
                    NameImpl.of("subscription"),
                    StateImpl.empty(),
                    Subject.Type.SUBSCRIPTION
                );
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    subscribers.remove(subscriber);
                }
            }
        };
    }

    /**
     * Emits an event to all subscribers.
     *
     * <p>This implements the Pipe interface, allowing the Conduit to emit events
     * into the Source, which then dispatches them to all registered subscriber Pipes.
     *
     * <p>Called by Conduit's queue processor when a Channel emits a value.
     *
     * @param emission the event to emit
     */
    @Override
    public void emit(E emission) {
        for (Subscriber<E> subscriber : subscribers) {
            // Collect pipes that the subscriber registers
            List<Pipe<E>> pipes = new CopyOnWriteArrayList<>();

            // Invoke the subscriber, which will register its pipes
            subscriber.accept(sourceSubject, new Registrar<E>() {
                @Override
                public void register(Pipe<E> pipe) {
                    pipes.add(pipe);
                }
            });

            // Now emit to all registered pipes
            for (Pipe<E> pipe : pipes) {
                pipe.emit(emission);
            }
        }
    }

    /**
     * Internal Capture implementation for event delivery.
     */
    private record CaptureImpl<E>(Subject subject, E emission) implements Capture<E> {
    }
}
