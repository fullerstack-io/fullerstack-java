package io.fullerstack.substrates.registry;

import io.fullerstack.substrates.name.InternedName;
import io.humainary.substrates.api.Substrates.Name;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Hybrid registry optimized for both direct lookups and hierarchical subtree queries.
 *
 * <p>Uses a dual-index approach with lazy trie construction:
 * <ul>
 *   <li><b>Primary index:</b> ConcurrentHashMap for O(1) direct lookups</li>
 *   <li><b>Secondary index:</b> Lazy trie built only when subtree queries are performed</li>
 *   <li><b>Optimization:</b> Uses InternedName parent chain to avoid string splitting</li>
 * </ul>
 *
 * <h3>Performance Characteristics:</h3>
 * <ul>
 *   <li>Direct get/put: O(1) - pure ConcurrentHashMap performance</li>
 *   <li>Subtree queries: O(k) where k = result set size (trie traversal)</li>
 *   <li>First subtree query: Builds entire trie from current registry contents</li>
 *   <li>Zero string splitting when using InternedName instances</li>
 * </ul>
 *
 * <h3>Thread Safety:</h3>
 * <ul>
 *   <li>All direct operations (get/put/remove) are thread-safe via ConcurrentHashMap</li>
 *   <li>Trie construction is synchronized and happens once on first subtree query</li>
 *   <li>Incremental trie updates after initial build are synchronized</li>
 * </ul>
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * LazyTrieRegistry<MetricValue> registry = new LazyTrieRegistry<>();
 *
 * // Fast direct lookups - no trie overhead
 * Name brokerMetric = InternedName.of("kafka.broker.1.jvm.heap.used");
 * registry.put(brokerMetric, new MetricValue(12345));
 * MetricValue value = registry.get(brokerMetric);  // O(1) lookup
 *
 * // Hierarchical subtree query - builds trie on first call
 * Name prefix = InternedName.of("kafka.broker.1");
 * Map<Name, MetricValue> subtree = registry.getSubtree(prefix);  // All metrics under kafka.broker.1.*
 * }</pre>
 *
 * @param <T> the type of values stored in the registry
 */
public class LazyTrieRegistry<T> {

    /** Primary index: ConcurrentHashMap for O(1) direct lookups */
    private final ConcurrentMap<Name, T> registry = new ConcurrentHashMap<>();

    /** Identity map fast path for InternedName instances (reference equality) */
    private final ConcurrentMap<Name, T> identityMap = new ConcurrentHashMap<>();

    /** Secondary index: Lazy trie for O(k) subtree queries */
    private volatile TrieNode root = null;

    /** Flag to track if trie has been built */
    private volatile boolean trieBuilt = false;

    /**
     * Trie node for hierarchical indexing.
     * Each node represents a path segment and points to child segments.
     */
    private static final class TrieNode {
        final Name name;  // Full name path to this node (null for root)
        final Map<String, TrieNode> children = new HashMap<>();

        TrieNode(Name name) {
            this.name = name;
        }
    }

    /**
     * Retrieves a value by exact Name match.
     *
     * <p>Optimization: Uses identity map (==) fast path for InternedName instances,
     * falling back to equality-based lookup for other Name implementations.
     *
     * @param name the name to lookup
     * @return the value associated with the name, or null if not found
     */
    public T get(Name name) {
        if (name instanceof InternedName) {
            // Fast path: identity lookup (reference equality) for interned names
            T value = identityMap.get(name);
            if (value != null) return value;
        }
        // Fallback: equality-based lookup
        return registry.get(name);
    }

    /**
     * Atomically retrieves an existing value or creates a new one if absent.
     *
     * <p>If a subtree query has been performed, the trie will be updated incrementally.
     *
     * @param name the name to lookup or create
     * @param factory supplier to create a new value if absent
     * @return the existing or newly created value
     */
    public T getOrCreate(Name name, Supplier<T> factory) {
        T value = registry.computeIfAbsent(name, n -> factory.get());
        if (trieBuilt) {
            insertIntoTrie(name);
        }
        return value;
    }

    /**
     * Inserts or updates a value in the registry.
     *
     * <p>If a subtree query has been performed, the trie will be updated incrementally.
     *
     * @param name the name key
     * @param value the value to store
     * @return the previous value associated with the name, or null if none
     */
    public T put(Name name, T value) {
        // Update both maps
        T previous = registry.put(name, value);
        if (name instanceof InternedName) {
            identityMap.put(name, value);
        }
        if (trieBuilt) {
            insertIntoTrie(name);
        }
        return previous;
    }

    /**
     * Removes a value from the registry.
     *
     * <p>Note: Does not remove from trie (trie is immutable after build for simplicity).
     * Removed entries simply won't appear in subtree query results.
     *
     * @param name the name to remove
     * @return the removed value, or null if not found
     */
    public T remove(Name name) {
        if (name instanceof InternedName) {
            identityMap.remove(name);
        }
        return registry.remove(name);
    }

    /**
     * Checks if the registry contains a value for the given name.
     *
     * @param name the name to check
     * @return true if the name exists in the registry
     */
    public boolean containsKey(Name name) {
        return registry.containsKey(name);
    }

