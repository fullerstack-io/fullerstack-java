package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Array-backed, segment-interned, weakly-interned Name implementation.
 *
 * <p><b>Design Principles:</b>
 * <ul>
 *   <li>SEGMENT_POOL: unique canonical segment strings</li>
 *   <li>INTERN_POOL: weak references to full names (keyed by joined path)</li>
 *   <li>Each node stores: segment, parent, depth, cached segments array (weak)</li>
 * </ul>
 *
 * <p><b>Focus:</b>
 * <ul>
 *   <li>Low allocation for lookups</li>
 *   <li>Fast prefix/startsWith comparisons using String[] arrays</li>
 *   <li>Bounded memory retention via WeakReference pooling</li>
 * </ul>
 *
 * <p><b>Trade-offs vs NameImpl:</b>
 * <ul>
 *   <li>✅ Faster prefix checks (array comparison vs string startsWith)</li>
 *   <li>✅ Lower memory for repeated segments (interned)</li>
 *   <li>✅ Bounded memory (weak references allow GC)</li>
 *   <li>❌ More complexity (WeakReference management)</li>
 *   <li>❌ Slower toString() (builds on demand)</li>
 *   <li>❌ Potential GC churn from weak references</li>
 * </ul>
 *
 * @see NameImpl
 */
public final class SegmentArrayName implements Name {
    public static final char SEPARATOR = '.';

    // Intern pools
    private static final ConcurrentHashMap<String, String> SEGMENT_POOL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, WeakReference<SegmentArrayName>> INTERN_POOL = new ConcurrentHashMap<>();

    // Node fields
    private final String segment; // interned segment string
    private final SegmentArrayName parent; // parent node (null for root)
    private final int depth; // number of segments (root = 1)
    private final int hash; // cached hash of path
    private volatile WeakReference<String[]> segmentsRef; // cached segments array (weak)

    private SegmentArrayName(String segment, SegmentArrayName parent, int depth, int hash) {
        this.segment = segment;
        this.parent = parent;
        this.depth = depth;
        this.hash = hash;
    }

    // ----- Public factory -----

