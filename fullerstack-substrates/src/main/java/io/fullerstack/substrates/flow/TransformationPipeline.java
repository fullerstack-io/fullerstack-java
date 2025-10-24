package io.fullerstack.substrates.flow;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.segment.FilteringSegment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.*;

/**
 * Implementation of Substrates.Flow for emission transformation pipelines.
 *
 * <p><b>Note:</b> Flow is the latest name in the API evolution: Path → Segment → Flow.
 * The API evolved to use "Flow" to better represent the continuous data stream concept
 * with Consumer<Flow> replacing the Sequencer pattern.
 *
 * <p>Flow provides a fluent API for building transformation pipelines that are applied
 * to emissions flowing through a Pipe. Transformations include filtering, mapping, reducing,
 * sampling, and comparison-based sifting.
 *
 * <p>Key characteristics:
 * <ul>
 *   <li>Immutable - each transformation returns a new Flow</li>
 *   <li>Lazy - transformations are captured, not executed immediately</li>
 *   <li>Composable - transformations chain fluently with @Fluent annotations</li>
 *   <li>Executed by Circuit - not by emitting thread (per Humainary design)</li>
 * </ul>
 *
 * <p>Usage (M15+ with Consumer):
 * <pre>
 * channel.pipe(
 *   flow -> flow
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
public class TransformationPipeline<E> implements Flow<E> {

    /**
     * Transformation operations that can be applied to emissions.
     */
    private final List<Function<E, TransformResult<E>>> transformations = new ArrayList<>();

    public TransformationPipeline() {
    }

    @Override
    public Flow<E> diff() {
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
    public Flow<E> diff(E initial) {
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
    public Flow<E> forward(Pipe<E> pipe) {
        Objects.requireNonNull(pipe, "Pipe cannot be null");
        return addTransformation(value -> {
            pipe.emit(value);
            return TransformResult.pass(value);
        });
    }

    public Flow<E> forward(Consumer<? super E> consumer) {
        Objects.requireNonNull(consumer, "Consumer cannot be null");
        return addTransformation(value -> {
            consumer.accept(value);
            return TransformResult.pass(value);
        });
    }

    @Override
    public Flow<E> guard(Predicate<? super E> predicate) {
        Objects.requireNonNull(predicate, "Predicate cannot be null");
        return addTransformation(value ->
            predicate.test(value) ? TransformResult.pass(value) : TransformResult.filter()
        );
    }

    @Override
    public Flow<E> guard(E reference, BiPredicate<? super E, ? super E> predicate) {
        Objects.requireNonNull(predicate, "BiPredicate cannot be null");
        E[] previous = (E[]) new Object[]{reference};
        return addTransformation(value -> {
            boolean passes = predicate.test(previous[0], value);
            if (passes) {
                previous[0] = value; // Update previous for next emission
                return TransformResult.pass(value);
            }
            return TransformResult.filter();
        });
    }

    @Override
    public Flow<E> limit(int maxEmissions) {
        return limit((long) maxEmissions);
    }

    @Override
    public Flow<E> limit(long maxEmissions) {
        if (maxEmissions < 0) {
            throw new IllegalArgumentException("Limit must be non-negative");
        }
        long[] counter = {0};
        return addTransformation(value -> {
            if (counter[0] >= maxEmissions) {
                return TransformResult.filter(); // Limit reached
            }
            counter[0]++;
            return TransformResult.pass(value);
        });
    }

    @Override
    public Flow<E> skip(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("Skip count must be non-negative");
        }
        long[] counter = {0};
        return addTransformation(value -> {
            if (counter[0] < n) {
                counter[0]++;
                return TransformResult.filter(); // Still skipping
            }
            return TransformResult.pass(value);
        });
    }

    @Override
    public Flow<E> peek(Consumer<E> consumer) {
        Objects.requireNonNull(consumer, "Consumer cannot be null");
        return addTransformation(value -> {
            consumer.accept(value);
            return TransformResult.pass(value);
        });
    }

    @Override
    public Flow<E> reduce(E identity, BinaryOperator<E> accumulator) {
        Objects.requireNonNull(accumulator, "Accumulator cannot be null");
        E[] accumulated = (E[]) new Object[]{identity};
        return addTransformation(value -> {
            accumulated[0] = accumulator.apply(accumulated[0], value);
            return TransformResult.replace(accumulated[0]);
        });
    }

    @Override
    public Flow<E> replace(UnaryOperator<E> mapper) {
        Objects.requireNonNull(mapper, "Mapper cannot be null");
        return addTransformation(value -> TransformResult.replace(mapper.apply(value)));
    }

    @Override
    public Flow<E> sample(int n) {
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
    public Flow<E> sample(double probability) {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException("Probability must be between 0.0 and 1.0");
        }
        return addTransformation(value ->
            Math.random() < probability ? TransformResult.pass(value) : TransformResult.filter()
        );
    }

    @Override
    public Flow<E> sift(Comparator<E> comparator, Consumer<? super Sift<E>> configurer) {
        Objects.requireNonNull(comparator, "Comparator cannot be null");
        Objects.requireNonNull(configurer, "Configurer cannot be null");

        FilteringSegment<E> sift = new FilteringSegment<>(comparator);
        configurer.accept(sift);

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
        E current = emission;
        for (Function<E, TransformResult<E>> transformation : transformations) {
            TransformResult<E> result = transformation.apply(current);
            if (result.isFiltered()) {
                return null; // Filtered out
            }
            current = result.getValue();
        }
        return current;
    }

    private Flow<E> addTransformation(Function<E, TransformResult<E>> transformation) {
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
