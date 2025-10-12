package io.fullerstack.substrates.source;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.name.NameImpl;
import io.fullerstack.substrates.subscription.SubscriptionImpl;
import io.fullerstack.substrates.sink.CaptureImpl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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
        return new SubscriptionImpl(() -> subscribers.remove(subscriber));
    }

    /**
     * INTERNAL: Notifies all subscribers of an emission.
     *
     * <p>This is an implementation method (not part of the Substrates API interface)
     * used by ConduitImpl to route emissions to subscribers. Handles:
     * <ul>
     *   <li>Lazy pipe registration - subscriber.accept() called only on first emission from each Subject</li>
     *   <li>Pipe caching - reuses registered pipes for subsequent emissions</li>
     *   <li>Multi-dispatch - routes to all registered pipes for each subscriber</li>
     * </ul>
     *
     * @param capture the emission capture (Subject + value)
     * @param pipeCache cache of registered pipes per (Subject Name, Subscriber)
     */
    public void notifySubscribers(
        Capture<E> capture,
        Map<Name, Map<Subscriber<E>, List<Pipe<E>>>> pipeCache
    ) {
        Subject emittingSubject = capture.subject();
        Name subjectName = emittingSubject.name();

        // Get or create the subscriber->pipes map for this Subject
        Map<Subscriber<E>, List<Pipe<E>>> subscriberPipes = pipeCache.computeIfAbsent(
            subjectName,
            name -> new ConcurrentHashMap<>()
        );

        // For each subscriber, get cached pipes or register new ones (first emission only)
        for (Subscriber<E> subscriber : subscribers) {
            List<Pipe<E>> pipes = subscriberPipes.computeIfAbsent(subscriber, sub -> {
                // First emission from this Subject - call subscriber.accept() to register pipes
                List<Pipe<E>> registeredPipes = new CopyOnWriteArrayList<>();

                sub.accept(emittingSubject, new Registrar<E>() {
                    @Override
                    public void register(Pipe<E> pipe) {
                        registeredPipes.add(pipe);
                    }
                });

                return registeredPipes;
            });

            // Emit to all registered pipes (cached or newly registered)
            for (Pipe<E> pipe : pipes) {
                pipe.emit(capture.emission());
            }
        }
    }

}
