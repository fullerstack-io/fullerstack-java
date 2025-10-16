package io.fullerstack.substrates.registry;

import io.humainary.substrates.api.Substrates.Name;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Simple registry using only ConcurrentHashMap (no hierarchical indexing).
 *
 * <p><b>Design:</b>
 * <ul>
 *   <li>Single flat ConcurrentHashMap for storage</li>
 *   <li>No trie structure - subtree queries require full scan</li>
 *   <li>Minimal code complexity</li>
 * </ul>
 *
 * <p><b>Performance characteristics:</b>
 * <ul>
 *   <li>get(): O(1) - very fast direct lookup</li>
 *   <li>put(): O(1) - very fast insertion</li>
 *   <li>subtree(): O(n) - full scan of all entries (SLOW)</li>
 * </ul>
 *
 * <p><b>Use case:</b>
 * Best when subtree queries are rare or not needed. Ideal for simple
 * key-value lookups where hierarchical structure is not important.
 */
public final class FlatMapRegistry<T> {

    /** Simple flat map */
    private final ConcurrentMap<Name, T> map = new ConcurrentHashMap<>();

    // -------------------- API --------------------

    /**
     * Get a value by exact name.
     *
     * @param name the name to look up
     * @return the value, or null if not found
     */
    public T get(Name name) {
        Objects.requireNonNull(name, "name");
        return map.get(name);
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
        return map.computeIfAbsent(name, k -> factory.get());
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
        map.put(name, value);
    }

    /**
     * Remove a value.
     *
     * @param name the name to remove
     */
    public void remove(Name name) {
        Objects.requireNonNull(name, "name");
        map.remove(name);
    }

    /**
     * Return all entries under a given prefix.
     * WARNING: This requires scanning ALL entries (O(n)).
     *
     * @param prefix the prefix name
     * @return map of all names under prefix (including prefix itself)
     */
    public Map<Name, T> subtree(Name prefix) {
        Objects.requireNonNull(prefix, "prefix");

        String prefixStr = prefix.toString();
        Map<Name, T> result = new LinkedHashMap<>();

        // Full scan - expensive!
        for (Map.Entry<Name, T> entry : map.entrySet()) {
            String entryPath = entry.getKey().toString();
            if (entryPath.equals(prefixStr) || entryPath.startsWith(prefixStr + ".")) {
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    /**
     * Returns all entries in the registry.
     *
     * @return unmodifiable view of all entries
     */
    public Map<Name, T> entries() {
        return Collections.unmodifiableMap(map);
    }

    /**
     * Returns total registered items.
     *
     * @return size of registry
     */
    public int size() {
        return map.size();
    }

    /**
     * Checks if registry contains a name.
     *
     * @param name the name to check
     * @return true if present
     */
    public boolean contains(Name name) {
        Objects.requireNonNull(name, "name");
        return map.containsKey(name);
    }

    /**
     * Clears all entries.
     */
    public void clear() {
        map.clear();
    }
}
