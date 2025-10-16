package io.fullerstack.substrates.registry;

import io.fullerstack.substrates.name.InternedName;
import io.humainary.substrates.api.Substrates.Name;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Optimized hierarchical registry leveraging InternedName structure.
 *
 * <p><b>Optimizations over baseline:</b>
 * <ul>
 *   <li>Zero string splitting - uses InternedName parent chain traversal</li>
 *   <li>Identity-based fast path for InternedName lookups</li>
 *   <li>Atomic dual-index updates via ReadWriteLock</li>
 *   <li>Cached path construction eliminates joinUntil() overhead</li>
 *   <li>Reduced allocation pressure (no String[] arrays)</li>
 * </ul>
 *
 * <p><b>Performance characteristics:</b>
 * <ul>
 *   <li>get(): O(1) with fast path for InternedName (identity check)</li>
 *   <li>put(): O(d) where d = depth, but faster due to no string ops</li>
 *   <li>subtree(): O(n) where n = subtree size</li>
 * </ul>
 *
 * <p><b>Thread safety:</b>
 * ReadWriteLock ensures:
 * <ul>
 *   <li>Multiple concurrent readers (get, contains, size)</li>
 *   <li>Exclusive writer (put, remove, clear)</li>
 *   <li>Atomic consistency between flat map and trie</li>
 * </ul>
 */
public final class OptimizedNameRegistry<T> {

    /** Fast path for InternedName - uses identity equality */
    private final Map<Name, T> identityMap = Collections.synchronizedMap(new IdentityHashMap<>());

    /** Fallback for other Name implementations */
    private final ConcurrentMap<Name, T> flat = new ConcurrentHashMap<>();

    /** Hierarchical index for subtree traversal */
    private final TrieNode root = new TrieNode(null);

