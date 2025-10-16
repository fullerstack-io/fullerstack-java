package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.lang.reflect.Member;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Optimized Name implementation combining best features of NameImpl and ArrayBackedInternedName.
 *
 * <p><b>Design Goals:</b>
 * <ul>
 *   <li>Fast deep hierarchy creation (parse once, build segments array)</li>
 *   <li>Fast toString() via cached full path string</li>
 *   <li>Fast hierarchical composition via direct array manipulation</li>
 *   <li>Bounded memory via LRU eviction (not weak references)</li>
 *   <li>Segment interning for memory efficiency</li>
 * </ul>
 *
 * <p><b>Key Optimizations:</b>
 * <ul>
 *   <li>Cached full path string (built once, O(1) toString)</li>
 *   <li>Cached hashCode (computed once from path)</li>
 *   <li>Lazy segments array (only created when needed for array operations)</li>
 *   <li>Segment interning (shared canonical strings)</li>
 *   <li>Strong references with bounded cache (no GC churn from WeakReference)</li>
 *   <li>Optimized array concatenation (no string building for composition)</li>
 * </ul>
 *
 * <p><b>Trade-offs:</b>
 * <ul>
 *   <li>Slightly more memory per instance (cached path + lazy segments)</li>
 *   <li>But bounded total memory via LRU cache</li>
 *   <li>No GC overhead from WeakReference checking</li>
 * </ul>
 */
public final class LRUCachedName implements Name {
    public static final char SEPARATOR = '.';
    private static final int MAX_CACHE_SIZE = 10000; // Bounded cache

    // Intern pools
    private static final ConcurrentHashMap<String, String> SEGMENT_POOL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LRUCachedName> NAME_CACHE = new ConcurrentHashMap<>();

    // LRU tracking (simple approach: remove oldest 10% when full)
    private static final LinkedHashMap<String, Long> ACCESS_ORDER = new LinkedHashMap<>(16, 0.75f, true);

    // Instance fields
    private final String cachedPath;      // Full path string (cached, O(1) toString)
    private final int cachedHashCode;     // Cached hashCode
    private volatile String[] segments;   // Lazy segments array (only created when needed)
    private final int depth;              // Number of segments

    private LRUCachedName(String path, String[] segments, int depth) {
        this.cachedPath = Objects.requireNonNull(path);
        this.cachedHashCode = path.hashCode();
        this.segments = segments; // May be null (lazy)
        this.depth = depth;
    }

    // ----- Public factory -----

    /**
     * Creates a Name from a dot-separated path.
     * Fast path: O(n) parse, creates segments array and cached path.
     *
     * @param path dot-separated path (e.g., "kafka.broker.1")
     * @return cached Name instance
     */
    public static LRUCachedName of(String path) {
        Objects.requireNonNull(path, "path");
        validatePath(path);

        // Check cache first
        LRUCachedName cached = NAME_CACHE.get(path);
        if (cached != null) {
            updateAccessTime(path);
            return cached;
        }

        // Parse segments and intern them
        String[] parts = path.split("\\.");
        String[] internedSegments = new String[parts.length];
        for (int i = 0; i < parts.length; i++) {
            internedSegments[i] = SEGMENT_POOL.computeIfAbsent(parts[i], String::intern);
        }

        // Create with cached path and segments
        LRUCachedName created = new LRUCachedName(path, internedSegments, parts.length);

        // Add to cache with eviction if needed
        if (NAME_CACHE.size() >= MAX_CACHE_SIZE) {
            evictOldest();
        }
        NAME_CACHE.put(path, created);
        updateAccessTime(path);

        return created;
    }

    private static void validatePath(String path) {
        if (path.isEmpty()
            || path.charAt(0) == SEPARATOR
            || path.charAt(path.length() - 1) == SEPARATOR
            || path.contains("..")) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
    }

