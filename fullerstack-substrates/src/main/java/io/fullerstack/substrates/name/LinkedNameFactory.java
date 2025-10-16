package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory for creating LinkedName instances.
 *
 * <p>This factory creates {@link LinkedName} instances which use a simple
 * parent-child linked structure. This is the baseline implementation.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Simple linked list structure</li>
 *   <li>Eager path caching at construction</li>
 *   <li>Root name caching for common single-segment names</li>
 *   <li>No global interning (each call creates new instance unless root cached)</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Fully thread-safe singleton.
 *
 * <p><b>Usage:</b>
 * <pre>
 * NameFactory factory = LinkedNameFactory.getInstance();
 * Name name = factory.create("kafka.broker.1");
 * </pre>
 *
 * @see LinkedName
 */
public final class LinkedNameFactory implements NameFactory {

    private static final LinkedNameFactory INSTANCE = new LinkedNameFactory();

    /**
     * Cache for root names (single segment, no parent).
     * Reduces allocation pressure for frequently used root names.
     */
    private final ConcurrentMap<String, LinkedName> rootCache = new ConcurrentHashMap<>();

    /**
     * Private constructor - use {@link #getInstance()}.
     */
    private LinkedNameFactory() {
    }

    /**
     * Get the singleton instance.
     *
     * @return the LinkedNameFactory singleton
     */
    public static LinkedNameFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public Name create(String path) {
        Objects.requireNonNull(path, "path");
        validatePath(path);

        String[] parts = path.split("\\.");
        if (parts.length == 1) {
            return createRoot(parts[0]);
        }

        // Build hierarchical name
        LinkedName current = (LinkedName) createRoot(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            current = new LinkedName(parts[i], current);
        }
        return current;
    }

    @Override
    public Name createRoot(String segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.contains(".")) {
            throw new IllegalArgumentException("Root segment cannot contain dots: " + segment);
        }
        return rootCache.computeIfAbsent(segment, s -> new LinkedName(s, null));
    }

    /**
     * Get statistics about the root name cache.
     *
     * @return string with cache size
     */
    public String getCacheStatistics() {
        return String.format("LinkedName root cache size: %d", rootCache.size());
    }

    /**
     * Clear the root name cache (useful for testing).
     */
    public void clearCache() {
        rootCache.clear();
    }

    /**
     * Validate path format.
     */
    private void validatePath(String path) {
        if (path.isEmpty() || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("Invalid name path: " + path);
        }
    }

    @Override
    public String toString() {
        return "LinkedNameFactory{" + getCacheStatistics() + "}";
    }
}
