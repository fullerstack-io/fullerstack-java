package io.fullerstack.substrates.cell;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.id.UuidIdentifier;
import io.fullerstack.substrates.state.LinkedState;
import io.fullerstack.substrates.subject.HierarchicalSubject;
import io.fullerstack.substrates.subscription.CallbackSubscription;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Simple Cell implementation for Circuit.cell() support.
 *
 * <p>Cell<I, E> provides bidirectional signal transformation:
 * <ul>
 *   <li>Accepts input type I via emit() - delegates to wrapped Pipe</li>
 *   <li>Emits type E to subscribers via Conduit's infrastructure</li>
 *   <li>Supports hierarchical children via get(Name)</li>
 * </ul>
 *
 * <p><b>Design:</b>
 * - Wraps a Pipe<I> created by the Composer
 * - Uses Conduit for emission infrastructure
 * - Implements Pipe<I> by delegating to wrapped pipe
 * - Implements Source<E> by delegating to conduit
 * - Implements Extent for hierarchy (get, enclosure)
 *
 * @param <I> input type (what Cell receives)
 * @param <E> emission type (what Cell emits to subscribers)
 */
public class SimpleCell<I, E> implements Cell<I, E> {

    private final SimpleCell<I, E> parent;
    private final String segment;
    private final Pipe<I> pipe;                      // Pipe created by Composer
    private final Conduit<Pipe<I>, E> conduit;       // Conduit for emission infrastructure
    private final Subject subject;
    private final Map<Name, Cell<I, E>> children = new ConcurrentHashMap<>();

    /**
     * Creates a new Cell wrapping a Pipe.
     *
     * @param parent parent Cell (null for root)
     * @param name Cell name
     * @param pipe Pipe created by Composer for handling emissions
     * @param conduit Conduit providing emission infrastructure
     * @param parentSubject parent Subject for hierarchy (null for root)
     */
    public SimpleCell(
        SimpleCell<I, E> parent,
        Name name,
        Pipe<I> pipe,
        Conduit<Pipe<I>, E> conduit,
        Subject<?> parentSubject
    ) {
        this.parent = parent;
        this.segment = name.part().toString();
        this.pipe = Objects.requireNonNull(pipe, "pipe cannot be null");
        this.conduit = Objects.requireNonNull(conduit, "conduit cannot be null");
        this.subject = new HierarchicalSubject<>(
            UuidIdentifier.generate(),
            name,
            LinkedState.empty(),
            Cell.class,
            parentSubject
        );
    }

    // ========== Pipe<I> implementation (input) ==========

    @Override
    public void emit(I input) {
        // Delegate to the wrapped pipe created by the Composer
        pipe.emit(input);
    }

    // ========== Source<E> implementation (output) ==========

    @Override
    public Subscription subscribe(Subscriber<E> subscriber) {
        // Delegate to the conduit's subscription mechanism
        return conduit.subscribe(subscriber);
    }

    // ========== Extent implementation (hierarchy) ==========

    @Override
    public CharSequence part() {
        return segment;
    }

    @Override
    public Optional<Cell<I, E>> enclosure() {
        return Optional.ofNullable(parent);
    }

    @Override
    public Cell<I, E> get(Name name) {
        return children.computeIfAbsent(name, n -> {
            // Get the child pipe from the conduit
            // This creates a new Channel and invokes the composer
            Pipe<I> childPipe = conduit.get(n);

            // Wrap it in a Cell with this Cell as parent
            return new SimpleCell<>(this, n, childPipe, conduit, this.subject);
        });
    }

    // ========== Container implementation ==========

    @Override
    public Subject subject() {
        return subject;
    }

    // ========== Object overrides ==========

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
