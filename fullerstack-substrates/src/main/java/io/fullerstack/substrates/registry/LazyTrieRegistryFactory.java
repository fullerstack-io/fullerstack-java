package io.fullerstack.substrates.registry;

/**
 * Factory for creating {@link LazyTrieRegistry} instances.
 *
 * <p>This factory creates {@link LazyTrieRegistry} instances which use a hybrid
 * dual-index approach with lazy trie construction.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Direct lookups: O(1) via ConcurrentHashMap</li>
 *   <li>Subtree queries: O(k) via lazy trie (built on first query)</li>
 *   <li>Zero string splitting when using InternedName</li>
 *   <li>Optimal for mixed workloads (direct + hierarchical)</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Fully thread-safe singleton.
 *
 * <p><b>Best for:</b>
 * <ul>
 *   <li>Production use with mixed query patterns</li>
 *   <li>When subtree queries are infrequent</li>
 *   <li>Memory-efficient (trie built only when needed)</li>
 *   <li>Kafka metrics monitoring (recommended default)</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * RegistryFactory factory = LazyTrieRegistryFactory.getInstance();
 * Map&lt;Name, MetricValue&gt; registry = factory.create(); // LazyTrieRegistry implements Map
 *
 * // Standard Map operations - O(1), no trie overhead
 * registry.put(name, value);
 * MetricValue v = registry.get(name);
 *
 * // Hierarchical query - cast to LazyTrieRegistry for getSubtree
 * Map&lt;Name, MetricValue&gt; subtree = ((LazyTrieRegistry&lt;MetricValue&gt;) registry).getSubtree(prefix);
 * </pre>
 *
 * @see LazyTrieRegistry
 * @see RegistryFactory
 */
public final class LazyTrieRegistryFactory implements RegistryFactory {

    private static final LazyTrieRegistryFactory INSTANCE = new LazyTrieRegistryFactory();

    /**
     * Private constructor - use {@link #getInstance()}.
     */
    private LazyTrieRegistryFactory() {
    }

    /**
     * Get the singleton instance.
     *
     * @return the LazyTrieRegistryFactory singleton
     */
    public static LazyTrieRegistryFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public <T> LazyTrieRegistry<T> create() {
        return new LazyTrieRegistry<>();
    }

    @Override
    public String toString() {
        return "LazyTrieRegistryFactory{type=hybrid-lazy}";
    }
}
