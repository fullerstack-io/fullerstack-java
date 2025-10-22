package io.fullerstack.substrates.cell;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.ChannelImpl;
import io.fullerstack.substrates.circuit.Scheduler;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.name.NameNode;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.subscription.SubscriptionImpl;
import io.fullerstack.substrates.capture.CaptureImpl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * CellNode implementation - following SimpleName pattern exactly.
 *
 * <p><b>Design Principles:</b>
 * <ul>
 *   <li>Parent-child linked structure (like SimpleName)</li>
 *   <li>Only absolutely necessary fields</li>
 *   <li>Extent defaults for hierarchy (depth, iterator, path)</li>
 * </ul>
 *
 * <p><b>What we NEED (8 fields):</b>
 * <ul>
 *   <li>parent - for hierarchy (like SimpleName)</li>
 *   <li>segment - for part() (like SimpleName)</li>
 *   <li>transformer - for emit() to transform I → E</li>
 *   <li>source - for managing subscribers</li>
 *   <li>pipe - for emitting to Source (connects via Channel)</li>
 *   <li>scheduler - for async emission (needed by Channel)</li>
 *   <li>subject - for identity</li>
 *   <li>children - cache children (just a ConcurrentHashMap, no RegistryFactory needed)</li>
 * </ul>
 */
public final class CellNode<I, E> implements Cell<I, E> {

    private final CellNode<I, E> parent;              // Parent Cell (null for root)
    private final String segment;                         // This Cell's name segment
    private final Function<I, E> transformer;             // I → E transformation
    private final Pipe<E> pipe;                           // For emitting (connects to Source via Channel)
    private final Scheduler scheduler;                    // For async operations
    private final Subject subject;                        // For identity
    private final Map<Name, Cell<I, E>> children;         // Cache of children - simple ConcurrentHashMap

    // Direct subscriber management (Cell IS-A Source via sealed hierarchy)
    private final List<Subscriber<E>> subscribers = new CopyOnWriteArrayList<>();
    private final Map<Name, Map<Subscriber<E>, List<Pipe<E>>>> pipeCache = new ConcurrentHashMap<>();

    /**
     * Constructor - minimal fields only.
     *
     * @param parent the parent Cell (null for root)
     * @param name the full hierarchical Name for this Cell
     * @param transformer function to transform I → E
     * @param scheduler scheduler for async operations
     */
    public CellNode(CellNode<I, E> parent, Name name, Function<I, E> transformer, Scheduler scheduler) {
        this.parent = parent;
        this.segment = name.part().toString();
        this.transformer = Objects.requireNonNull(transformer, "transformer cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");

        this.subject = new SubjectImpl<>(
            IdImpl.generate(),
            name,
            StateImpl.empty(),
            Cell.class
        );

        // Create Channel with emission handler callback (Cell IS-A Source)
        Channel<E> channel = new ChannelImpl<>(name, scheduler, this::notifySubscribers, this::hasSubscribers, null);
        this.pipe = channel.pipe();

        // Initialize empty children map (each Cell manages its own children)
        this.children = new ConcurrentHashMap<>();
    }

    // ============ REQUIRED: Extent implementations ============

    @Override
    public CharSequence part() {
        return segment;
    }

    @Override
    public Optional<Cell<I, E>> enclosure() {
        return Optional.ofNullable(parent);
    }

    // iterator(), depth(), path(), compareTo() - all use Extent defaults

    // ============ REQUIRED: Cell.get() - creates children (cached) ============

    @Override
    public Cell<I, E> get(Name name) {
        // computeIfAbsent: create only if doesn't exist
        // Each Cell has its own local children map
        // kafka Cell: {"broker" → Cell, "consumer" → Cell}
        // broker Cell: {"metrics" → Cell}
        // consumer Cell: {"metrics" → Cell}  ← different "metrics" Cell!
        return children.computeIfAbsent(name, n -> {
            // Build hierarchical Name: parent.name().name(childSegment)
            // Like SimpleName: parent.name("child") creates new SimpleName(parent, "child")
            Name childName = subject.name().name(n);
            return new CellNode<>(this, childName, transformer, scheduler);
        });
    }

    // ============ REQUIRED: Pipe<I> implementation ============

    @Override
    public void emit(I input) {
        Objects.requireNonNull(input, "input cannot be null");

        // Transform I → E
        E output = transformer.apply(input);

        // Emit to pipe, which notifies Source's subscribers
        pipe.emit(output);
    }

    // ============ REQUIRED: Source<E> implementation - Cell IS-A Source ============

    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        Objects.requireNonNull(subscriber, "Subscriber cannot be null");
        subscribers.add(subscriber);
        return new SubscriptionImpl(() -> subscribers.remove(subscriber));
    }

    public boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    private void notifySubscribers(Capture<E, Channel<E>> capture) {
        Subject<Channel<E>> emittingSubject = capture.subject();
        Name subjectName = emittingSubject.name();

        Map<Subscriber<E>, List<Pipe<E>>> subscriberPipes = pipeCache.computeIfAbsent(
            subjectName,
            name -> new ConcurrentHashMap<>()
        );

        subscribers.stream()
            .flatMap(subscriber ->
                resolvePipes(subscriber, emittingSubject, subscriberPipes).stream()
            )
            .forEach(pipe -> pipe.emit(capture.emission()));
    }

    private List<Pipe<E>> resolvePipes(
        Subscriber<E> subscriber,
        Subject emittingSubject,
        Map<Subscriber<E>, List<Pipe<E>>> subscriberPipes
    ) {
        return subscriberPipes.computeIfAbsent(subscriber, sub -> {
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

    // ============ REQUIRED: Subject (from Container) ============

    @Override
    public Subject subject() {
        return subject;
    }

    // ============ Object overrides ============

    @Override
    public String toString() {
        return path().toString();
    }

    @Override
    public int hashCode() {
        return path().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Cell)) return false;
        Cell<?, ?> other = (Cell<?, ?>) o;
        return path().equals(other.path());
    }
}
