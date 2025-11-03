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
 * Simple Cell implementation for Circuit.cell() support (M18 API).
 *
 * <p>Cell<I, E> provides bidirectional signal transformation:
 * <ul>
 *   <li>Accepts input type I via emit() - delegates to input Pipe<I></li>
 *   <li>Emits type E to output Pipe<E> (M18 API)</li>
 *   <li>Supports hierarchical children via get(Name)</li>
 * </ul>
 *
 * <p><b>M18 API Design:</b>
 * - Input: Pipe<I> created by Composer (receives I values)
 * - Output: Pipe<E> provided by caller (emits E values)
 * - The Conduit provides the Source<E> subscription infrastructure
 * - Implements Pipe<I> by delegating to input pipe
 * - Implements Source<E> by delegating to conduit
 * - Implements Extent for hierarchy (get, enclosure)
 *
 * @param <I> input type (what Cell receives)
 * @param <E> emission type (what Cell emits to output pipe)
 */
public class SimpleCell<I, E> implements Cell<I, E> {

  private final SimpleCell<I, E> parent;
  private final String segment;
  private final Pipe<I> inputPipe;                 // Input: created by Composer
  private final Pipe<E> outputPipe;                // Output: provided by M18 API
  private final Conduit<Pipe<I>, E> conduit;       // Provides subscription infrastructure
  private final Subject subject;
  private final Map<Name, Cell<I, E>> children = new ConcurrentHashMap<>();

  /**
   * Creates a new Cell with input and output pipes (M18 API).
   *
   * @param parent parent Cell (null for root)
   * @param name Cell name
   * @param inputPipe Pipe created by Composer (receives I values)
   * @param outputPipe Pipe for emitting E values (M18 API parameter)
   * @param conduit Conduit providing subscription infrastructure
   * @param parentSubject parent Subject for hierarchy (null for root)
   */
  public SimpleCell(
    SimpleCell<I, E> parent,
    Name name,
    Pipe<I> inputPipe,
    Pipe<E> outputPipe,
    Conduit<Pipe<I>, E> conduit,
    Subject<?> parentSubject
  ) {
    this.parent = parent;
    this.segment = name.part().toString();
    this.inputPipe = Objects.requireNonNull(inputPipe, "inputPipe cannot be null");
    this.outputPipe = Objects.requireNonNull(outputPipe, "outputPipe cannot be null");
    this.conduit = Objects.requireNonNull(conduit, "conduit cannot be null");
    this.subject = new HierarchicalSubject<>(
      UuidIdentifier.generate(),
      name,
      LinkedState.empty(),
      Cell.class,
      parentSubject
    );
  }

  // ========== Pipe<I> accessor (RC3) ==========

  @Override
  public Pipe<I> pipe() {
    // Return the input pipe (Cell no longer implements Pipe directly in RC3)
    // RC3 change: Cell.pipe() method instead of implementing Pipe interface
    return inputPipe;
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
      // Get the child input pipe from the conduit
      // This creates a new Channel and invokes the composer
      Pipe<I> childInputPipe = conduit.get(n);

      // Child cells share the same output pipe as the parent
      // (all cells in the hierarchy emit to the same output)
      return new SimpleCell<>(this, n, childInputPipe, outputPipe, conduit, this.subject);
    });
  }

  @Override
  public Cell<I, E> get(Subject<?> subject) {
    return get(subject.name());
  }

  @Override
  public Cell<I, E> get(Substrate<?> substrate) {
    return get(substrate.subject().name());
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
