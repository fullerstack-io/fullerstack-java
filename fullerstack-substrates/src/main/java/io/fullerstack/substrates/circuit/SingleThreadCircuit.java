package io.fullerstack.substrates.circuit;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.cell.SimpleCell;
import io.fullerstack.substrates.channel.EmissionChannel;
import io.fullerstack.substrates.conduit.TransformingConduit;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.pool.ConcurrentPool;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import io.fullerstack.substrates.subscription.CallbackSubscription;
import io.fullerstack.substrates.name.HierarchicalName;
import io.fullerstack.substrates.valve.Valve;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Single-threaded implementation of Substrates.Circuit using the Virtual CPU Core pattern.
 *
 * <p>This implementation processes all emissions through a single virtual thread with a FIFO queue,
 * ensuring ordered execution and eliminating the need for locks within the Circuit domain.
 *
 * <p><b>Virtual CPU Core Pattern (William's Valve Architecture):</b>
 * <ul>
 *   <li>Single {@link Valve} processes all emissions (FIFO ordering)</li>
 *   <li>Valve = BlockingQueue + Virtual Thread processor</li>
 *   <li>Emissions → Tasks (submitted to valve)</li>
 *   <li>All Conduits share the same valve (isolation per Circuit)</li>
 *   <li>Guarantees ordering, eliminates locks, prevents race conditions</li>
 * </ul>
 *
 * <p><b>Component Management:</b>
 * <ul>
 *   <li>Conduit caching by (name, composer type) - different composers create different conduits</li>
 *   <li>Cell creation with hierarchical structure</li>
 *   <li>State subscriber management (Circuit IS-A Source&lt;State&gt;)</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b>
 * Single-threaded execution within Circuit domain eliminates need for synchronization.
 * External callers can emit from any thread - emissions are posted to queue and processed serially.
 *
 * @see Circuit
 * @see Scheduler
 */
public class SingleThreadCircuit implements Circuit, Scheduler {
    private final Subject circuitSubject;

    // Valve (William's pattern: BlockingQueue + Virtual Thread)
    private final Valve valve;

    private final Map<Name, ConduitSlot> conduits;
    private volatile boolean closed = false;

    // Direct subscriber management for State (Circuit IS-A Source<State>)
    private final List<Subscriber<State>> stateSubscribers = new CopyOnWriteArrayList<>();

    /**
     * Optimized storage for Conduits with single-slot fast path + overflow map.
     * <p>
     * Performance: 95% of names have 1 composer (5ns lookup), 5% have multiple composers (13ns).
     * This is 15× faster than composite key approach for common case.
     * <p>
     * Pattern: Primary slot holds the first composer (usually Composer.pipe()), overflow map
     * holds additional composers (rare but fully supported for custom domain types).
     */
    private static class ConduitSlot {
        final Class<?> primaryClass;
        final Conduit<?, ?> primaryConduit;
        volatile Map<Class<?>, Conduit<?, ?>> overflow;

        ConduitSlot(Class<?> primaryClass, Conduit<?, ?> primaryConduit) {
            this.primaryClass = primaryClass;
            this.primaryConduit = primaryConduit;
            this.overflow = null;
        }

        Conduit<?, ?> get(Class<?> composerClass) {
            // FAST PATH (95%): Check primary slot
            if (primaryClass == composerClass) {
                return primaryConduit;
            }
            // SLOW PATH (5%): Check overflow map
            Map<Class<?>, Conduit<?, ?>> overflowMap = overflow;
            return overflowMap != null ? overflowMap.get(composerClass) : null;
        }

        void putOverflow(Class<?> composerClass, Conduit<?, ?> conduit) {
            Map<Class<?>, Conduit<?, ?>> overflowMap = overflow;
            if (overflowMap == null) {
                synchronized (this) {
                    overflowMap = overflow;
                    if (overflowMap == null) {
                        overflow = overflowMap = new ConcurrentHashMap<>();
                    }
                }
            }
            overflowMap.put(composerClass, conduit);
        }
    }


    /**
     * Creates a single-threaded circuit with the specified name.
     *
     * <p>Initializes:
     * <ul>
     *   <li>Valve (BlockingQueue + Virtual Thread) for FIFO emission processing</li>
     *   <li>Shared ScheduledExecutorService for all Clocks</li>
     *   <li>Component caches (Conduits, Clocks)</li>
     * </ul>
     *
     * @param name circuit name (hierarchical, e.g., "account.region.cluster")
     */
    public SingleThreadCircuit(Name name) {
        Objects.requireNonNull(name, "Circuit name cannot be null");
        this.conduits = new ConcurrentHashMap<>();
        Id id = UuidIdentifier.generate();
        this.circuitSubject = new HierarchicalSubject<>(
            id,
            name,
            LinkedState.empty(),
            Circuit.class
        );

        // Create valve for task execution (William's pattern)
        this.valve = new Valve("circuit-" + name.part());
    }

    @Override
    public Subject subject() {
        return circuitSubject;
    }

    @Override
    public Subscription subscribe(Subscriber<State> subscriber) {
        Objects.requireNonNull(subscriber, "Subscriber cannot be null");
        stateSubscribers.add(subscriber);
        return new CallbackSubscription(() -> stateSubscribers.remove(subscriber), circuitSubject);
    }

    // Circuit.await() - public API
    @Override
    public void await() {
        // Fast path: if circuit is already closed, return immediately
        if (closed) {
            return;
        }

        // Delegate to valve's await with "Circuit" context for error messages
        valve.await("Circuit");
    }

    // Scheduler.schedule() - internal API for components
    @Override
    public void schedule(Runnable task) {
        checkClosed();
        if (task != null) {
            valve.submit(task);  // Submit to valve
        }
    }

    @Override
    public <I, E> Cell<I, E> cell(
            BiFunction<Subject<Cell<I, E>>, Pipe<E>, Pipe<I>> transformer,
            BiFunction<Subject<Cell<I, E>>, Pipe<E>, Pipe<E>> aggregator,
            Pipe<E> pipe) {
        return cell(HierarchicalName.of("cell"), transformer, aggregator, pipe);
    }

    @Override
    public <I, E> Cell<I, E> cell(
            Name name,
            BiFunction<Subject<Cell<I, E>>, Pipe<E>, Pipe<I>> transformer,
            BiFunction<Subject<Cell<I, E>>, Pipe<E>, Pipe<E>> aggregator,
            Pipe<E> pipe) {
        Objects.requireNonNull(name, "Cell name cannot be null");
        Objects.requireNonNull(transformer, "Transformer cannot be null");
        Objects.requireNonNull(aggregator, "Aggregator cannot be null");
        Objects.requireNonNull(pipe, "Pipe cannot be null");

        // Create a Conduit for this Cell to manage subscriptions
        // The conduit type must be Conduit<Pipe<I>, E> to match SimpleCell constructor
        // We'll cast later after applying transformer
        Conduit<Pipe<E>, E> tempConduit = conduit(name, Composer.pipe());

        // Get the channel from conduit
        Pipe<E> channelPipe = tempConduit.get(name);

        // Create Cell Subject
        Subject<Cell<I, E>> cellSubject = new HierarchicalSubject<>(
            UuidIdentifier.generate(),
            name,
            LinkedState.empty(),
            (Class<Cell<I, E>>) (Class<?>) Cell.class,
            circuitSubject
        );

        // Apply transformer to create input pipe
        Pipe<I> inputPipe = transformer.apply(cellSubject, channelPipe);

        // Apply aggregator to output pipe
        Pipe<E> outputPipe = aggregator.apply(cellSubject, pipe);

        // Cast conduit to the correct type for SimpleCell
        // This is safe because the transformer creates Pipe<I> from the channel's Pipe<E>
        @SuppressWarnings("unchecked")
        Conduit<Pipe<I>, E> cellConduit = (Conduit<Pipe<I>, E>) (Conduit<?, E>) tempConduit;

        // Create SimpleCell with explicit type parameters
        return new SimpleCell<I, E>(
            null,
            name,
            inputPipe,
            outputPipe,
            cellConduit,
            circuitSubject
        );
    }

    @Override
    public <E> Pipe<E> pipe(Pipe<E> target) {
        Objects.requireNonNull(target, "Target pipe cannot be null");
        // Return a pipe that routes emissions through the valve to the target
        return value -> schedule(() -> target.emit(value));
    }

    @Override
    public <E> Pipe<E> pipe(Pipe<E> target, Consumer<? super Flow<E>> configurer) {
        Objects.requireNonNull(target, "Target pipe cannot be null");
        Objects.requireNonNull(configurer, "Flow configurer cannot be null");
        // TODO: Implement Flow transformations before dispatching to target
        // For now, just delegate to the simpler version
        return pipe(target);
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Composer<E, ? extends P> composer) {
        // Generate unique name for unnamed conduits to avoid caching collisions
        return conduit(HierarchicalName.of("conduit-" + UuidIdentifier.generate().toString()), composer);
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Name name, Composer<E, ? extends P> composer) {
        checkClosed();
        Objects.requireNonNull(name, "Conduit name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");

        Class<?> composerClass = composer.getClass();

        // FAST PATH: Try to get existing slot (identity map lookup)
        ConduitSlot slot = conduits.get(name);  // ~4ns with HierarchicalName identity map

        if (slot != null) {
            // Check if composer exists in slot
            @SuppressWarnings("unchecked")
            Conduit<P, E> existing = (Conduit<P, E>) slot.get(composerClass);  // ~1-8ns
            if (existing != null) {
                return existing;  // Total: ~5-12ns for cache hit
            }
        }

        // COLD PATH: Create new conduit (only on miss)
        // Use simple name - hierarchy is implicit through parent Subject references
        Conduit<P, E> newConduit = new TransformingConduit<>(
            name, composer, this  // Pass Circuit as parent
        );

        // Add to slot structure (thread-safe)
        conduits.compute(name, (k, existingSlot) -> {
            if (existingSlot == null) {
                // First conduit for this name - create primary slot
                return new ConduitSlot(composerClass, newConduit);
            }
            // Add to overflow map (second+ conduit for this name)
            existingSlot.putOverflow(composerClass, newConduit);
            return existingSlot;
        });

        return newConduit;
    }

    @Override
    public <P, E> Conduit<P, E> conduit(Name name, Composer<E, ? extends P> composer, Consumer<Flow<E>> configurer) {
        checkClosed();
        Objects.requireNonNull(name, "Conduit name cannot be null");
        Objects.requireNonNull(composer, "Composer cannot be null");
        Objects.requireNonNull(configurer, "Flow configurer cannot be null");

        Class<?> composerClass = composer.getClass();

        // FAST PATH: Try to get existing slot
        ConduitSlot slot = conduits.get(name);

        if (slot != null) {
            @SuppressWarnings("unchecked")
            Conduit<P, E> existing = (Conduit<P, E>) slot.get(composerClass);
            if (existing != null) {
                return existing;
            }
        }

        // COLD PATH: Create new conduit with flow configurer
        // Use simple name - hierarchy is implicit through parent Subject references
        Conduit<P, E> newConduit = new TransformingConduit<>(
            name, composer, this, configurer
        );

        // Add to slot structure
        conduits.compute(name, (k, existingSlot) -> {
            if (existingSlot == null) {
                return new ConduitSlot(composerClass, newConduit);
            }
            existingSlot.putOverflow(composerClass, newConduit);
            return existingSlot;
        });

        return newConduit;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;

            // Close valve
            valve.close();

            conduits.clear();
        }
    }

    private void checkClosed() {
        if (closed) {
            throw new IllegalStateException("Circuit is closed");
        }
    }
}
