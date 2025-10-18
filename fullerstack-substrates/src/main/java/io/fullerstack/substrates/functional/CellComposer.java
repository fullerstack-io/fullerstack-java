package io.fullerstack.substrates.functional;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.cell.CellImpl;
import io.fullerstack.substrates.registry.RegistryFactory;
import io.fullerstack.substrates.registry.LazyTrieRegistryFactory;
import io.fullerstack.substrates.circuit.Scheduler;

import java.util.function.Consumer;

/**
 * Factory for creating Cell-returning Composers.
 *
 * <p>This helper resolves the type mismatch between Composer<Pipe<I>, E> and Cell<I, E>
 * by providing Composers that explicitly return Cell instances instead of generic Pipes.
 *
 * <h3>The Problem:</h3>
 * <pre>{@code
 * // Circuit.cell() expects: Composer<Pipe<I>, E>
 * // But Cell.get() needs to return: Cell<I, E> (which IS-A Pipe<I>)
 *
 * // Current implementation requires unsafe cast:
 * Pipe<I> pipe = composer.compose(channel);
 * Cell<I, E> cell = (Cell<I, E>) pipe;  // ← Unsafe cast
 * }</pre>
 *
 * <h3>The Solution:</h3>
 * <pre>{@code
 * // Create a Composer that returns Cells directly
 * Composer<Pipe<KafkaMetric>, Alert> cellComposer = CellComposer.create(
 *     circuit,                    // Provides Scheduler
 *     LazyTrieRegistryFactory.getInstance(),
 *     flow -> flow               // Transformation: KafkaMetric → Alert
 *         .filter(m -> m.value() > threshold)
 *         .map(m -> new Alert(m))
 * );
 *
 * // Use with Circuit.cell()
 * Cell<KafkaMetric, Alert> cell = circuit.cell(cellComposer);
 *
 * // Now Cell.get() can safely cast because Composer returns Cells
 * Cell<KafkaMetric, Alert> child = cell.get(name("cpu"));
 * }</pre>
 *
 * <h3>How It Works:</h3>
 * <p>The Composer lambda creates new CellImpl instances when Channel calls compose().
 * Since Cell<I, E> extends Pipe<I>, the return type satisfies the Composer<Pipe<I>, E> interface
 * while actually returning Cell instances.
 *
 * @see CellImpl
 * @see Circuit#cell(Composer)
 */
public class CellComposer {

    /**
     * Creates a Composer that returns Cell instances.
     *
     * <p>This factory method creates a Composer<Pipe<E>, E> that internally creates
     * CellImpl instances, allowing safe casting in Cell.get().
     *
     * <p><b>Note:</b> Cells don't apply their own transformations - transformations
     * are applied at the Circuit.cell() level via the second parameter. Child cells
     * created by this Composer simply relay emissions.
     *
     * @param scheduler the Circuit's scheduler (for async emission processing)
     * @param registryFactory factory for creating child cell registries
     * @param <E> emission type
     * @return Composer that creates Cell instances
     */
    public static <E> Composer<Pipe<E>, E> create(
        Scheduler scheduler,
        RegistryFactory registryFactory
    ) {
        return channel -> {
            // Create a CellImpl instance (which IS-A Pipe<E>)
            // Child cells use same type (E → E), no transformation
            return new CellImpl<>(
                channel.subject().name(),
                // Recursive: child Cells use the same Composer pattern
                create(scheduler, registryFactory),
                scheduler,
                registryFactory,
                null  // No flow transformation at child level
            );
        };
    }

    /**
     * Creates a simple Composer for Cells without transformations.
     *
     * <p>Equivalent to Composer.pipe() but returns Cells instead of plain Pipes.
     *
     * @param scheduler the Circuit's scheduler
     * @param registryFactory factory for creating child cell registries
     * @param <I> input type
     * @param <E> emission type
     * @return Composer that creates Cell instances without transformations
     */
    public static <I, E> Composer<Pipe<I>, E> create(
        Scheduler scheduler,
        RegistryFactory registryFactory
    ) {
        return create(scheduler, registryFactory, null);
    }

    /**
     * Creates a Composer from a Circuit instance.
     *
     * <p>Convenience method that extracts the Scheduler from the Circuit.
     *
     * @param circuit the circuit (provides Scheduler via cast to Scheduler interface)
     * @param registryFactory factory for creating child cell registries
     * @param flowConfigurer transformation pipeline
     * @param <I> input type
     * @param <E> emission type
     * @return Composer that creates Cell instances
     */
    public static <I, E> Composer<Pipe<I>, E> fromCircuit(
        Circuit circuit,
        RegistryFactory registryFactory,
        Consumer<Flow<E>> flowConfigurer
    ) {
        if (!(circuit instanceof Scheduler)) {
            throw new IllegalArgumentException("Circuit must implement Scheduler interface");
        }
        return create((Scheduler) circuit, registryFactory, flowConfigurer);
    }

    /**
     * Creates a simple Composer from a Circuit without transformations.
     *
     * @param circuit the circuit
     * @param registryFactory factory for creating child cell registries
     * @param <I> input type
     * @param <E> emission type
     * @return Composer that creates Cell instances
     */
    public static <I, E> Composer<Pipe<I>, E> fromCircuit(
        Circuit circuit,
        RegistryFactory registryFactory
    ) {
        return fromCircuit(circuit, registryFactory, null);
    }

    private CellComposer() {
        // Utility class - prevent instantiation
    }
}
