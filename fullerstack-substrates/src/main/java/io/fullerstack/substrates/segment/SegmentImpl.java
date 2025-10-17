package io.fullerstack.substrates.segment;

import io.humainary.substrates.api.Substrates.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.*;

/**
 * Implementation of Substrates.Segment for emission transformation pipelines.
 *
 * <p><b>Note:</b> Segment is the current name for what was originally called "Path" in early
 * Humainary blog articles. The API evolved to use "Segment" to better align with the
 * "assembly line" metaphor where each segment performs specific operations on emissions.
 *
 * <p>Segment provides a fluent API for building transformation pipelines that are applied
 * to emissions flowing through a Pipe. Transformations include filtering, mapping, reducing,
 * sampling, and comparison-based sifting.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Immutable - each transformation returns a new Segment</li>
 *   <li>Lazy - transformations are captured, not executed immediately</li>
 *   <li>Composable - transformations chain fluently</li>
 *   <li>Executed by Circuit - not by emitting thread (per Humainary design)</li>
 * </ul>
 *
 * <p>Usage (from blog examples):
 * <pre>
 * channel.pipe(
 *   segment -> segment
 *     .guard(value -> value > 0)
 *     .limit(10)
 *     .reduce(0, Integer::sum)
 * )
 * </pre>
 *
 * @param <E> emission type
 * @see <a href="https://humainary.io/blog/observability-x-channels/">Observability X - Channels</a>
 * @see <a href="https://humainary.io/blog/observability-x-staging-state/">Observability X - Staging State</a>
 */
public class SegmentImpl<E> implements Segment<E> {

    /**
     * Transformation operations that can be applied to emissions.
     */
    private final List<Function<E, TransformResult<E>>> transformations = new ArrayList<>();

    private long limitCount = Long.MAX_VALUE;
    private long emissionCount = 0;

    public SegmentImpl() {
    }

    @Override
    public Segment<E> diff() {
        E[] lastValue = (E[]) new Object[1];
        return addTransformation(value -> {
            if (lastValue[0] == null) {
                lastValue[0] = value;
                return TransformResult.pass(value);
            }
            if (!Objects.equals(value, lastValue[0])) {
                lastValue[0] = value;
                return TransformResult.pass(value);
            }
            return TransformResult.filter();
        });
    }

    @Override
    public Segment<E> diff(E initial) {
        E[] lastValue = (E[]) new Object[]{initial};
        return addTransformation(value -> {
            if (!Objects.equals(value, lastValue[0])) {
                lastValue[0] = value;
                return TransformResult.pass(value);
            }
            return TransformResult.filter();
        });
    }

    @Override
    public Segment<E> forward(Pipe<E> pipe) {
        Objects.requireNonNull(pipe, "Pipe cannot be null");
        return addTransformation(value -> {
            pipe.emit(value);
            return TransformResult.pass(value);
        });
    }

    @Override
    public Segment<E> guard(Predicate<? super E> predicate) {
        Objects.requireNonNull(predicate, "Predicate cannot be null");
        return addTransformation(value ->
            predicate.test(value) ? TransformResult.pass(value) : TransformResult.filter()
        );
    }

    @Override
    public Segment<E> guard(E reference, BiPredicate<? super E, ? super E> predicate) {
        Objects.requireNonNull(predicate, "BiPredicate cannot be null");
        return addTransformation(value ->
            predicate.test(value, reference) ? TransformResult.pass(value) : TransformResult.filter()
        );
    }

    @Override
    public Segment<E> limit(long maxEmissions) {
        if (maxEmissions < 0) {
            throw new IllegalArgumentException("Limit must be non-negative");
        }
        this.limitCount = maxEmissions;
        return this;
    }

    @Override
    public Segment<E> peek(Consumer<E> consumer) {
        Objects.requireNonNull(consumer, "Consumer cannot be null");
        return addTransformation(value -> {
            consumer.accept(value);
            return TransformResult.pass(value);
        });
    }

    @Override
    public Segment<E> reduce(E identity, BinaryOperator<E> accumulator) {
        Objects.requireNonNull(accumulator, "Accumulator cannot be null");
        E[] accumulated = (E[]) new Object[]{identity};
        return addTransformation(value -> {
            accumulated[0] = accumulator.apply(accumulated[0], value);
            return TransformResult.replace(accumulated[0]);
        });
    }

    @Override
    public Segment<E> replace(UnaryOperator<E> mapper) {
        Objects.requireNonNull(mapper, "Mapper cannot be null");
        return addTransformation(value -> TransformResult.replace(mapper.apply(value)));
    }

    @Override
    public Segment<E> sample(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive");
        }
        long[] counter = {0};
        return addTransformation(value -> {
            counter[0]++;
            return (counter[0] % n == 0) ? TransformResult.pass(value) : TransformResult.filter();
        });
    }

    @Override
    public Segment<E> sample(double probability) {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("Probability must be between 0.0 and 1.0");
        }
        return addTransformation(value ->
            Math.random() < probability ? TransformResult.pass(value) : TransformResult.filter()
        );
    }

    @Override
    public Segment<E> sift(Comparator<E> comparator, Sequencer<? super Sift<E>> sequencer) {
        Objects.requireNonNull(comparator, "Comparator cannot be null");
        Objects.requireNonNull(sequencer, "Sequencer cannot be null");

        SiftImpl<E> sift = new SiftImpl<>(comparator);
        sequencer.apply(sift);

        return addTransformation(value ->
            sift.test(value) ? TransformResult.pass(value) : TransformResult.filter()
        );
    }

    /**
     * Applies all transformations to an emission.
     *
     * @param emission the value to transform
     * @return the transformed value, or null if filtered
     */
    public E apply(E emission) {
        if (emissionCount >= limitCount) {
            return null; // Limit reached
        }

        E current = emission;
        for (Function<E, TransformResult<E>> transformation : transformations) {
            TransformResult<E> result = transformation.apply(current);
            if (result.isFiltered()) {
                return null; // Filtered out
            }
            current = result.getValue();
        }

        emissionCount++;
        return current;
    }

    /**
     * Checks if the limit has been reached.
     */
    public boolean hasReachedLimit() {
        return emissionCount >= limitCount;
    }

    private Segment<E> addTransformation(Function<E, TransformResult<E>> transformation) {
        this.transformations.add(transformation);
        return this;
    }

    /**
     * Result of applying a transformation.
     */
    private static class TransformResult<E> {
        private final E value;
        private final boolean filtered;

        private TransformResult(E value, boolean filtered) {
            this.value = value;
            this.filtered = filtered;
        }

        static <E> TransformResult<E> pass(E value) {
            return new TransformResult<>(value, false);
        }

        static <E> TransformResult<E> replace(E value) {
            return new TransformResult<>(value, false);
        }

        static <E> TransformResult<E> filter() {
            return new TransformResult<>(null, true);
        }

        E getValue() {
            return value;
        }

        boolean isFiltered() {
            return filtered;
        }
    }
}
