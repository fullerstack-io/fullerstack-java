package io.fullerstack.substrates.source;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.util.NameImpl;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implementation of Substrates.Source for event emission and subscriber management.
 *
 * <p>Implements both Source (consumer API) and Pipe (producer API) to provide
 * a complete event bus: components can emit() to it, and others can subscribe() to it.
 *
 * <p>Manages subscribers with thread-safe CopyOnWriteArrayList, suitable for
 * read-heavy workloads (many emissions, fewer subscribe/unsubscribe operations).
 *
 * <p>Features:
 * <ul>
 *   <li>Thread-safe subscriber management</li>
 *   <li>Subscription lifecycle with close() support</li>
 *   <li>Pipe.emit() for event delivery (producer API)</li>
 *   <li>Source.subscribe() for event subscription (consumer API)</li>
 * </ul>
 *
 * @param <E> event emission type
 * @see Source
 * @see Pipe
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
     * <p>This implements the Pipe interface, allowing SourceImpl to act as both
     * a producer (via emit) and a consumer subscription manager (via subscribe).
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