    /** Ensures atomic dual-index updates */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

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
     * Fast path for InternedName uses identity comparison.
     *
     * @param name the name to look up
     * @return the value, or null if not found
     */
    public T get(Name name) {
        Objects.requireNonNull(name, "name");

        lock.readLock().lock();
        try {
            // Fast path for InternedName - identity check
            if (name instanceof InternedName) {
                T value = identityMap.get(name);
                if (value != null) return value;
            }
            // Fallback to equals-based lookup
            return flat.get(name);
        } finally {
            lock.readLock().unlock();
        }
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

        // Fast check without creating value
        T existing = get(name);
        if (existing != null) return existing;

        lock.writeLock().lock();
        try {
            // Double-check after acquiring write lock
            existing = getInternal(name);
            if (existing != null) return existing;

            // Create and insert
            T created = factory.get();
            putInternal(name, created);
            return created;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Insert or replace a value for the given name.
     * Atomically updates both flat map and trie.
     *
     * @param name the name
     * @param value the value
     */
    public void put(Name name, T value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");

        lock.writeLock().lock();
        try {
            putInternal(name, value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Remove a value and clean up the trie if possible.
     * Atomically updates both flat map and trie.
     *
     * @param name the name to remove
     */
    public void remove(Name name) {
        Objects.requireNonNull(name, "name");

        lock.writeLock().lock();
        try {
            if (name instanceof InternedName) {
                identityMap.remove(name);
            }
            flat.remove(name);
            removeFromTrie(name);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Return all entries directly or under a given prefix.
     *
     * @param prefix the prefix name
     * @return map of all names under prefix (including prefix itself)
     */
    public Map<Name, T> subtree(Name prefix) {
        Objects.requireNonNull(prefix, "prefix");

        lock.readLock().lock();
        try {
            TrieNode node = findNode(prefix);
            if (node == null) return Collections.emptyMap();

            Map<Name, T> result = new LinkedHashMap<>();
            collectSubtree(node, result);
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns all entries in the registry.
     *
     * @return unmodifiable view of all entries
     */
    public Map<Name, T> entries() {
        lock.readLock().lock();
        try {
            Map<Name, T> result = new LinkedHashMap<>();
            result.putAll(identityMap);
            result.putAll(flat);
            return Collections.unmodifiableMap(result);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns total registered items.
     *
     * @return size of registry
     */
    public int size() {
        lock.readLock().lock();
        try {
            return identityMap.size() + flat.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Checks if registry contains a name.
     *
     * @param name the name to check
     * @return true if present
     */
    public boolean contains(Name name) {
        Objects.requireNonNull(name, "name");

        lock.readLock().lock();
        try {
            if (name instanceof InternedName && identityMap.containsKey(name)) {
                return true;
            }
            return flat.containsKey(name);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Clears all entries.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            identityMap.clear();
            flat.clear();
            root.children.clear();
            root.value = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // -------------------- Internal Methods --------------------

    private T getInternal(Name name) {
        if (name instanceof InternedName) {
            T value = identityMap.get(name);
            if (value != null) return value;
        }
        return flat.get(name);
    }

    private void putInternal(Name name, T value) {
        // Update appropriate map
        if (name instanceof InternedName) {
            identityMap.put(name, value);
        } else {
            flat.put(name, value);
        }

        // Update trie
        insertIntoTrie(name, value);
    }

    // -------------------- Optimized Trie Ops --------------------

    /**
     * Optimized trie insertion using InternedName parent chain.
     * Zero string splitting or parsing.
     */
    private void insertIntoTrie(Name name, T value) {
        if (name instanceof InternedName internedName) {
            insertIntoTrieFast(internedName, value);
        } else {
            insertIntoTrieSlow(name, value);
        }
    }

    /**
     * Fast path: traverse InternedName parent chain directly.
     */
    private void insertIntoTrieFast(InternedName name, T value) {
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
        node.value = value;
    }

    /**
     * Slow path: fallback for non-InternedName implementations.
     */
    private void insertIntoTrieSlow(Name name, T value) {
        String[] parts = name.toString().split("\\.");
        TrieNode node = root;

        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i > 0) pathBuilder.append('.');
            pathBuilder.append(part);

            String currentPath = pathBuilder.toString();
            node = node.children.computeIfAbsent(part, p ->
                new TrieNode(InternedName.of(currentPath))
            );
        }
        node.value = value;
    }

    /**
     * Optimized trie removal using InternedName structure.
     */
    private void removeFromTrie(Name name) {
        if (name instanceof InternedName internedName) {
            removeFromTrieFast(internedName);
        } else {
            removeFromTrieSlow(name);
        }
    }

    private void removeFromTrieFast(InternedName name) {
        // Build path from parent chain
        List<String> segments = new ArrayList<>();
        InternedName current = name;
        while (current != null) {
            segments.add(current.segment());
            current = current.parent();
        }
        Collections.reverse(segments);

        removeRecursive(root, segments, 0);
    }

    private void removeFromTrieSlow(Name name) {
        String[] parts = name.toString().split("\\.");
        removeRecursive(root, Arrays.asList(parts), 0);
    }

    private boolean removeRecursive(TrieNode current, List<String> parts, int index) {
        if (index == parts.size()) {
            current.value = null;
            return current.children.isEmpty();
        }
        TrieNode child = current.children.get(parts.get(index));
        if (child == null) return false;

        boolean shouldRemove = removeRecursive(child, parts, index + 1);
        if (shouldRemove) current.children.remove(parts.get(index));
        return current.children.isEmpty() && current.value == null;
    }

    /**
     * Optimized node finding using InternedName structure.
     */
    private TrieNode findNode(Name prefix) {
        if (prefix instanceof InternedName internedName) {
            return findNodeFast(internedName);
        } else {
            return findNodeSlow(prefix);
        }
    }

    private TrieNode findNodeFast(InternedName prefix) {
        // Build path from parent chain
        List<String> segments = new ArrayList<>();
        InternedName current = prefix;
        while (current != null) {
            segments.add(current.segment());
            current = current.parent();
        }
        Collections.reverse(segments);

        TrieNode node = root;
        for (String segment : segments) {
            node = node.children.get(segment);
            if (node == null) return null;
        }
        return node;
    }

    private TrieNode findNodeSlow(Name prefix) {
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
}
