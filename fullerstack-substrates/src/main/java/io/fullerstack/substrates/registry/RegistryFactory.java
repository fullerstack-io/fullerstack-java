package io.fullerstack.substrates.registry;

/**
 * Factory interface for creating Registry implementations.
 *
 * <p>This abstraction allows different registry strategies to be plugged in:
 * <ul>
 *   <li>{@link FlatMapRegistry} - Simple ConcurrentHashMap (no hierarchy)</li>
 *   <li>{@link StringSplitTrieRegistry} - Eager trie with string splitting overhead</li>
 *   <li>{@link EagerTrieRegistry} - Eager trie optimized for InternedName</li>
 *   <li>{@link LazyTrieRegistry} - Lazy trie construction (recommended)</li>
 * </ul>
 *
 * <p><b>Registry Characteristics Comparison:</b>
 * <table border="1">
 * <tr>
 *   <th>Implementation</th>
 *   <th>Direct Lookup</th>
 *   <th>Subtree Query</th>
 *   <th>Best For</th>
 * </tr>
 * <tr>
 *   <td>FlatMapRegistry</td>
 *   <td>O(1)</td>
 *   <td>Not supported</td>
 *   <td>No hierarchy needed</td>
 * </tr>
 * <tr>
 *   <td>StringSplitTrieRegistry</td>
 *   <td>O(1)</td>
 *   <td>O(k)</td>
 *   <td>Any Name type, eager indexing</td>
 * </tr>
 * <tr>
 *   <td>EagerTrieRegistry</td>
 *   <td>O(1)</td>
 *   <td>O(k)</td>
 *   <td>InternedName, eager indexing</td>
 * </tr>
 * <tr>
 *   <td>LazyTrieRegistry</td>
 *   <td>O(1)</td>
 *   <td>O(k)</td>
 *   <td>Mixed workload (recommended)</td>
 * </tr>
 * </table>
 *
 * <p><b>Usage:</b>
 * <pre>
 * RegistryFactory factory = LazyTrieRegistryFactory.getInstance();
 * LazyTrieRegistry&lt;MetricValue&gt; registry = factory.create();
 * </pre>
 *
 * <p><b>Design Pattern:</b>
 * Each factory is a thread-safe singleton that creates new Registry instances.
 * Registries themselves are thread-safe and can be shared across threads.
 *
 * @param <T> the type of values stored in the registry
 * @see FlatMapRegistry
 * @see StringSplitTrieRegistry
 * @see EagerTrieRegistry
 * @see LazyTrieRegistry
 */
public interface RegistryFactory {

    /**
     * Creates a new Registry instance.
     *
     * @param <T> the type of values stored in the registry
     * @return a new Registry implementation
     */
    <T> Object create();
}