    /**
     * Returns the number of entries in the registry.
     *
     * @return the size of the registry
     */
    public int size() {
        return registry.size();
    }

    /**
     * Retrieves all entries under a given prefix in the name hierarchy.
     *
     * <p>On the first call to this method, a complete trie index is built from
     * all current registry entries. Subsequent calls use the existing trie with
     * incremental updates for new entries.
     *
     * <p>Examples:
     * <ul>
     *   <li>prefix="kafka.broker.1" returns all entries under kafka.broker.1.*</li>
     *   <li>prefix="kafka" returns all entries under kafka.*</li>
     * </ul>
     *
     * @param prefix the name prefix to query
     * @return a map of all entries with names matching or under the prefix (never null)
     */
    public Map<Name, T> getSubtree(Name prefix) {
        ensureTrieBuilt();

        TrieNode node = findNode(prefix);
        if (node == null) {
            return Collections.emptyMap();
        }

        Map<Name, T> result = new LinkedHashMap<>();
        collectSubtree(node, result);
        return result;
    }

    /**
     * Ensures the trie is built, building it if necessary.
     * This method is thread-safe and ensures only one thread builds the trie.
     */
    private void ensureTrieBuilt() {
        if (!trieBuilt) {
            synchronized (this) {
                if (!trieBuilt) {
                    buildTrie();
                    trieBuilt = true;
                }
            }
        }
    }

    /**
     * Builds the complete trie from all current registry entries.
     * Called once on first subtree query.
     */
    private void buildTrie() {
        root = new TrieNode(null);
        for (Name name : registry.keySet()) {
            insertIntoTrieUnsafe(name);
        }
    }

    /**
     * Thread-safe insertion into trie (used for incremental updates).
     */
    private synchronized void insertIntoTrie(Name name) {
        if (root == null) {
            root = new TrieNode(null);
        }
        insertIntoTrieUnsafe(name);
    }

    /**
     * Inserts a name into the trie without synchronization.
     * Caller must ensure thread safety.
     *
     * <p>Optimization: If the name is an InternedName, uses parent chain traversal
     * to avoid string splitting. Otherwise falls back to string splitting.
     */
    private void insertIntoTrieUnsafe(Name name) {
        if (name instanceof InternedName) {
            insertInternedName((InternedName) name);
        } else {
            insertGenericName(name);
        }
    }

    /**
     * Optimized insertion for InternedName using parent chain (no string splitting).
     */
    private void insertInternedName(InternedName name) {
        // Build path by traversing parent chain
        List<InternedName> path = new ArrayList<>();
        InternedName current = name;
        while (current != null) {
            path.add(current);
            current = current.parent();
        }
        Collections.reverse(path);

        // Insert into trie
        TrieNode node = root;
        for (InternedName n : path) {
            String segment = n.segment();
            node = node.children.computeIfAbsent(segment, s -> new TrieNode(n));
        }
    }

    /**
     * Generic insertion using string splitting (fallback for non-InternedName).
     */
    private void insertGenericName(Name name) {
        String[] parts = name.toString().split("\\.");
        TrieNode node = root;
        for (String part : parts) {
            node = node.children.computeIfAbsent(part, p -> new TrieNode(name));
        }
    }

    /**
     * Finds the trie node corresponding to the given prefix.
     *
     * @param prefix the prefix to find
     * @return the trie node for the prefix, or null if not found
     */
    private TrieNode findNode(Name prefix) {
        if (root == null) return null;

        if (prefix instanceof InternedName) {
            return findInternedNode((InternedName) prefix);
        } else {
            return findGenericNode(prefix);
        }
    }

    /**
     * Optimized node finding for InternedName using parent chain.
     */
    private TrieNode findInternedNode(InternedName prefix) {
        // Build path by traversing parent chain
        List<String> segments = new ArrayList<>();
        InternedName current = prefix;
        while (current != null) {
            segments.add(current.segment());
            current = current.parent();
        }
        Collections.reverse(segments);

        // Navigate trie
        TrieNode node = root;
        for (String segment : segments) {
            node = node.children.get(segment);
            if (node == null) return null;
        }
        return node;
    }

    /**
     * Generic node finding using string splitting.
     */
    private TrieNode findGenericNode(Name prefix) {
        String[] parts = prefix.toString().split("\\.");
        TrieNode node = root;
        for (String part : parts) {
            node = node.children.get(part);
            if (node == null) return null;
        }
        return node;
    }

    /**
     * Recursively collects all entries in the subtree rooted at the given node.
     *
     * @param node the root node of the subtree
     * @param result the map to collect results into
     */
    private void collectSubtree(TrieNode node, Map<Name, T> result) {
        if (node.name != null) {
            T value = registry.get(node.name);
            if (value != null) {
                result.put(node.name, value);
            }
        }
        for (TrieNode child : node.children.values()) {
            collectSubtree(child, result);
        }
    }

    /**
     * Returns all entries in the registry.
     *
     * @return an unmodifiable map of all entries
     */
    public Map<Name, T> getAll() {
        return Collections.unmodifiableMap(registry);
    }

    /**
     * Removes all entries from the registry and resets the trie.
     */
    public void clear() {
        registry.clear();
        identityMap.clear();
        root = null;
        trieBuilt = false;
    }
}
