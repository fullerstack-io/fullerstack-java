package io.fullerstack.substrates.pipe;

import io.humainary.substrates.api.Substrates.*;

import java.util.Objects;
import java.util.function.Function;

/**
 * A Pipe that transforms input type I to output type E before emitting to a Channel.
 *
 * <p>This enables Cell<I, E> type transformation where:
 * <ul>
 *   <li>Cell receives type I via emit(I)</li>
 *   <li>TransformingPipe transforms I → E</li>
 *   <li>Channel<E> receives transformed type E</li>
 * </ul>
 *
 * <h3>Usage with Cell:</h3>
 * <pre>{@code
 * // Create a Composer that transforms KafkaMetric → Alert
 * Composer<Pipe<KafkaMetric>, Alert> composer = channel -> {
 *     return new TransformingPipe<>(
 *         channel.pipe(),           // Get the Channel's Pipe<Alert>
 *         metric -> new Alert(metric)  // Transform function: KafkaMetric → Alert
 *     );
 * };
 *
 * // Use with Circuit.cell()
 * Cell<KafkaMetric, Alert> cell = circuit.cell(composer);
 * }</pre>
 *
 * @param <I> input type (what this Pipe receives)
 * @param <E> emission type (what gets emitted to the Channel)
 */
public class TransformingPipe<I, E> implements Pipe<I> {

    private final Pipe<E> delegate;
    private final Function<I, E> transformer;

    /**
     * Creates a TransformingPipe that transforms I → E.
     *
     * @param delegate the target Pipe<E> that receives transformed values
     * @param transformer the transformation function I → E
     */
    public TransformingPipe(Pipe<E> delegate, Function<I, E> transformer) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate pipe cannot be null");
        this.transformer = Objects.requireNonNull(transformer, "Transformer cannot be null");
    }

    @Override
    public void emit(I emission) {
        Objects.requireNonNull(emission, "Emission cannot be null");

        // Transform I → E
        E transformed = transformer.apply(emission);

        // Emit transformed value to delegate Pipe<E>
        if (transformed != null) {
            delegate.emit(transformed);
        }
    }
}
