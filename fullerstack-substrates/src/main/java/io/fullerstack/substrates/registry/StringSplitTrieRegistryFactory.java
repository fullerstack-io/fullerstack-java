package io.fullerstack.substrates.registry;

/**
 * Factory for creating {@link StringSplitTrieRegistry} instances.
 *
 * <p>This factory creates {@link StringSplitTrieRegistry} instances which use
 * eager trie construction with string splitting for all Name types.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Direct lookups: O(1) via ConcurrentHashMap</li>
 *   <li>Subtree queries: O(k) via eager trie</li>
 *   <li>String splitting overhead on every insert</li>
 *   <li>Works with any Name implementation</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Fully thread-safe singleton.
 *
 * <p><b>Best for:</b>
 * <ul>
 *   <li>Frequent subtree queries</li>
 *   <li>Mixed Name implementations (not just InternedName)</li>
 *   <li>When eager indexing is acceptable</li>
 *   <li>Benchmarking baseline trie performance</li>
 * </ul>
 *
 * <p><b>Trade-offs:</b>
 * <ul>
 *   <li>✅ Works with any Name type</li>
 *   <li>✅ Subtree queries always ready</li>
 *   <li>❌ String splitting overhead on writes</li>
 *   <li>❌ Eager trie maintenance</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * RegistryFactory factory = StringSplitTrieRegistryFactory.getInstance();
 * StringSplitTrieRegistry&lt;MetricValue&gt; registry = factory.create();
 *
 * registry.put(name, value);
 * Map&lt;Name, MetricValue&gt; subtree = registry.getSubtree(prefix);
 * </pre>
 *
 * @see StringSplitTrieRegistry
 * @see RegistryFactory
 */
public final class StringSplitTrieRegistryFactory implements RegistryFactory {

    private static final StringSplitTrieRegistryFactory INSTANCE = new StringSplitTrieRegistryFactory();

    /**
     * Private constructor - use {@link #getInstance()}.
     */
    private StringSplitTrieRegistryFactory() {
    }

    /**
     * Get the singleton instance.
     *
     * @return the StringSplitTrieRegistryFactory singleton
     */
    public static StringSplitTrieRegistryFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public <T> StringSplitTrieRegistry<T> create() {
        return new StringSplitTrieRegistry<>();
    }

    @Override
    public String toString() {
        return "StringSplitTrieRegistryFactory{type=eager-string-split}";
    }
}
