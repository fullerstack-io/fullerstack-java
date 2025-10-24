package io.fullerstack.substrates.cell;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.EmissionChannel;
import io.fullerstack.substrates.conduit.TransformingConduit;
import io.fullerstack.substrates.circuit.Scheduler;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.name.HierarchicalName;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import io.fullerstack.substrates.subscription.CallbackSubscription;
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
 * HierarchicalCell implementation - following SimpleName pattern exactly.
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
public final class HierarchicalCell<I, E> implements Cell<I, E> {

    private final HierarchicalCell<I, E> parent;              // Parent Cell (null for root)
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
    public HierarchicalCell(HierarchicalCell<I, E> parent, Name name, Function<I, E> transformer, Scheduler scheduler) {
        this.parent = parent;
        this.segment = name.part().toString();
        this.transformer = Objects.requireNonNull(transformer, "transformer cannot be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");

        this.subject = new HierarchicalSubject<>(
            UuidIdentifier.generate(),
            name,
            LinkedState.empty(),
            Cell.class,
            parent != null ? parent.subject() : null  // Parent Cell's Subject for hierarchy
        );

        // TODO: HierarchicalCell architecture needs redesign to work with parent reference pattern
        // For now, HierarchicalCell is not being used in the actual system
        // This is a temporary workaround - create a minimal stub Conduit

        // Create a stub Conduit that provides minimal functionality for Channel creation
        CellConduitStub<E> stubConduit = new CellConduitStub<>(name, this.subject, scheduler, this::notifySubscribers, this::hasSubscribers);
        Channel<E> channel = new EmissionChannel<>(name, stubConduit, null);
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
            return new HierarchicalCell<>(this, childName, transformer, scheduler);
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
        return new CallbackSubscription(() -> subscribers.remove(subscriber), subject);
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

    /**
     * Minimal stub Conduit implementation for HierarchicalCell's internal use.
     * Provides just enough functionality for Channel to create Pipes.
     */
    private static class CellConduitStub<E> extends TransformingConduit<Pipe<E>, E> {
        private final CircuitStub circuitStub;
        private final Consumer<Capture<E, Channel<E>>> emissionHandler;
        private final java.util.function.BooleanSupplier hasSubscribersCheck;

        CellConduitStub(Name name, Subject<?> subject, Scheduler scheduler,
                       Consumer<Capture<E, Channel<E>>> emissionHandler,
                       java.util.function.BooleanSupplier hasSubscribersCheck) {
            super(name, Channel::pipe, null, null);  // null Circuit parent, null flow configurer
            this.circuitStub = new CircuitStub(scheduler, subject);
            this.emissionHandler = emissionHandler;
            this.hasSubscribersCheck = hasSubscribersCheck;
        }

        @Override
        public Circuit getCircuit() {
            return circuitStub;
        }

        @Override
        public Consumer<Capture<E, Channel<E>>> emissionHandler() {
            return emissionHandler;
        }

        @Override
        public boolean hasSubscribers() {
            return hasSubscribersCheck.getAsBoolean();
        }
    }

    /**
     * Minimal Circuit stub that provides only scheduling capability.
     * Implements both Circuit and Scheduler interfaces (like SequentialCircuit).
     */
    private static class CircuitStub implements Circuit, Scheduler {
        private final Scheduler scheduler;
        private final Subject<?> subject;

        CircuitStub(Scheduler scheduler, Subject<?> subject) {
            this.scheduler = scheduler;
            this.subject = subject;
        }

        // Scheduler interface methods
        @Override public void schedule(Runnable task) { scheduler.schedule(task); }
        @Override public void await() { scheduler.await(); }

        // Circuit interface methods
        @Override @SuppressWarnings("unchecked") public Subject<Circuit> subject() { return (Subject<Circuit>) subject; }
        @Override public Subscription subscribe(Subscriber<State> subscriber) { throw new UnsupportedOperationException(); }
        @Override public <I2, E2> Cell<I2, E2> cell(Composer<Pipe<I2>, E2> composer, Pipe<E2> pipe) { throw new UnsupportedOperationException(); }
        @Override public <I2, E2> Cell<I2, E2> cell(Composer<Pipe<I2>, E2> composer, Consumer<Flow<E2>> configurer, Pipe<E2> pipe) { throw new UnsupportedOperationException(); }
        @Override public Clock clock() { throw new UnsupportedOperationException(); }
        @Override public Clock clock(Name n) { throw new UnsupportedOperationException(); }
        @Override public <P, E2> Conduit<P, E2> conduit(Composer<? extends P, E2> composer) { throw new UnsupportedOperationException(); }
        @Override public <P, E2> Conduit<P, E2> conduit(Name n, Composer<? extends P, E2> composer) { throw new UnsupportedOperationException(); }
        @Override public <P, E2> Conduit<P, E2> conduit(Name n, Composer<? extends P, E2> composer, Consumer<Flow<E2>> configurer) { throw new UnsupportedOperationException(); }
        @Override public Circuit tap(Consumer<? super Circuit> consumer) { return this; }
        @Override public void close() {}
    }
}
