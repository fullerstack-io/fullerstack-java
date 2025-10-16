package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.util.Objects;

/**
 * Factory for creating LRUCachedName instances.
 *
 * <p>This factory creates {@link LRUCachedName} instances which use a strong
 * reference LRU cache with a fixed capacity (10,000 entries).
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Strong LRU cache (10,000 entries) - no GC until evicted</li>
 *   <li>Eager path caching for fast toString()</li>
 *   <li>Segment interning for reduced memory per entry</li>
 *   <li>Balanced creation/lookup performance</li>
 *   <li>Predictable memory footprint (bounded cache)</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Fully thread-safe singleton.
 *
 * <p><b>Best for:</b>
 * <ul>
 *   <li>Bounded, predictable memory usage requirements</li>
 *   <li>Hot path with limited name diversity (< 10k unique names)</li>
 *   <li>When you need guaranteed cache hits for recent names</li>
 *   <li>Environments where weak references cause GC churn</li>
 * </ul>
 *
 * <p><b>Trade-offs:</b>
 * <ul>
 *   <li>✅ Predictable memory (max 10k entries)</li>
 *   <li>✅ No GC pressure from weak reference cleanup</li>
 *   <li>❌ Cache eviction for names beyond capacity</li>
 *   <li>❌ Higher memory per entry than InternedName</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * NameFactory factory = LRUCachedNameFactory.getInstance();
 * Name name = factory.create("kafka.broker.1.jvm.heap.used");
 * </pre>
 *
 * @see LRUCachedName
 */
public final class LRUCachedNameFactory implements NameFactory {

    private static final LRUCachedNameFactory INSTANCE = new LRUCachedNameFactory();

    /**
     * Private constructor - use {@link #getInstance()}.
     */
    private LRUCachedNameFactory() {
    }

    /**
     * Get the singleton instance.
     *
     * @return the LRUCachedNameFactory singleton
     */
    public static LRUCachedNameFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public Name create(String path) {
        return LRUCachedName.of(path);
    }

    @Override
    public Name createRoot(String segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.contains(".")) {
            throw new IllegalArgumentException("Root segment cannot contain dots: " + segment);
        }
        // Use the optimized factory method which will cache the single-segment name
        return LRUCachedName.of(segment);
    }

    /**
     * Get statistics about the LRU cache.
     *
     * @return string with cache statistics
     */
    public String getCacheStatistics() {
        return String.format("LRUCachedName - Segment pool: %d, Cache size: %d",
            LRUCachedName.segmentPoolSize(),
            LRUCachedName.cacheSize());
    }

    /**
     * Clear the LRU cache (useful for testing).
     * <b>WARNING:</b> This will clear all cached names and reset statistics.
     */
    public void clearCache() {
        LRUCachedName.clearCaches();
    }

    @Override
    public String toString() {
        return "LRUCachedNameFactory{" + getCacheStatistics() + "}";
    }
}
