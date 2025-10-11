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
 * Implementation of Substrates.Source for event routing and subscriber management.
 *
 * <p>SourceImpl is a routing mechanism that connects emitting Subjects (Channels) with Subscribers:
 * <ul>
 *   <li><b>Source interface</b> - Allows external code to subscribe and observe emissions</li>
 *   <li><b>notify(Capture)</b> - Package-visible method for Conduit to dispatch Channel emissions</li>
 * </ul>
 *
 * <p><b>Data Flow:</b>
 * <ol>
 *   <li>Conduit calls {@code source.notify(capture)} with Channel's Subject + emission</li>
 *   <li>SourceImpl dispatches to all Subscribers with the CHANNEL's Subject (not Source's)</li>
 *   <li>Each Subscriber registers consumer Pipes via Registrar</li>
 *   <li>Emission is forwarded to all registered consumer Pipes</li>
 * </ol>
 *
 * <p><b>Critical Design Point:</b> Source is NOT an emitter - it's a connection mechanism.
 * The actual emitting Subjects are Channels, and their identity must be preserved when
 * dispatching to Subscribers for hierarchical routing.
 *
 * <p>Manages subscribers with thread-safe CopyOnWriteArrayList, suitable for
 * read-heavy workloads (many emissions, fewer subscribe/unsubscribe operations).
 *
 * @param <E> event emission type
 * @see Source
 * @see Subscriber
 * @see Registrar
 * @see Capture
 */
public class SourceImpl<E> implements Source<E> {
    private final List<Subscriber<E>> subscribers = new CopyOnWriteArrayList<>();
    private final Subject sourceSubject;

    /**
     * Creates a source with default name.
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
        // Each subscription has unique ID and stable Subject
        return new Subscription() {
            private volatile boolean closed = false;
            private final Id subscriptionId = IdImpl.generate();
            private final Subject subscriptionSubject = new SubjectImpl(
                subscriptionId,
                NameImpl.of("subscription").name(subscriptionId.toString()),
                StateImpl.empty(),
                Subject.Type.SUBSCRIPTION
            );

            @Override
            public Subject subject() {
                return subscriptionSubject;
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
     * Notifies all subscribers of a captured emission from a Channel.
     *
     * <p>Public method called by Conduit's queue processor to dispatch emissions.
     * Unlike a Pipe's emit(), this method preserves the emitting Channel's Subject identity,
     * allowing Subscribers to perform hierarchical routing based on WHO emitted.
     *
     * <p><b>Critical:</b> Passes capture.subject() (the Channel's Subject) to subscribers,
     * NOT sourceSubject. This is essential for Humainary's hierarchical routing design.
     *
     * @param capture the captured emission (Channel's Subject + value)
     */
    public void notify(Capture<E> capture) {
        for (Subscriber<E> subscriber : subscribers) {
            // Collect pipes that the subscriber registers
            List<Pipe<E>> pipes = new CopyOnWriteArrayList<>();

            // Invoke the subscriber with the CHANNEL's Subject (from capture)
            // This enables hierarchical routing based on the emitting Channel
            subscriber.accept(capture.subject(), new Registrar<E>() {
                @Override
                public void register(Pipe<E> pipe) {
                    pipes.add(pipe);
                }
            });

            // Now emit to all registered pipes
            for (Pipe<E> pipe : pipes) {
                pipe.emit(capture.emission());
            }
        }
    }
}
