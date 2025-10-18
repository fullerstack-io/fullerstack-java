package io.fullerstack.substrates.functional;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.cell.CellImpl;
import io.fullerstack.substrates.pipe.TransformingPipe;
import io.fullerstack.substrates.registry.RegistryFactory;
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
 *     LazyTrieRegistryFactory.getInstance(),
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
     * @param registryFactory factory for creating child cell registries
     * @param transformer function to transform I → E
     * @param <I> input type (what Cell receives via emit())
     * @param <E> emission type (what gets emitted to Source)
     * @return Composer that creates type-transforming Cells
     */
    public static <I, E> Composer<Pipe<I>, E> typeTransforming(
        Scheduler scheduler,
        RegistryFactory registryFactory,
        Function<I, E> transformer
    ) {
        return channel -> {
            // Create TransformingPipe that converts I → E
            Pipe<I> transformingPipe = new TransformingPipe<>(
                channel.pipe(),  // Target Pipe<E>
                transformer      // I → E transformation
            );

            // Wrap in CellImpl (which IS-A Pipe<I>)
            return new CellImpl<>(
                channel.subject().name(),
                // Recursive: child Cells use same transformer
                typeTransforming(scheduler, registryFactory, transformer),
                scheduler,
                registryFactory,
                null  // Flow transformations happen on the E type in Channel
            );
        };
    }

    /**
     * Creates a type-transforming Composer from a Circuit instance.
     *
     * <p>Convenience method that extracts the Scheduler from the Circuit.
     *
     * @param circuit the circuit (provides Scheduler via cast to Scheduler interface)
     * @param registryFactory factory for creating child cell registries
     * @param transformer function to transform I → E
     * @param <I> input type
     * @param <E> emission type
     * @return Composer that creates type-transforming Cells
     */
    public static <I, E> Composer<Pipe<I>, E> fromCircuit(
        Circuit circuit,
        RegistryFactory registryFactory,
        Function<I, E> transformer
    ) {
        if (!(circuit instanceof Scheduler)) {
            throw new IllegalArgumentException("Circuit must implement Scheduler interface");
        }
        return typeTransforming((Scheduler) circuit, registryFactory, transformer);
    }

    /**
     * Creates a same-type Composer (no transformation).
     *
     * <p>For Cell<E, E> where no type transformation is needed.
     *
     * @param circuit the circuit
     * @param registryFactory factory for creating child cell registries
     * @param <E> emission type (same for input and output)
     * @return Composer that creates same-type Cells
     */
    public static <E> Composer<Pipe<E>, E> sameType(
        Circuit circuit,
        RegistryFactory registryFactory
    ) {
        return fromCircuit(circuit, registryFactory, Function.identity());
    }

    private CellComposer() {
        // Utility class - prevent instantiation
    }
}
