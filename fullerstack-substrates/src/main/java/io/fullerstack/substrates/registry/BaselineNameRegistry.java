package io.fullerstack.substrates.registry;

import io.fullerstack.substrates.name.InternedName;
import io.humainary.substrates.api.Substrates.Name;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Baseline hierarchical registry using string splitting approach.
 *
 * <p><b>Design:</b>
 * <ul>
 *   <li>Flat ConcurrentHashMap for O(1) direct lookups</li>
 *   <li>Trie structure for hierarchical subtree queries</li>
 *   <li>String splitting to extract path segments</li>
 * </ul>
 *
 * <p><b>Performance characteristics:</b>
 * <ul>
 *   <li>get(): O(1) direct lookup</li>
 *   <li>put(): O(d) where d = depth (string splitting + trie insertion)</li>
 *   <li>subtree(): O(n) where n = subtree size</li>
 * </ul>
 */
public final class BaselineNameRegistry<T> {

    /** O(1) direct lookups */
    private final ConcurrentMap<Name, T> flat = new ConcurrentHashMap<>();

    /** Hierarchical index for subtree traversal */
    private final TrieNode root = new TrieNode(null);

    /** Represents a single node in the registry trie */
    private final class TrieNode {
        final Name name; // may be null for root
        final ConcurrentMap<String, TrieNode> children = new ConcurrentHashMap<>();
        volatile T value;

        TrieNode(Name name) {
            this.name = name;
        }
    }

    // -------------------- API --------------------

    /**
     * Get a value by exact name.
     *
     * @param name the name to look up
     * @return the value, or null if not found
     */
    public T get(Name name) {
        Objects.requireNonNull(name, "name");
        return flat.get(name);
    }

    /**
     * Atomically get or create a value by name.
     *
     * @param name the name
     * @param factory supplier to create value if absent
     * @return the existing or newly created value
     */
    public T getOrCreate(Name name, Supplier<T> factory) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(factory, "factory");

        // Ensure presence in flat map
        T existing = flat.get(name);
        if (existing != null) return existing;

        T created = factory.get();
        T prev = flat.putIfAbsent(name, created);
        if (prev != null) return prev;

        // Insert into trie index
        insertIntoTrie(name, created);
        return created;
    }

    /**
     * Insert or replace a value for the given name.
     *
     * @param name the name
     * @param value the value
     */
    public void put(Name name, T value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");

        flat.put(name, value);
        insertIntoTrie(name, value);
    }

    /**
     * Remove a value and clean up the trie if possible.
     *
     * @param name the name to remove
     */
    public void remove(Name name) {
        Objects.requireNonNull(name, "name");

        flat.remove(name);
        removeFromTrie(name);
    }

    /**
     * Return all entries directly or under a given prefix.
     *
     * @param prefix the prefix name
     * @return map of all names under prefix (including prefix itself)
     */
    public Map<Name, T> subtree(Name prefix) {
        Objects.requireNonNull(prefix, "prefix");
        TrieNode node = findNode(prefix);
        if (node == null) return Collections.emptyMap();

        Map<Name, T> result = new LinkedHashMap<>();
        collectSubtree(node, result);
        return result;
    }

    /**
     * Returns all entries in the registry.
     *
     * @return unmodifiable view of all entries
     */
    public Map<Name, T> entries() {
        return Collections.unmodifiableMap(flat);
    }

    /**
     * Returns total registered items.
     *
     * @return size of registry
     */
    public int size() {
        return flat.size();
    }

    /**
     * Checks if registry contains a name.
     *
     * @param name the name to check
     * @return true if present
     */
    public boolean contains(Name name) {
        Objects.requireNonNull(name, "name");
        return flat.containsKey(name);
    }

    /**
     * Clears all entries.
     */
    public void clear() {
        flat.clear();
        root.children.clear();
        root.value = null;
    }

    // -------------------- Internal Trie Ops --------------------

    private void insertIntoTrie(Name name, T value) {
        String[] parts = name.toString().split("\\.");
        TrieNode node = root;
        for (String part : parts) {
            node = node.children.computeIfAbsent(part, p ->
                new TrieNode(InternedName.of(joinUntil(parts, p)))
            );
        }
        node.value = value;
    }

    private void removeFromTrie(Name name) {
        String[] parts = name.toString().split("\\.");
        removeRecursive(root, parts, 0);
    }

    private boolean removeRecursive(TrieNode current, String[] parts, int index) {
        if (index == parts.length) {
            current.value = null;
            return current.children.isEmpty();
        }
        TrieNode child = current.children.get(parts[index]);
        if (child == null) return false;

        boolean shouldRemove = removeRecursive(child, parts, index + 1);
        if (shouldRemove) current.children.remove(parts[index]);
        return current.children.isEmpty() && current.value == null;
    }

    private TrieNode findNode(Name prefix) {
        String[] parts = prefix.toString().split("\\.");
        TrieNode node = root;
        for (String part : parts) {
            node = node.children.get(part);
            if (node == null) return null;
        }
        return node;
    }

    private void collectSubtree(TrieNode node, Map<Name, T> output) {
        if (node.value != null && node.name != null) {
            output.put(node.name, node.value);
        }
        for (TrieNode child : node.children.values()) {
            collectSubtree(child, output);
        }
    }

    private static String joinUntil(String[] parts, String end) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append('.');
            sb.append(part);
            if (part.equals(end)) break;
        }
        return sb.toString();
    }
}
