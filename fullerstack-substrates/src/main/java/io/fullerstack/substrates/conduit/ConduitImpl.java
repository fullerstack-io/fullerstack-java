package io.fullerstack.substrates.conduit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.ChannelImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.subscription.SubscriptionImpl;
import io.fullerstack.substrates.circuit.Scheduler;

import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Generic implementation of Substrates.Conduit interface with Lombok for getters.
 *
 * <p><b>Type System Foundation:</b>
 * Conduit&lt;P, E&gt; IS-A Subject&lt;Conduit&lt;P, E&gt;&gt; (via sealed hierarchy: Component → Context → Source → Conduit).
 * This means every Conduit is itself a Subject that can be subscribed to, and it emits values of type E
 * via its internal Channels. Subscribers receive Subject&lt;Channel&lt;E&gt;&gt; and can dynamically
 * register Pipes to receive emissions from specific subjects.
 *
 * <p>Routes emitted values from Channels (producers) to Pipes (consumers) via Circuit's shared queue.
 * Manages percepts created from channels via a Composer. Each percept corresponds to a subject
 * and shares the Circuit's queue for signal processing (single-threaded execution model).
 *
 * <p><b>Data Flow (Circuit Queue Architecture):</b>
 * <ol>
 *   <li>Channel (producer) emits value → posts Script to Circuit Queue</li>
 *   <li>Circuit Queue processor executes Script → calls processEmission()</li>
 *   <li>processEmission() invokes subscribers (on channel creation + first emission)</li>
 *   <li>Subscribers register Pipes via Registrar → Pipes receive emissions</li>
 * </ol>
 *
 * <p><b>Single-Threaded Execution Model:</b>
 * All Conduits within a Circuit share the Circuit's single Queue. This ensures:
 * <ul>
 *   <li>Ordered delivery within Circuit domain</li>
 *   <li>QoS control (can prioritize certain Conduits)</li>
 *   <li>Prevents queue saturation</li>
 *   <li>Matches "Virtual CPU Core" design principle</li>
 * </ul>
 *
 * <p><b>Subscriber Invocation (Two-Phase Notification):</b>
 * <ul>
 *   <li><b>Phase 1 (Channel creation):</b> subscriber.accept() called when new Channel created via get()</li>
 *   <li><b>Phase 2 (First emission):</b> subscriber.accept() called on first emission from a Subject (lazy registration)</li>
 *   <li>Registered pipes are cached per Subject per Subscriber</li>
 *   <li>Subsequent emissions reuse cached pipes (efficient multi-dispatch)</li>
 *   <li>Example: Hierarchical routing where pipes register parent pipes once</li>
 *   <li>Dual-key caching: Percepts cached under both simple and hierarchical names to prevent recursion</li>
 * </ul>
 *
 * @param <P> the percept type (e.g., Pipe<E>)
 * @param <E> the emission type (e.g., MonitorSignal)
 */
@Getter
public class ConduitImpl<P, E> implements Conduit<P, E> {

    private final Subject conduitSubject;
    private final Composer<? extends P, E> perceptComposer;
    private final Map<Name, P> percepts;
    private final Scheduler scheduler; // Circuit scheduler for serialized execution
    private final Consumer<Flow<E>> flowConfigurer; // Optional transformation pipeline (nullable)

    // Direct subscriber management (moved from SourceImpl)
    private final List<Subscriber<E>> subscribers = new CopyOnWriteArrayList<>();

    // Cache: Subject Name -> Subscriber -> List of registered Pipes
    // Pipes are registered only once per Subject per Subscriber (on first emission)
    private final Map<Name, Map<Subscriber<E>, List<Pipe<E>>>> pipeCache = new ConcurrentHashMap<>();

    /**
     * Creates a Conduit without transformations.
     */
    public ConduitImpl(Name conduitName, Composer<? extends P, E> perceptComposer, Scheduler scheduler) {
        this(conduitName, perceptComposer, scheduler, null);
    }

    /**
     * Creates a Conduit with optional transformation pipeline.
     *
     * @param conduitName hierarchical conduit name (e.g., "circuit.conduit")
     * @param perceptComposer composer for creating percepts from channels
     * @param scheduler circuit's scheduler for work execution
     * @param flowConfigurer optional transformation pipeline (null if no transformations)
     */
    public ConduitImpl(Name conduitName, Composer<? extends P, E> perceptComposer, Scheduler scheduler, Consumer<Flow<E>> flowConfigurer) {
        this.conduitSubject = new SubjectImpl<>(
            IdImpl.generate(),
            conduitName,
            StateImpl.empty(),
            Conduit.class
        );
        this.perceptComposer = perceptComposer;
        this.percepts = new ConcurrentHashMap<>();
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler cannot be null");
        this.flowConfigurer = flowConfigurer; // Can be null
    }

    @Override
    public Subject subject() {
        return conduitSubject;
    }

