package io.fullerstack.substrates.functional;

import io.fullerstack.substrates.name.LinkedName;
import io.humainary.substrates.api.Substrates.Name;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Streams}.
 */
class StreamsTest {

    @Test
    void hierarchical_producesAllPrefixes() {
        // Given: A hierarchical name
        Name name = new LinkedName("broker.1.heap", null);

        // When: Getting hierarchical stream
        List<String> prefixes = Streams.hierarchical(name)
            .map(n -> n.path().toString())
            .collect(Collectors.toList());

        // Then: Contains all prefixes
        assertThat(prefixes).containsExactly(
            "broker",
            "broker.1",
            "broker.1.heap"
        );
    }

    @Test
    void hierarchical_withSingleSegment() {
        // Given: A single-segment name
        Name name = new LinkedName("broker", null);

        // When: Getting hierarchical stream
        List<String> prefixes = Streams.hierarchical(name)
            .map(n -> n.path().toString())
            .collect(Collectors.toList());

        // Then: Contains only the single segment
        assertThat(prefixes).containsExactly("broker");
    }

    @Test
    void hierarchical_withFactory() {
        // Given: A hierarchical name and a factory
        Name name = new LinkedName("broker.1", null);

        // When: Applying factory to each prefix
        List<String> results = Streams.hierarchical(name, n -> "Conduit[" + n.path().toString() + "]")
            .collect(Collectors.toList());

        // Then: Factory applied to all prefixes
        assertThat(results).containsExactly(
            "Conduit[broker]",
            "Conduit[broker.1]"
        );
    }

    @Test
    void depths_returnsDepthLevels() {
        // Given: A hierarchical name
        Name name = new LinkedName("broker.1.heap", null);

        // When: Getting depth levels
        List<Integer> depths = Streams.depths(name)
            .collect(Collectors.toList());

        // Then: Returns 1, 2, 3
        assertThat(depths).containsExactly(1, 2, 3);
    }

    @Test
    void depth_calculatesCorrectly() {
        // Given: Various names
        Name single = new LinkedName("broker", null);
        Name double_ = new LinkedName("broker.1", null);
        Name triple = new LinkedName("broker.1.heap", null);

        // When: Calculating depth
        int depth1 = Streams.depth(single);
        int depth2 = Streams.depth(double_);
        int depth3 = Streams.depth(triple);

        // Then: Correct depths returned
        assertThat(depth1).isEqualTo(1);
        assertThat(depth2).isEqualTo(2);
        assertThat(depth3).isEqualTo(3);
    }

    @Test
    void atDepth_filtersCorrectly() {
        // Given: Hierarchical name
        Name name = new LinkedName("broker.1.heap", null);

        // When: Filtering at depth 2
        List<String> atDepth2 = Streams.hierarchical(name)
            .filter(Streams.atDepth(2))
            .map(n -> n.path().toString())
            .collect(Collectors.toList());

        // Then: Only depth-2 names included
        assertThat(atDepth2).containsExactly("broker.1");
    }

    @Test
    void flatten_flattensNestedStreams() {
        // Given: A stream of streams
        Stream<Stream<Integer>> nested = Stream.of(
            Stream.of(1, 2, 3),
            Stream.of(4, 5),
            Stream.of(6)
        );

        // When: Flattening
        List<Integer> flattened = Streams.flatten(nested)
            .collect(Collectors.toList());

        // Then: All elements in single stream
        assertThat(flattened).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void hierarchical_integration_withMultipleLevels() {
        // Given: A deep hierarchical name
        Name name = new LinkedName("partition.topic-1.0.offset", null);

        // When: Processing each level
        List<String> processed = Streams.hierarchical(name)
            .map(prefix -> {
                int depth = Streams.depth(prefix);
                return "Level " + depth + ": " + prefix.path().toString();
            })
            .collect(Collectors.toList());

        // Then: Each level processed correctly
        assertThat(processed).containsExactly(
            "Level 1: partition",
            "Level 2: partition.topic-1",
            "Level 3: partition.topic-1.0",
            "Level 4: partition.topic-1.0.offset"
        );
    }
}
