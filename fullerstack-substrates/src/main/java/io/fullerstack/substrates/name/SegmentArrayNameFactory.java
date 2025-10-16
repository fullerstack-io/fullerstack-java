package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.util.Objects;

/**
 * Factory for creating SegmentArrayName instances.
 *
 * <p>This factory creates {@link SegmentArrayName} instances which use an
 * array-backed structure with weak reference interning.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Array-backed segment storage for O(1) depth calculation</li>
 *   <li>Weak reference interning for bounded memory</li>
 *   <li>Best performance for very deep hierarchical paths</li>
 *   <li>Excellent prefix checking performance</li>
 *   <li>Slightly higher memory overhead than LinkedName</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Fully thread-safe singleton.
 *
 * <p><b>Best for:</b>
 * <ul>
 *   <li>Very deep hierarchical paths (10+ levels)</li>
 *   <li>Frequent depth() and prefix checking operations</li>
 *   <li>When O(1) depth calculation is critical</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * NameFactory factory = SegmentArrayNameFactory.getInstance();
 * Name name = factory.create("kafka.broker.1.jvm.heap.used.max.current");
 * int depth = name.depth(); // O(1) - no traversal needed
 * </pre>
 *
 * @see SegmentArrayName
 */
public final class SegmentArrayNameFactory implements NameFactory {

    private static final SegmentArrayNameFactory INSTANCE = new SegmentArrayNameFactory();

    /**
     * Private constructor - use {@link #getInstance()}.
     */
    private SegmentArrayNameFactory() {
    }

    /**
     * Get the singleton instance.
     *
     * @return the SegmentArrayNameFactory singleton
     */
    public static SegmentArrayNameFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public Name create(String path) {
        return SegmentArrayName.of(path);
    }

    @Override
    public Name createRoot(String segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.contains(".")) {
            throw new IllegalArgumentException("Root segment cannot contain dots: " + segment);
        }
        // Use the optimized factory method which will intern the single-segment name
        return SegmentArrayName.of(segment);
    }

    /**
     * Get statistics about the interning pools.
     *
     * @return string with pool sizes
     */
    public String getPoolStatistics() {
        return String.format("SegmentArrayName pools - Segments: %d, Full paths: %d",
            SegmentArrayName.segmentPoolSize(),
            SegmentArrayName.internPoolSize());
    }

    /**
     * Clear the interning pools (useful for testing).
     * <b>WARNING:</b> This will break reference equality for existing SegmentArrayName instances.
     */
    public void clearPools() {
        SegmentArrayName.clearPools();
    }

    @Override
    public String toString() {
        return "SegmentArrayNameFactory{" + getPoolStatistics() + "}";
    }
}
