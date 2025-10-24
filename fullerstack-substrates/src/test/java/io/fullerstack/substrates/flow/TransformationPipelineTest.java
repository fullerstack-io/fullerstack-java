package io.fullerstack.substrates.flow;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.name.HierarchicalName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for TransformationPipeline transformation pipeline.
 */
class TransformationPipelineTest {

    @Test
    void shouldPassAllEmissionsWithNoTransformations() {
        TransformationPipeline<Integer> segment = new TransformationPipeline<>();

        assertThat(segment.apply(1)).isEqualTo(1);
        assertThat(segment.apply(2)).isEqualTo(2);
        assertThat(segment.apply(3)).isEqualTo(3);
    }

    @Test
    void shouldFilterWithGuard() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .guard(value -> value > 0);

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(-1)).isNull();
        assertThat(impl.apply(0)).isNull();
        assertThat(impl.apply(1)).isEqualTo(1);
        assertThat(impl.apply(5)).isEqualTo(5);
    }

    @Test
    void shouldLimitEmissions() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .limit(3);

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(1)).isEqualTo(1);
        assertThat(impl.apply(2)).isEqualTo(2);
        assertThat(impl.apply(3)).isEqualTo(3);
        assertThat(impl.apply(4)).isNull(); // Limit reached
        assertThat(impl.apply(5)).isNull(); // Still limited
    }

    @Test
    void shouldReplaceWithMapper() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .replace(value -> value * 2);

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(1)).isEqualTo(2);
        assertThat(impl.apply(5)).isEqualTo(10);
        assertThat(impl.apply(10)).isEqualTo(20);
    }

    @Test
    void shouldReduceWithAccumulator() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .reduce(0, Integer::sum);

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(1)).isEqualTo(1);   // 0 + 1
        assertThat(impl.apply(2)).isEqualTo(3);   // 1 + 2
        assertThat(impl.apply(3)).isEqualTo(6);   // 3 + 3
        assertThat(impl.apply(4)).isEqualTo(10);  // 6 + 4
    }

    @Test
    void shouldFilterDuplicatesWithDiff() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .diff();

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(1)).isEqualTo(1);   // First value passes
        assertThat(impl.apply(1)).isNull();       // Duplicate filtered
        assertThat(impl.apply(2)).isEqualTo(2);   // Changed value passes
        assertThat(impl.apply(2)).isNull();       // Duplicate filtered
        assertThat(impl.apply(1)).isEqualTo(1);   // Changed back passes
    }

    @Test
    void shouldFilterDuplicatesWithDiffInitial() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .diff(1);

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(1)).isNull();       // Same as initial - filtered
        assertThat(impl.apply(2)).isEqualTo(2);   // Different - passes
        assertThat(impl.apply(2)).isNull();       // Duplicate - filtered
    }

    @Test
    void shouldSampleEveryNthEmission() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .sample(3);

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(1)).isNull();       // 1st - filtered
        assertThat(impl.apply(2)).isNull();       // 2nd - filtered
        assertThat(impl.apply(3)).isEqualTo(3);   // 3rd - passes
        assertThat(impl.apply(4)).isNull();       // 4th - filtered
        assertThat(impl.apply(5)).isNull();       // 5th - filtered
        assertThat(impl.apply(6)).isEqualTo(6);   // 6th - passes
    }

    @Test
    void shouldPeekWithoutModifying() {
        List<Integer> peeked = new ArrayList<>();
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .peek(peeked::add)
            .guard(value -> value > 0);

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        impl.apply(-1);
        impl.apply(5);
        impl.apply(10);

        assertThat(peeked).containsExactly(-1, 5, 10);
    }

    @Test
    void shouldChainMultipleTransformations() {
        // Guard > 0, multiply by 2, limit to 3
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .guard(value -> value > 0)
            .replace(value -> value * 2)
            .limit(3);

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(-1)).isNull();      // Filtered by guard
        assertThat(impl.apply(1)).isEqualTo(2);   // 1 * 2 = 2
        assertThat(impl.apply(5)).isEqualTo(10);  // 5 * 2 = 10
        assertThat(impl.apply(7)).isEqualTo(14);  // 7 * 2 = 14
        assertThat(impl.apply(9)).isNull();       // Limit reached
    }

    @Test
    void shouldSiftAboveThreshold() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .sift(Integer::compareTo, sift -> sift.above(5));

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(3)).isNull();
        assertThat(impl.apply(5)).isNull();
        assertThat(impl.apply(6)).isEqualTo(6);
        assertThat(impl.apply(10)).isEqualTo(10);
    }

    @Test
    void shouldSiftBelowThreshold() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .sift(Integer::compareTo, sift -> sift.below(5));

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(3)).isEqualTo(3);
        assertThat(impl.apply(5)).isNull();
        assertThat(impl.apply(6)).isNull();
    }

    @Test
    void shouldSiftInRange() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .sift(Integer::compareTo, sift -> sift.range(5, 10));

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(3)).isNull();
        assertThat(impl.apply(5)).isEqualTo(5);
        assertThat(impl.apply(7)).isEqualTo(7);
        assertThat(impl.apply(10)).isEqualTo(10);
        assertThat(impl.apply(11)).isNull();
    }

    @Test
    void shouldSiftWithMin() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .sift(Integer::compareTo, sift -> sift.min(5));

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(3)).isNull();
        assertThat(impl.apply(5)).isEqualTo(5);
        assertThat(impl.apply(10)).isEqualTo(10);
    }

    @Test
    void shouldSiftWithMax() {
        Flow<Integer> segment = new TransformationPipeline<Integer>()
            .sift(Integer::compareTo, sift -> sift.max(10));

        TransformationPipeline<Integer> impl = (TransformationPipeline<Integer>) segment;

        assertThat(impl.apply(5)).isEqualTo(5);
        assertThat(impl.apply(10)).isEqualTo(10);
        assertThat(impl.apply(11)).isNull();
    }

    @Test
    void shouldRequireNonNullPredicate() {
        TransformationPipeline<Integer> segment = new TransformationPipeline<>();

        assertThatThrownBy(() -> segment.guard(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRequireNonNullMapper() {
        TransformationPipeline<Integer> segment = new TransformationPipeline<>();

        assertThatThrownBy(() -> segment.replace(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRequireNonNullAccumulator() {
        TransformationPipeline<Integer> segment = new TransformationPipeline<>();

        assertThatThrownBy(() -> segment.reduce(0, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRequireNonNegativeLimit() {
        TransformationPipeline<Integer> segment = new TransformationPipeline<>();

        assertThatThrownBy(() -> segment.limit(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRequirePositiveSampleRate() {
        TransformationPipeline<Integer> segment = new TransformationPipeline<>();

        assertThatThrownBy(() -> segment.sample(0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> segment.sample(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
