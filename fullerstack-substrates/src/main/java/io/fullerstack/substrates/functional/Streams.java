package io.fullerstack.substrates.functional;

import io.humainary.substrates.api.Substrates.Name;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Functional utilities for working with Streams.
 *
 * <p>Provides hierarchical iteration and stream composition helpers for Substrates entities.
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * // Hierarchical iteration over name prefixes
 * Name name = Name.name("broker.1.heap");
 * Stream<Name> prefixes = Streams.hierarchical(name);
 * // Produces: Stream("broker", "broker.1", "broker.1.heap")
 *
 * // Map each prefix to a Conduit
 * Stream<Conduit> conduits = Streams.hierarchical(name, circuit::conduit);
 * }</pre>
 *
 * @see Stream
 * @see Name
 */
@UtilityClass
public class Streams {

    /**
     * Returns a stream of all hierarchical prefixes for the given name.
     *
     * <p>For a name like "broker.1.heap", this produces:
     * <ul>
     *   <li>"broker"</li>
     *   <li>"broker.1"</li>
     *   <li>"broker.1.heap"</li>
     * </ul>
     *
     * <p>This enables hierarchical routing where signals flow through
     * each level of the hierarchy.
     *
     * @param name the hierarchical name
     * @return a stream of prefix names
     */
    public static Stream<Name> hierarchical(Name name) {
        String path = name.path().toString();
        if (path.isEmpty()) {
            return Stream.empty();
        }

        String[] parts = path.split("\\.");
        List<Name> prefixes = new ArrayList<>();

        // Build each hierarchical level
        Name current = null;
        for (String part : parts) {
            current = current == null
                ? new io.fullerstack.substrates.name.NameImpl(part, null)
                : new io.fullerstack.substrates.name.NameImpl(part, current);
            prefixes.add(current);
        }

        return prefixes.stream();
    }

    /**
     * Returns a stream of values produced by applying a factory function
     * to each hierarchical prefix of the given name.
     *
     * <p>This is a convenience method that combines {@link #hierarchical(Name)}
     * with a mapping function.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * // Create conduits for each hierarchical level
     * Stream<Conduit> conduits = Streams.hierarchical(
     *     Name.name("broker.1"),
     *     circuit::conduit
     * );
     * }</pre>
     *
     * @param name the hierarchical name
     * @param factory function to apply to each prefix
     * @param <T> the type of value produced
     * @return a stream of produced values
     */
    public static <T> Stream<T> hierarchical(Name name, Function<Name, T> factory) {
        return hierarchical(name).map(factory);
    }

    /**
     * Returns a stream of depth levels for a hierarchical name.
     *
     * <p>For "broker.1.heap", returns Stream(1, 2, 3).
     *
     * @param name the hierarchical name
     * @return stream of depth levels (1-indexed)
     */
    public static Stream<Integer> depths(Name name) {
        return hierarchical(name)
            .map(Streams::depth);
    }

    /**
     * Returns the depth of a name (number of segments).
     *
     * <p>Examples:
     * <ul>
     *   <li>"broker" → 1</li>
     *   <li>"broker.1" → 2</li>
     *   <li>"broker.1.heap" → 3</li>
     * </ul>
     *
     * @param name the name
     * @return the depth (1-indexed)
     */
    public static int depth(Name name) {
        CharSequence path = name.path();
        return path.isEmpty() ? 0 : path.toString().split("\\.").length;
    }

    /**
     * Filters a stream to include only names at a specific depth.
     *
     * @param depth the target depth (1-indexed)
     * @return a predicate for filtering
     */
    public static java.util.function.Predicate<Name> atDepth(int depth) {
        return name -> depth(name) == depth;
    }

    /**
     * Flattens a stream of streams into a single stream.
     *
     * @param streamOfStreams the nested stream
     * @param <T> the element type
     * @return a flattened stream
     */
    public static <T> Stream<T> flatten(Stream<Stream<T>> streamOfStreams) {
        return streamOfStreams.flatMap(Function.identity());
    }
}
