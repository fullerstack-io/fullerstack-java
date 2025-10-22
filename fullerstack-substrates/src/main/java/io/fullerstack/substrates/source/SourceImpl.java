package io.fullerstack.substrates.source;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.capture.CaptureImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.name.NameTree;
import io.fullerstack.substrates.subscription.SubscriptionImpl;

import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Implementation of Substrates.Source for subscriber management with Lombok for getters.
 *
 * <p>SourceImpl manages Subscribers and provides the observable stream interface.
 *
 * <p>Manages subscribers with thread-safe CopyOnWriteArrayList, suitable for
 * read-heavy workloads (many emissions, fewer subscribe/unsubscribe operations).
 *
 * <p>Owns the pipe cache for efficient multi-dispatch to subscribers. Pipes are
 * registered lazily on first emission from each Subject and cached for subsequent
 * emissions. This cache belongs here because it's fundamentally about subscriber
 * management - tracking which Pipes to notify for each Subscriber.
 *
 * <p><b>Sibling Coordination:</b> Source and Channel are siblings owned by Conduit.
 * Channel creates inlet Pipes by requesting an emission handler from Source via
 * {@link #emissionHandler()}. This allows Source to keep its distribution logic
 * private while providing Channels with the callback they need for Pipe creation.
 *
 * @param <E> event emission type
 * @see Source
 * @see Subscriber
 */
@Getter
public class SourceImpl<E> implements Source<E> {
    private final List<Subscriber<E>> subscribers = new CopyOnWriteArrayList<>();
    private final Subject<?> sourceSubject;

    // Cache: Subject Name -> Subscriber -> List of registered Pipes
    // Pipes are registered only once per Subject per Subscriber (on first emission)
    private final Map<Name, Map<Subscriber<E>, List<Pipe<E>>>> pipeCache = new ConcurrentHashMap<>();

    /**
     * Creates a source with default name using NameTree.of().
     */
    public SourceImpl() {
        this(NameTree.of("source"));
    }

    /**
     * Creates a source with the specified name.
     *
     * @param name source name
     */
    @SuppressWarnings("unchecked")
    public SourceImpl(Name name) {
        Objects.requireNonNull(name, "Source name cannot be null");
        // Source doesn't extend Substrate, so we use wildcard Subject
        this.sourceSubject = new SubjectImpl(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Source.class
        );
    }

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
     * Checks if there are any active subscribers.
     *
     * <p>Used by Pipes for early exit optimization - avoids allocating Capture
     * and posting Script when no subscribers are registered.
     *
     * @return true if at least one subscriber exists, false otherwise
     */
    public boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    /**
     * Provides an emission handler for inlet Pipe creation.
     *
     * <p>This method enables sibling coordination between Source and Channel.
     * Channel requests this handler when creating inlet Pipes, allowing the
     * Pipe to notify subscribers without exposing Source's internal distribution logic.
     *
     * <p><b>Design Pattern:</b> Source and Channel are siblings owned by Conduit.
     * This is NOT callback passing through layers - it's dependency injection at
     * construction time between siblings.
     *
     * @return callback that routes emissions to subscribers
     */
    public Consumer<Capture<E, Channel<E>>> emissionHandler() {
        return this::notifySubscribers;
    }

    /**
     * PRIVATE: Notifies all subscribers of an emission.
     *
     * <p>This method is private and only accessible via the emission handler callback
     * provided to inlet Pipes through {@link #emissionHandler()}. Handles:
     * <ul>
     *   <li>Lazy pipe registration - subscriber.accept() called only on first emission from each Subject</li>
     *   <li>Pipe caching - reuses registered pipes for subsequent emissions</li>
     *   <li>Multi-dispatch - routes to all registered pipes for each subscriber</li>
     * </ul>
     *
     * <p>Source owns the pipe cache because it's fundamentally about subscriber management -
     * tracking which Pipes to notify for each Subscriber/Subject combination.
     *
     * <p><b>Refactored to functional style:</b> Uses streams instead of imperative loops
     * for clearer intent and composability.
     *
     * @param capture the emission capture (Subject + value)
     */
    private void notifySubscribers(Capture<E, Channel<E>> capture) {
        Subject<Channel<E>> emittingSubject = capture.subject();
        Name subjectName = emittingSubject.name();

        // Get or create the subscriber->pipes map for this Subject
        Map<Subscriber<E>, List<Pipe<E>>> subscriberPipes = pipeCache.computeIfAbsent(
            subjectName,
            name -> new ConcurrentHashMap<>()
        );

        // Functional stream pipeline: resolve pipes for each subscriber, then emit
        subscribers.stream()
            .flatMap(subscriber ->
                resolvePipes(subscriber, emittingSubject, subscriberPipes).stream()
            )
            .forEach(pipe -> pipe.emit(capture.emission()));
    }

    /**
     * Resolves pipes for a subscriber, registering them on first emission from a subject.
     *
     * @param subscriber the subscriber
     * @param emittingSubject the subject emitting
     * @param subscriberPipes cache of subscriber->pipes
     * @return list of pipes for this subscriber
     */
    private List<Pipe<E>> resolvePipes(
        Subscriber<E> subscriber,
        Subject emittingSubject,
        Map<Subscriber<E>, List<Pipe<E>>> subscriberPipes
    ) {
        return subscriberPipes.computeIfAbsent(subscriber, sub -> {
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
    }

}
