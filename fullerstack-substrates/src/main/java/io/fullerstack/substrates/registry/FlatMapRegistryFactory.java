package io.fullerstack.substrates.registry;

/**
 * Factory for creating {@link FlatMapRegistry} instances.
 *
 * <p>This factory creates {@link FlatMapRegistry} instances which use a simple
 * ConcurrentHashMap with no hierarchical indexing support.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Direct lookups: O(1) via ConcurrentHashMap</li>
 *   <li>Subtree queries: Not supported</li>
 *   <li>Minimal memory overhead</li>
 *   <li>Baseline performance</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Fully thread-safe singleton.
 *
 * <p><b>Best for:</b>
 * <ul>
 *   <li>No hierarchical queries needed</li>
 *   <li>Minimal memory footprint required</li>
 *   <li>Baseline benchmarking</li>
 *   <li>Simple key-value storage</li>
 * </ul>
 *
 * <p><b>Limitations:</b>
 * <ul>
 *   <li>No getSubtree() support</li>
 *   <li>No hierarchical traversal</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * RegistryFactory factory = FlatMapRegistryFactory.getInstance();
 * FlatMapRegistry&lt;String&gt; registry = factory.create();
 *
 * registry.put(name, value);
 * String v = registry.get(name);
 * </pre>
 *
 * @see FlatMapRegistry
 * @see RegistryFactory
 */
public final class FlatMapRegistryFactory implements RegistryFactory {

    private static final FlatMapRegistryFactory INSTANCE = new FlatMapRegistryFactory();

    /**
     * Private constructor - use {@link #getInstance()}.
     */
    private FlatMapRegistryFactory() {
    }

    /**
     * Get the singleton instance.
     *
     * @return the FlatMapRegistryFactory singleton
     */
    public static FlatMapRegistryFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public <T> FlatMapRegistry<T> create() {
        return new FlatMapRegistry<>();
    }

    @Override
    public String toString() {
        return "FlatMapRegistryFactory{type=flat-no-hierarchy}";
    }
}