    /**
     * Subscribes a subscriber to receive emissions from this Conduit.
     *
     * <p><b>Type System Insight:</b>
     * Conduit&lt;P, E&gt; IS-A Subject&lt;Conduit&lt;P, E&gt;&gt; (via Component → Context → Source → Conduit hierarchy).
     * This means:
     * <ul>
     *   <li>Conduit emits {@code E} values via its internal Channels</li>
     *   <li>Conduit can be subscribed to by {@code Subscriber<E>} instances</li>
     *   <li>Subscriber receives {@code Subject<Channel<E>>} (the subject of each Channel created within this Conduit)</li>
     * </ul>
     *
     * <p><b>Subscriber Behavior:</b>
     * The subscriber's {@code accept(Subject<Channel<E>>, Registrar<E>)} method is invoked:
     * <ol>
     *   <li><b>On Channel creation</b>: When a new Channel is created via {@link #get(Name)}</li>
     *   <li><b>On first emission</b>: Lazy registration when a Subject emits for the first time</li>
     * </ol>
     *
     * <p>The subscriber can:
     * <ul>
     *   <li>Inspect the {@code Subject<Channel<E>>} to determine routing logic</li>
     *   <li>Call {@code conduit.get(subject.name())} to retrieve the percept (cached, no recursion)</li>
     *   <li>Register one or more {@code Pipe<E>} instances via the {@code Registrar<E>}</li>
     *   <li>Registered pipes receive all future emissions from that Subject</li>
     * </ul>
     *
     * @param subscriber the subscriber to register
     * @return a Subscription to control the subscription lifecycle
     */
    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        Objects.requireNonNull(subscriber, "Subscriber cannot be null");
        subscribers.add(subscriber);
        return new SubscriptionImpl(() -> subscribers.remove(subscriber));
    }

    /**
     * Checks if there are any active subscribers.
     * Used by Pipes for early exit optimization.
     *
     * @return true if at least one subscriber exists, false otherwise
     */
    public boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    @Override
    public P get(Name subject) {
        // Build hierarchical name first (for lookup and creation)
        Name hierarchicalChannelName = conduitSubject.name().name(subject);

        // Fast path: check both simple and hierarchical names
        P existingPercept = percepts.get(subject);
        if (existingPercept != null) {
            return existingPercept;
        }
        existingPercept = percepts.get(hierarchicalChannelName);
        if (existingPercept != null) {
            return existingPercept;
        }

        // Slow path: create new Channel and percept
        Channel<E> channel = new ChannelImpl<>(hierarchicalChannelName, scheduler, this::notifySubscribers, this::hasSubscribers, flowConfigurer);
        P newPercept = perceptComposer.compose(channel);

        // Cache under BOTH the simple name and hierarchical name
        // This allows get("sensor1") and get("circuit.sensors.sensor1") to both work
        P cachedPercept = percepts.putIfAbsent(subject, newPercept);

        if (cachedPercept == null) {
            // We created it - also cache under hierarchical name
            percepts.putIfAbsent(hierarchicalChannelName, newPercept);

            // Notify subscribers AFTER caching
            // This allows subscriber.accept() to safely call conduit.get(subject.name())
            notifySubscribersOfNewSubject(channel.subject());
            return newPercept;
        } else {
            // Someone else created it first - also cache under hierarchical name
            percepts.putIfAbsent(hierarchicalChannelName, cachedPercept);
            return cachedPercept;
        }
    }

    @Override
    public Conduit<P, E> tap(Consumer<? super Conduit<P, E>> consumer) {
        Objects.requireNonNull(consumer, "Consumer cannot be null");
        consumer.accept(this);
        return this;
    }

    /**
     * Provides an emission handler callback for Channel/Pipe creation.
     * Channels pass this callback to Pipes, allowing Pipes to notify subscribers.
     *
     * @return callback that routes emissions to subscribers
     */
    public Consumer<Capture<E, Channel<E>>> emissionHandler() {
        return this::notifySubscribers;
    }

    /**
     * Notifies all subscribers that a new Subject (Channel) has become available.
     * Called AFTER the Channel is cached in percepts map.
     *
     * <p>Per the Substrates API contract: "the subscriber's behavior is invoked each time
     * a new channel or emitting subject is created within that source."
     *
     * <p>The subscriber can safely call {@code conduit.get(subject.name())} to retrieve
     * the percept, as it's already cached.
     *
     * @param subject the Subject of the newly created Channel
     */
    private void notifySubscribersOfNewSubject(Subject<Channel<E>> subject) {
        for (Subscriber<E> subscriber : subscribers) {
            // Call subscriber.accept() to let it register pipes
            subscriber.accept(subject, new Registrar<E>() {
                @Override
                public void register(Pipe<E> pipe) {
                    // Cache the registered pipe for this (subscriber, subject) pair
                    Name subjectName = subject.name();
                    pipeCache
                        .computeIfAbsent(subjectName, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(subscriber, k -> new CopyOnWriteArrayList<>())
                        .add(pipe);
                }
            });
        }
    }

    /**
     * Notifies all subscribers of an emission from a Channel.
     * Handles lazy pipe registration and multi-dispatch.
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
