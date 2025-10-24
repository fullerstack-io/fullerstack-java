package io.fullerstack.substrates.functional;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.cell.HierarchicalCell;
import io.fullerstack.substrates.circuit.Scheduler;

import java.util.function.Function;

/**
 * Factory for creating type-transforming Cell Composers.
 *
 * <p>Enables Cell<I, E> type transformation by creating Composers that wrap
 * Channels with TransformingPipe to convert input type I to emission type E.
 *
 * <h3>The Problem:</h3>
 * <p>Cell<I, E> signature indicates type transformation (I → E), but Flow API
 * only supports same-type transformations (E → E). The I → E transformation
 * must happen in the Composer.
 *
 * <h3>The Solution:</h3>
 * <pre>{@code
 * // Create a type-transforming Composer
 * Composer<Pipe<KafkaMetric>, Alert> composer = CellComposer.typeTransforming(
 *     circuit,
 *     metric -> new Alert(metric)  // Transform: KafkaMetric → Alert
 * );
 *
 * // Use with Circuit.cell()
 * Cell<KafkaMetric, Alert> cell = circuit.cell(composer);
 * cell.emit(kafkaMetric);  // Receives KafkaMetric
 * // → Transforms to Alert
 * // → Emits Alert to Source<Alert>
 * }</pre>
 *
 * <h3>How It Works:</h3>
 * <ol>
 *   <li>Composer receives Channel<E> from Circuit</li>
 *   <li>Creates TransformingPipe<I, E> wrapping Channel.pipe()</li>
 *   <li>Wraps TransformingPipe in CellImpl</li>
 *   <li>Cell.emit(I) → TransformingPipe transforms I → E → Channel<E></li>
 * </ol>
 *
 * @see CellImpl
 * @see TransformingPipe
 * @see Circuit#cell(Composer)
 */
public class CellComposer {

    /**
     * Creates a type-transforming Composer for Cells.
     *
     * <p>This factory method creates a Composer<Pipe<I>, E> that transforms
     * input type I to emission type E using the provided transformer function.
     *
     * @param scheduler the Circuit's scheduler (for async emission processing)
     * @param transformer function to transform I → E
     * @param <I> input type (what Cell receives via emit())
     * @param <E> emission type (what gets emitted to Source)
     * @return Composer that creates type-transforming Cells
     */
    public static <I, E> Composer<Pipe<I>, E> typeTransforming(
        Scheduler scheduler,
        Function<I, E> transformer
    ) {
        // Create root Composer (no parent)
        return typeTransformingWithParent(null, scheduler, transformer);
    }

    /**
     * Creates a Composer that captures parent Cell for creating children.
     *
     * <p>This is used internally by Cell.get() to create child Cells.
     * The parent reference is captured in the closure.
     *
     * @param parent the parent Cell (null for root Cells)
     * @param scheduler the scheduler
     * @param transformer the transformation function
     * @param <I> input type
     * @param <E> emission type
     * @return Composer that creates Cells with parent reference
     */
    public static <I, E> Composer<Pipe<I>, E> typeTransformingWithParent(
        Cell<I, E> parent,
        Scheduler scheduler,
        Function<I, E> transformer
    ) {
        return channel -> {
            // Create Cell with parent reference
            // HierarchicalCell manages its own children via get()
            @SuppressWarnings("unchecked")
            HierarchicalCell<I, E> cellParent = (HierarchicalCell<I, E>) parent;

            return new HierarchicalCell<>(
                cellParent,      // Captured from closure (null for root)
                channel.subject().name(),
                transformer,
                scheduler
            );
        };
    }

    /**
     * Creates a type-transforming Composer from a Circuit instance.
     *
     * <p>Convenience method that extracts the Scheduler from the Circuit.
     *
     * @param circuit the circuit (provides Scheduler via cast to Scheduler interface)
     * @param transformer function to transform I → E
     * @param <I> input type
     * @param <E> emission type
     * @return Composer that creates type-transforming Cells
     */
    public static <I, E> Composer<Pipe<I>, E> fromCircuit(
        Circuit circuit,
        Function<I, E> transformer
    ) {
        if (!(circuit instanceof Scheduler)) {
            throw new IllegalArgumentException("Circuit must implement Scheduler interface");
        }
        return typeTransforming((Scheduler) circuit, transformer);
    }

    /**
     * Creates a same-type Composer (no transformation).
     *
     * <p>For Cell<E, E> where no type transformation is needed.
     *
     * @param circuit the circuit
     * @param <E> emission type (same for input and output)
     * @return Composer that creates same-type Cells
     */
    public static <E> Composer<Pipe<E>, E> sameType(
        Circuit circuit
    ) {
        return fromCircuit(circuit, Function.identity());
    }

    private CellComposer() {
        // Utility class - prevent instantiation
    }
}
