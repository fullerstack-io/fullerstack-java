package io.fullerstack.substrates.registry;

/**
 * Factory for creating {@link EagerTrieRegistry} instances.
 *
 * <p>This factory creates {@link EagerTrieRegistry} instances which use
 * eager trie construction optimized for InternedName with parent chain traversal.
 *
 * <p><b>Characteristics:</b>
 * <ul>
 *   <li>Direct lookups: O(1) via ConcurrentHashMap + IdentityHashMap</li>
 *   <li>Subtree queries: O(k) via eager trie</li>
 *   <li>Zero string splitting for InternedName</li>
 *   <li>Eager trie maintenance on every write</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Fully thread-safe singleton.
 *
 * <p><b>Best for:</b>
 * <ul>
 *   <li>Very frequent subtree queries</li>
 *   <li>Exclusive use of InternedName</li>
 *   <li>When write latency is less critical</li>
 *   <li>Benchmarking optimized eager trie</li>
 * </ul>
 *
 * <p><b>Trade-offs:</b>
 * <ul>
 *   <li>✅ Zero string splitting with InternedName</li>
 *   <li>✅ Subtree queries always ready</li>
 *   <li>✅ Identity map fast path (reference equality)</li>
 *   <li>❌ Eager trie maintenance overhead</li>
 *   <li>❌ Higher memory usage (identity map + trie)</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * RegistryFactory factory = EagerTrieRegistryFactory.getInstance();
 * EagerTrieRegistry&lt;MetricValue&gt; registry = factory.create();
 *
 * Name name = InternedName.of("kafka.broker.1.heap");
 * registry.put(name, value);
 * Map&lt;Name, MetricValue&gt; subtree = registry.getSubtree(prefix);
 * </pre>
 *
 * @see EagerTrieRegistry
 * @see RegistryFactory
 */
public final class EagerTrieRegistryFactory implements RegistryFactory {

    private static final EagerTrieRegistryFactory INSTANCE = new EagerTrieRegistryFactory();

    /**
     * Private constructor - use {@link #getInstance()}.
     */
    private EagerTrieRegistryFactory() {
    }

    /**
     * Get the singleton instance.
     *
     * @return the EagerTrieRegistryFactory singleton
     */
    public static EagerTrieRegistryFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public <T> EagerTrieRegistry<T> create() {
        return new EagerTrieRegistry<>();
    }

    @Override
    public String toString() {
        return "EagerTrieRegistryFactory{type=eager-interned-optimized}";
    }
}