    private static void updateAccessTime(String path) {
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.put(path, System.nanoTime());
        }
    }

    private static void evictOldest() {
        synchronized (ACCESS_ORDER) {
            int toRemove = MAX_CACHE_SIZE / 10; // Remove oldest 10%
            Iterator<String> it = ACCESS_ORDER.keySet().iterator();
            while (toRemove-- > 0 && it.hasNext()) {
                String path = it.next();
                NAME_CACHE.remove(path);
                it.remove();
            }
        }
    }

    // ----- Name methods (core) -----

    @Override
    public String part() {
        // Last segment
        ensureSegments();
        return segments[depth - 1];
    }

    @Override
    public Name name(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty()) return this;
        validatePath(path);

        // Fast path: single segment append
        if (path.indexOf(SEPARATOR) == -1) {
            String newPath = cachedPath + SEPARATOR + path;
            return of(newPath);
        }

        // Multi-segment append
        String newPath = cachedPath + SEPARATOR + path;
        return of(newPath);
    }

    @Override
    public Name name(Name suffix) {
        Objects.requireNonNull(suffix, "suffix");

        // Optimized: if both are LRUCachedName, use array concatenation
        if (suffix instanceof LRUCachedName other) {
            ensureSegments();
            other.ensureSegments();

            // Build new path efficiently
            StringBuilder sb = new StringBuilder(cachedPath.length() + 1 + other.cachedPath.length());
            sb.append(cachedPath).append(SEPARATOR).append(other.cachedPath);

            // Build concatenated segments array
            String[] newSegments = new String[depth + other.depth];
            System.arraycopy(segments, 0, newSegments, 0, depth);
            System.arraycopy(other.segments, 0, newSegments, depth, other.depth);

            String newPath = sb.toString();

            // Check cache
            LRUCachedName cached = NAME_CACHE.get(newPath);
            if (cached != null) {
                updateAccessTime(newPath);
                return cached;
            }

            // Create with pre-built segments
            LRUCachedName created = new LRUCachedName(newPath, newSegments, depth + other.depth);

            if (NAME_CACHE.size() >= MAX_CACHE_SIZE) {
                evictOldest();
            }
            NAME_CACHE.put(newPath, created);
            updateAccessTime(newPath);

            return created;
        } else {
            // Fallback
            return name(suffix.toString());
        }
    }

    @Override
    public Name name(Enum<?> path) {
        Objects.requireNonNull(path, "path");
        return name(path.getDeclaringClass().getName() + SEPARATOR + path.name());
    }

    @Override
    public Name name(Class<?> type) {
        Objects.requireNonNull(type);
        return name(type.getName());
    }

    @Override
    public Name name(Member member) {
        Objects.requireNonNull(member);
        return name(member.getDeclaringClass().getName() + SEPARATOR + member.getName());
    }

    @Override
    public Name name(Iterable<String> parts) {
        Objects.requireNonNull(parts);
        StringBuilder sb = new StringBuilder(cachedPath);
        for (String p : parts) {
            sb.append(SEPARATOR).append(Objects.requireNonNull(p));
        }
        return of(sb.toString());
    }

    @Override
    public <T> Name name(Iterable<? extends T> parts, Function<T, String> mapper) {
        Objects.requireNonNull(parts);
        Objects.requireNonNull(mapper);
        StringBuilder sb = new StringBuilder(cachedPath);
        for (T t : parts) {
            sb.append(SEPARATOR).append(Objects.requireNonNull(mapper.apply(t)));
        }
        return of(sb.toString());
    }

    @Override
    public Name name(Iterator<String> parts) {
        Objects.requireNonNull(parts);
        StringBuilder sb = new StringBuilder(cachedPath);
        parts.forEachRemaining(s -> sb.append(SEPARATOR).append(Objects.requireNonNull(s)));
        return of(sb.toString());
    }

    @Override
    public <T> Name name(Iterator<? extends T> parts, Function<T, String> mapper) {
        Objects.requireNonNull(parts);
        Objects.requireNonNull(mapper);
        StringBuilder sb = new StringBuilder(cachedPath);
        parts.forEachRemaining(t -> sb.append(SEPARATOR).append(Objects.requireNonNull(mapper.apply(t))));
        return of(sb.toString());
    }

    // ----- Extent methods -----

    @Override
    public String value() {
        return cachedPath;
    }

    @Override
    public String path() {
        return cachedPath;
    }

    @Override
    public CharSequence path(char separator) {
        if (separator == SEPARATOR) {
            return cachedPath;
        }
        return cachedPath.replace(SEPARATOR, separator);
    }

    @Override
    public CharSequence path(Function<? super String, ? extends CharSequence> mapper) {
        ensureSegments();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) sb.append(SEPARATOR);
            sb.append(mapper.apply(segments[i]));
        }
        return sb.toString();
    }

    @Override
    public int depth() {
        return depth;
    }

    @Override
    public Optional<Name> enclosure() {
        if (depth == 1) {
            return Optional.empty();
        }

        // Find last separator
        int lastSep = cachedPath.lastIndexOf(SEPARATOR);
        if (lastSep == -1) {
            return Optional.empty();
        }

        return Optional.of(of(cachedPath.substring(0, lastSep)));
    }

    // ----- Array operations -----

    /**
     * Returns segments array, creating it lazily if needed.
     * Uses double-checked locking for thread safety.
     */
    private void ensureSegments() {
        if (segments == null) {
            synchronized (this) {
                if (segments == null) {
                    segments = cachedPath.split("\\.");
                    // Intern all segments
                    for (int i = 0; i < segments.length; i++) {
                        segments[i] = SEGMENT_POOL.computeIfAbsent(segments[i], String::intern);
                    }
                }
            }
        }
    }

    /**
     * Returns segments array (creates if needed).
     * Callers should not modify the returned array.
     */
    public String[] segments() {
        ensureSegments();
        return segments;
    }

    /**
     * Fast prefix check using cached paths.
     * Falls back to array comparison if needed.
     */
    public boolean startsWith(LRUCachedName prefix) {
        Objects.requireNonNull(prefix);

        // Fast path: string prefix check
        if (cachedPath.startsWith(prefix.cachedPath)) {
            // Verify it's a clean segment boundary
            if (cachedPath.length() == prefix.cachedPath.length()) {
                return true; // Exact match
            }
            return cachedPath.charAt(prefix.cachedPath.length()) == SEPARATOR;
        }

        return false;
    }

    // ----- Object overrides -----

    @Override
    public String toString() {
        return cachedPath; // O(1) - cached!
    }

    @Override
    public int hashCode() {
        return cachedHashCode; // O(1) - cached!
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LRUCachedName other)) return false;

        // Fast path: compare cached hashCode
        if (this.cachedHashCode != other.cachedHashCode) return false;

        // Both have same hashCode, check path
        return this.cachedPath.equals(other.cachedPath);
    }

    // ----- Pool introspection (for testing/monitoring) -----

    public static int segmentPoolSize() {
        return SEGMENT_POOL.size();
    }

    public static int cacheSize() {
        return NAME_CACHE.size();
    }

    public static void clearCaches() {
        NAME_CACHE.clear();
        SEGMENT_POOL.clear();
        synchronized (ACCESS_ORDER) {
            ACCESS_ORDER.clear();
        }
    }
}
