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
 * Implementation of Substrates.Source for subscriber management.
 *
 * <p>SourceImpl manages Subscribers and provides the observable stream interface.
 *
 * <p>Manages subscribers with thread-safe CopyOnWriteArrayList, suitable for
 * read-heavy workloads (many emissions, fewer subscribe/unsubscribe operations).
 *
 * @param <E> event emission type
 * @see Source
 * @see Subscriber
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
     * INTERNAL: Returns subscribers for Conduit processing.
     *
     * TODO: This violates strict API compliance. Should either:
     * 1. Move SourceImpl and ConduitImpl to same package for package-private access
     * 2. Redesign architecture so Conduit doesn't need direct subscriber access
     * 3. Have Source handle invocation instead of exposing subscribers
     *
     * @return list of subscribers
     */
    public List<Subscriber<E>> getSubscribers() {
        return subscribers;
    }

}
