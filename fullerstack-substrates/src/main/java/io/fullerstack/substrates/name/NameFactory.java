package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.lang.reflect.Member;
import java.util.Iterator;
import java.util.function.Function;

/**
 * Factory for creating Name instances.
 *
 * <p>This abstraction allows pluggable Name implementations, enabling the system
 * to swap between different Name strategies (interned, linked, array-backed, cached)
 * without changing application code.
 *
 * <p><b>Usage:</b>
 * <pre>
 * NameFactory factory = InternedNameFactory.getInstance();
 * Name name = factory.create("kafka.broker.1.jvm.heap.used");
 * </pre>
 *
 * <p><b>Available implementations:</b>
 * <ul>
 *   <li>{@link InternedNameFactory} - Recommended for production (weak interning)</li>
 *   <li>{@link LinkedNameFactory} - Simple linked list (baseline)</li>
 *   <li>{@link SegmentArrayNameFactory} - Array-backed with O(1) depth</li>
 *   <li>{@link LRUCachedNameFactory} - Strong LRU cache (10k entries)</li>
 * </ul>
 *
 * @see InternedName
 * @see LinkedName
 * @see SegmentArrayName
 * @see LRUCachedName
 */
public interface NameFactory {

    /**
     * Create a Name from a dot-separated path string.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "kafka"} → single segment</li>
     *   <li>{@code "kafka.broker.1"} → hierarchical path</li>
     *   <li>{@code "kafka.broker.1.jvm.heap.used"} → deep path</li>
     * </ul>
     *
     * @param path the dot-separated path (must not be null, empty, or have leading/trailing dots)
     * @return a Name instance representing the path
     * @throws NullPointerException if path is null
     * @throws IllegalArgumentException if path is invalid (empty, starts/ends with dot, contains "..")
     */
    Name create(String path);

    /**
     * Create a root Name from a single segment.
     *
     * <p>This is an optimization for creating single-segment names without parsing.
     * Equivalent to {@code create(segment)} but potentially faster.
     *
     * @param segment the segment name (must not be null or contain dots)
     * @return a Name instance with no parent
     * @throws NullPointerException if segment is null
     * @throws IllegalArgumentException if segment contains dots
     */
    Name createRoot(String segment);

    /**
     * Create a Name from an enum constant.
     *
     * <p>Uses the enum's simple name (not the declaring class).
     *
     * @param e the enum constant
     * @return a Name instance
     * @throws NullPointerException if e is null
     */
    default Name create(Enum<?> e) {
        return createRoot(e.name());
    }

    /**
     * Create a Name from a class.
     *
     * <p>Uses the class simple name.
     *
     * @param clazz the class
     * @return a Name instance
     * @throws NullPointerException if clazz is null
     */
    default Name create(Class<?> clazz) {
        return createRoot(clazz.getSimpleName());
    }

    /**
     * Create a Name from a member (method/field).
     *
     * @param member the member
     * @return a Name instance
     * @throws NullPointerException if member is null
     */
    default Name create(Member member) {
        return createRoot(member.getName());
    }

    /**
     * Create a Name from an iterable of string parts.
     *
     * <p>Example: {@code ["kafka", "broker", "1"]} → {@code "kafka.broker.1"}
     *
     * @param parts the string parts to join
     * @return a Name instance
     * @throws NullPointerException if parts is null
     * @throws IllegalArgumentException if parts is empty
     */
    default Name create(Iterable<String> parts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String part : parts) {
            if (!first) sb.append('.');
            sb.append(part);
            first = false;
        }
        if (sb.length() == 0) {
            throw new IllegalArgumentException("At least one part required");
        }
        return create(sb.toString());
    }

    /**
     * Create a Name from an iterable with a mapper function.
     *
     * @param items the items to map
     * @param mapper the function to extract string from each item
     * @param <T> the item type
     * @return a Name instance
     * @throws NullPointerException if items or mapper is null
     * @throws IllegalArgumentException if items is empty
     */
    default <T> Name create(Iterable<? extends T> items, Function<T, String> mapper) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (T item : items) {
            if (!first) sb.append('.');
            sb.append(mapper.apply(item));
            first = false;
        }
        if (sb.length() == 0) {
            throw new IllegalArgumentException("At least one item required");
        }
        return create(sb.toString());
    }

    /**
     * Create a Name from an iterator of string parts.
     *
     * @param parts the iterator of parts
     * @return a Name instance
     * @throws NullPointerException if parts is null
     * @throws IllegalArgumentException if iterator is empty
     */
    default Name create(Iterator<String> parts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        while (parts.hasNext()) {
            if (!first) sb.append('.');
            sb.append(parts.next());
            first = false;
        }
        if (sb.length() == 0) {
            throw new IllegalArgumentException("At least one part required");
        }
        return create(sb.toString());
    }

    /**
     * Create a Name from an iterator with a mapper function.
     *
     * @param items the iterator of items
     * @param mapper the function to extract string from each item
     * @param <T> the item type
     * @return a Name instance
     * @throws NullPointerException if items or mapper is null
     * @throws IllegalArgumentException if iterator is empty
     */
    default <T> Name create(Iterator<? extends T> items, Function<T, String> mapper) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        while (items.hasNext()) {
            if (!first) sb.append('.');
            sb.append(mapper.apply(items.next()));
            first = false;
        }
        if (sb.length() == 0) {
            throw new IllegalArgumentException("At least one item required");
        }
        return create(sb.toString());
    }
}
