package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.util.Objects;

/**
 * Factory for creating InternedName instances.
 *
 * <p>This is the <b>recommended factory for production use</b>. It creates
 * {@link InternedName} instances which use weak reference interning for
 * memory efficiency and fast equality checks.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Fast creation via global interning (reuses instances)</li>
 *   <li>Fast equality checks via reference equality (==)</li>
 *   <li>Bounded memory via weak references (GC collects unused names)</li>
 *   <li>Lazy toString() computation (cached after first call)</li>
 *   <li>Zero string splitting overhead (parent chain traversal)</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Fully thread-safe singleton.
 *
 * <p><b>Usage:</b>
 * <pre>
 * NameFactory factory = InternedNameFactory.getInstance();
 * Name name = factory.create("kafka.broker.1.jvm.heap.used");
 * </pre>
 *
 * @see InternedName
 */
public final class InternedNameFactory implements NameFactory {

    private static final InternedNameFactory INSTANCE = new InternedNameFactory();

    /**
     * Private constructor - use {@link #getInstance()}.
     */
    private InternedNameFactory() {
    }

    /**
     * Get the singleton instance.
     *
     * @return the InternedNameFactory singleton
     */
    public static InternedNameFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public Name create(String path) {
        return InternedName.of(path);
    }

    @Override
    public Name createRoot(String segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.contains(".")) {
            throw new IllegalArgumentException("Root segment cannot contain dots: " + segment);
        }
        // Use the optimized factory method which will intern the single-segment name
        return InternedName.of(segment);
    }

    /**
     * Get statistics about the interning pools.
     *
     * @return string with pool sizes
     */
    public String getPoolStatistics() {
        return String.format("InternedName pools - Segments: %d, Full paths: %d",
            InternedName.segmentPoolSize(),
            InternedName.internPoolSize());
    }

    /**
     * Clear the interning pools (useful for testing).
     * <b>WARNING:</b> This will break reference equality for existing InternedName instances.
     */
    public void clearPools() {
        InternedName.clearPools();
    }

    @Override
    public String toString() {
        return "InternedNameFactory{" + getPoolStatistics() + "}";
    }
}