    /**
     * Creates a Name from a dot-separated path.
     *
     * @param path dot-separated path (e.g., "kafka.broker.1")
     * @return interned Name instance
     */
    public static SegmentArrayName of(String path) {
        Objects.requireNonNull(path, "path");
        validatePath(path);

        // Quick path: try to get existing strong ref
        WeakReference<SegmentArrayName> ref = INTERN_POOL.get(path);
        SegmentArrayName existing = ref != null ? ref.get() : null;
        if (existing != null) return existing;

        // Build incrementally from left to right to reuse parent nodes
        int start = 0;
        SegmentArrayName current = null;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == SEPARATOR) {
                String seg = path.substring(start, i);
                seg = SEGMENT_POOL.computeIfAbsent(seg, String::intern);
                current = internAppendSegment(current, seg);
                start = i + 1;
            }
        }
        // `current` now points to full path node
        return current;
    }

    private static SegmentArrayName internAppendSegment(SegmentArrayName parent, String seg) {
        String key;
        if (parent == null) {
            key = seg;
        } else {
            // Only used as map key; acceptable cost here because infrequent
            key = parent.toString() + SEPARATOR + seg;
        }

        // Try fast-path
        WeakReference<SegmentArrayName> existingRef = INTERN_POOL.get(key);
        SegmentArrayName node = existingRef != null ? existingRef.get() : null;
        if (node != null) return node;

        // Create node
        int depth = parent == null ? 1 : parent.depth + 1;
        int h = (parent == null ? 31 : parent.hash) * 31 + seg.hashCode();
        SegmentArrayName created = new SegmentArrayName(seg, parent, depth, h);

        // Put weak reference (racing puts are fine; first wins semantically)
        WeakReference<SegmentArrayName> prev = INTERN_POOL.putIfAbsent(key, new WeakReference<>(created));
        if (prev != null) {
            SegmentArrayName other = prev.get();
            if (other != null) return other;
            // prev was cleared: replace
            INTERN_POOL.put(key, new WeakReference<>(created));
        }
        return created;
    }

    // ----- Validation -----

    private static void validatePath(String path) {
        if (path.isEmpty()
            || path.charAt(0) == SEPARATOR
            || path.charAt(path.length() - 1) == SEPARATOR
            || path.contains("..")) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
    }

    // ----- Name methods (core) -----

    @Override
    public String part() {
        return segment;
    }

    @Override
    public Name name(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty()) return this;
        validatePath(path);

        // Fast composition: if path has no dots, append single segment
        if (path.indexOf(SEPARATOR) == -1) {
            String seg = SEGMENT_POOL.computeIfAbsent(path, String::intern);
            return internAppendSegment(this, seg);
        }

        // Otherwise build full appended path
        StringBuilder sb = new StringBuilder(this.toString().length() + 1 + path.length());
        sb.append(this.toString()).append(SEPARATOR).append(path);
        return of(sb.toString());
    }

    @Override
    public Name name(Name suffix) {
        Objects.requireNonNull(suffix, "suffix");

        // If suffix is same implementation, we can build by merging arrays without string join
        if (suffix instanceof SegmentArrayName sfx) {
            String[] a = this.segments();
            String[] b = sfx.segments();
            StringBuilder keyBuilder = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) keyBuilder.append(SEPARATOR);
                keyBuilder.append(a[i]);
            }
            for (String part : b) {
                keyBuilder.append(SEPARATOR).append(part);
            }
            return of(keyBuilder.toString());
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
        StringBuilder sb = new StringBuilder(this.toString());
        for (String p : parts) {
            sb.append(SEPARATOR).append(Objects.requireNonNull(p));
        }
        return of(sb.toString());
    }

    @Override
    public <T> Name name(Iterable<? extends T> parts, Function<T, String> mapper) {
        Objects.requireNonNull(parts);
        Objects.requireNonNull(mapper);
        StringBuilder sb = new StringBuilder(this.toString());
        for (T t : parts) {
            sb.append(SEPARATOR).append(Objects.requireNonNull(mapper.apply(t)));
        }
        return of(sb.toString());
    }

    @Override
    public Name name(Iterator<String> parts) {
        Objects.requireNonNull(parts);
        StringBuilder sb = new StringBuilder(this.toString());
        parts.forEachRemaining(s -> sb.append(SEPARATOR).append(Objects.requireNonNull(s)));
        return of(sb.toString());
    }

    @Override
    public <T> Name name(Iterator<? extends T> parts, Function<T, String> mapper) {
        Objects.requireNonNull(parts);
        Objects.requireNonNull(mapper);
        StringBuilder sb = new StringBuilder(this.toString());
        parts.forEachRemaining(t -> sb.append(SEPARATOR).append(Objects.requireNonNull(mapper.apply(t))));
        return of(sb.toString());
    }

    // ----- Extent methods -----

    @Override
    public String value() {
        return toString();
    }

    @Override
    public String path() {
        return toString();
    }

    @Override
    public CharSequence path(char separator) {
        if (separator == SEPARATOR) {
            return toString();
        }
        return toString().replace(SEPARATOR, separator);
    }

    @Override
    public CharSequence path(Function<? super String, ? extends CharSequence> mapper) {
        StringBuilder sb = new StringBuilder();
        String[] segs = segments();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append(SEPARATOR);
            sb.append(mapper.apply(segs[i]));
        }
        return sb.toString();
    }

    @Override
    public int depth() {
        return depth;
    }

    @Override
    public Optional<Name> enclosure() {
        return Optional.ofNullable(parent);
    }

    public Optional<Name> enclosure(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("Level must be >= 0");
        }
        if (level >= depth) {
            return Optional.empty();
        }

        SegmentArrayName node = this;
        for (int i = 0; i < level; i++) {
            node = node.parent;
        }
        return Optional.of(node);
    }

    // ----- Array-backed operations -----

    /**
     * Returns a (canonicalized) array of segments for this name.
     * The array is created once and cached in a WeakReference.
     * <p>
     * <b>IMPORTANT:</b> Callers MUST NOT modify the returned array.
     *
     * @return array of segments (cached, do not modify)
     */
    public String[] segments() {
        WeakReference<String[]> ref = segmentsRef;
        String[] cached = ref != null ? ref.get() : null;
        if (cached != null) return cached;

        // Compute and cache
        String[] arr = new String[depth];
        SegmentArrayName node = this;
        for (int i = depth - 1; i >= 0; i--) {
            arr[i] = node.segment;
            node = node.parent;
        }
        // CAS-style set to avoid races (we don't mind duplicates briefly)
        segmentsRef = new WeakReference<>(arr);
        return arr;
    }

    /**
     * Fast prefix check using arrays. This avoids string concatenation.
     *
     * @param prefix prefix to check
     * @return true if this name starts with prefix
     */
    public boolean startsWith(SegmentArrayName prefix) {
        Objects.requireNonNull(prefix);
        if (prefix.depth > this.depth) return false;

        String[] a = this.segments();
        String[] b = prefix.segments();
        for (int i = 0; i < b.length; i++) {
            if (!a[i].equals(b[i])) return false;
        }
        return true;
    }

    // ----- Object overrides -----

    @Override
    public String toString() {
        // Build on demand. We avoid caching full string to keep memory low;
        // if you need frequent string calls, cache the full string too.
        StringBuilder sb = new StringBuilder();
        String[] s = segments();
        for (int i = 0; i < s.length; i++) {
            if (i > 0) sb.append(SEPARATOR);
            sb.append(s[i]);
        }
        return sb.toString();
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SegmentArrayName other)) return false;
        if (this.hash != other.hash) return false;
        if (this.depth != other.depth) return false;

        // Compare arrays
        String[] a = this.segments();
        String[] b = other.segments();
        return Arrays.equals(a, b);
    }

    // ----- Additional accessors for tests -----

    /**
     * Returns the parent Name, or null if this is a root name.
     *
     * @return parent name or null
     */
    public SegmentArrayName parent() {
        return parent;
    }

    /**
     * Returns the segment string for this node.
     *
     * @return segment string
     */
    public String segment() {
        return segment;
    }

    // ----- Pool introspection (for testing/monitoring) -----

    /**
     * Returns the size of the segment pool (for testing).
     *
     * @return number of interned segments
     */
    public static int segmentPoolSize() {
        return SEGMENT_POOL.size();
    }

    /**
     * Returns the size of the intern pool (for testing).
     * Note: This includes cleared weak references.
     *
     * @return number of entries in intern pool
     */
    public static int internPoolSize() {
        return INTERN_POOL.size();
    }

    /**
     * Clears the intern pools (for testing only).
     * <p>
     * <b>WARNING:</b> This will break singleton semantics for existing instances.
     */
    public static void clearPools() {
        SEGMENT_POOL.clear();
        INTERN_POOL.clear();
    }
}
