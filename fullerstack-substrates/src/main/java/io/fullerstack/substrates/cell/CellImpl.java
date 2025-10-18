package io.fullerstack.substrates.cell;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.ChannelImpl;
import io.fullerstack.substrates.id.IdImpl;
import io.fullerstack.substrates.source.SourceImpl;
import io.fullerstack.substrates.state.StateImpl;
import io.fullerstack.substrates.subject.SubjectImpl;
import io.fullerstack.substrates.registry.RegistryFactory;
import io.fullerstack.substrates.circuit.Scheduler;

import lombok.Getter;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Implementation of Cell - a combined Pipe and Container for type transformation.
 *
 * <p>Cell is unique in the Substrates API:
 * <ul>
 *   <li>Implements Pipe&lt;I&gt; - can receive input of type I via emit()</li>
 *   <li>Extends Container&lt;Cell&lt;I,E&gt;, E&gt; - manages child Cells that emit type E</li>
 *   <li>Performs type transformation: I → E</li>
 * </ul>
 *
 * <p><b>Key Difference from Conduit:</b>
 * <ul>
 *   <li>Conduit&lt;Pipe&lt;E&gt;, E&gt; - no type transformation (input = output = E)</li>
 *   <li>Cell&lt;I, E&gt; - type transformation (input I, output E)</li>
 * </ul>
 *
 * <p><b>Current Implementation Issue:</b> The get() method creates Pipes via Composer,
 * then casts them to Cells. This works at runtime but the architecture may need refinement
 * to properly support Cell creation patterns.
 *
 * @param <I> the input type (what this Cell receives)
 * @param <E> the emission type (what children emit)
 */
@Getter
public class CellImpl<I, E> implements Cell<I, E> {

    private final Subject cellSubject;
    private final Composer<Pipe<I>, E> composer;
    private final Map<Name, Cell<I, E>> childCells;
    private final Source<E> source;
    private final Scheduler scheduler;
    private final Consumer<Flow<E>> flowConfigurer;

    public CellImpl(Name cellName, Composer<Pipe<I>, E> composer, Scheduler scheduler, RegistryFactory registryFactory) {
        this(cellName, composer, scheduler, registryFactory, null);
    }

    @SuppressWarnings("unchecked")
    public CellImpl(Name cellName, Composer<Pipe<I>, E> composer, Scheduler scheduler,
                    RegistryFactory registryFactory, Consumer<Flow<E>> flowConfigurer) {
        this.cellSubject = new SubjectImpl(
            IdImpl.generate(),
            cellName,
            StateImpl.empty(),
            Subject.Type.CELL
        );
        this.composer = Objects.requireNonNull(composer, "Composer cannot be null");
        this.childCells = (Map<Name, Cell<I, E>>) registryFactory.create();
        this.source = new SourceImpl<>(cellName);
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler cannot be null");
        this.flowConfigurer = flowConfigurer;
    }

    @Override
    public Subject subject() {
        return cellSubject;
    }

    @Override
    public Source<E> source() {
        return source;
    }

    @Override
    public Cell<I, E> get(Name name) {
        return childCells.computeIfAbsent(name, n -> {
            Name hierarchicalName = cellSubject.name().name(n);
            Channel<E> channel = new ChannelImpl<>(hierarchicalName, scheduler, source, flowConfigurer);
            Pipe<I> pipe = composer.compose(channel);

            // TODO: This cast is questionable - Composer creates Pipe<I>, not Cell<I,E>
            // Need to understand intended Cell usage pattern from Substrates API
            @SuppressWarnings("unchecked")
            Cell<I, E> childCell = (Cell<I, E>) pipe;

            return childCell;
        });
    }

    @Override
    public void emit(I emission) {
        Objects.requireNonNull(emission, "Emission cannot be null");
        scheduler.schedule(() -> processEmission(emission));
    }

    private void processEmission(I emission) {
        for (Cell<I, E> childCell : childCells.values()) {
            childCell.emit(emission);
        }
    }

    // Tap interface provides default implementation

    // Extent<Cell<I,E>> implementation

    @Override
    public Optional<Cell<I, E>> enclosure() {
        // Cells can be hierarchical - parent cell would be stored here
        // For now, top-level cells have no enclosure
        return Optional.empty();
    }

    @Override
    public CharSequence part() {
        return cellSubject.name().part();
    }

    @Override
    public Iterator<Cell<I, E>> iterator() {
        // Default Extent implementation - iterates from this to root
        return new Iterator<>() {
            private Cell<I, E> current = CellImpl.this;

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public Cell<I, E> next() {
                Cell<I, E> result = current;
                current = current.enclosure().orElse(null);
                return result;
            }
        };
    }

    @Override
    public int compareTo(Cell<I, E> other) {
        Objects.requireNonNull(other, "Cannot compare to null");
        return CharSequence.compare(
            cellSubject.name().value(),
            other.subject().name().value()
        );
    }
}
