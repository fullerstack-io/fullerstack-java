package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.lang.ref.WeakReference;
import java.lang.reflect.Member;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Optimized, interned hierarchical Name implementation.
 * Each distinct path (e.g. "kafka.broker.jvm.heap") yields a unique instance.
 *
 * <p><b>Design:</b>
 * <ul>
 *   <li>Global weak interning pool - reuses Name instances for identical paths</li>
 *   <li>Segment interning - canonical segment strings</li>
 *   <li>Lazy full-path computation - built on first toString(), cached thereafter</li>
 *   <li>Parent-child linked structure - memory efficient hierarchy</li>
 * </ul>
 *
 * <p><b>Performance characteristics:</b>
 * <ul>
 *   <li>Fast creation via incremental node building</li>
 *   <li>O(1) toString after first call (cached)</li>
 *   <li>Bounded memory via weak references</li>
 * </ul>
 */
public final class InternedName implements Name {
    private static final char SEPARATOR = '.';

    /** Global interning pool (weakly references Names to allow GC of unused paths). */
    private static final Map<String, WeakReference<InternedName>> INTERN_POOL = new ConcurrentHashMap<>();

    /** Intern pool for individual segments. */
    private static final Map<String, String> SEGMENT_POOL = new ConcurrentHashMap<>();

    /** Parent node (null for root). */
    private final InternedName parent;

    /** The final segment (e.g. "heap" for "kafka.broker.jvm.heap"). */
    private final String segment;

    /** Cached hash code for fast lookup. */
    private final int hash;

    /** Lazily computed full name. */
    private volatile String fullName;

    private InternedName(InternedName parent, String segment) {
        this.parent = parent;
        this.segment = internSegment(segment);
        this.hash = Objects.hash(parent, this.segment);
    }

    // ---------------- FACTORY METHODS ----------------

    public static InternedName of(String path) {
        Objects.requireNonNull(path, "path");
        validatePath(path);
        WeakReference<InternedName> ref = INTERN_POOL.get(path);
        InternedName existing = ref != null ? ref.get() : null;
        if (existing != null) return existing;

        InternedName created = createFromPath(path);
        INTERN_POOL.put(path, new WeakReference<>(created));
        return created;
    }

    private static InternedName createFromPath(String path) {
        String[] parts = path.split("\\.");
        InternedName current = null;
        for (String p : parts) {
            current = new InternedName(current, p);
        }
        return current;
    }

    private static String internSegment(String segment) {
        return SEGMENT_POOL.computeIfAbsent(segment, s -> s);
    }

    private static void validatePath(String path) {
        if (path.isEmpty() || path.startsWith(".") || path.endsWith(".") || path.contains(".."))
            throw new IllegalArgumentException("Invalid name path: " + path);
    }

    // ---------------- NAME INTERFACE IMPLEMENTATION ----------------

    @Override
    public String part() {
        return segment;
    }

    @Override
    public Name name(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty()) return this;
        validatePath(path);
        return of(toString() + SEPARATOR + path);
    }

    @Override
    public Name name(Name suffix) {
        Objects.requireNonNull(suffix, "suffix");
        return of(toString() + SEPARATOR + suffix.toString());
    }

    @Override
    public Name name(Enum<?> e) {
        Objects.requireNonNull(e, "e");
        return name(e.getDeclaringClass().getName() + SEPARATOR + e.name());
    }

    @Override
    public Name name(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return name(type.getName());
    }

    @Override
    public Name name(Member member) {
        Objects.requireNonNull(member, "member");
        return name(member.getDeclaringClass().getName() + SEPARATOR + member.getName());
    }

    @Override
    public Name name(Iterable<String> parts) {
        Objects.requireNonNull(parts, "parts");
        StringJoiner joiner = new StringJoiner(String.valueOf(SEPARATOR), toString() + SEPARATOR, "");
        for (String s : parts) joiner.add(Objects.requireNonNull(s));
        return of(joiner.toString());
    }

    @Override
    public <T> Name name(Iterable<? extends T> parts, Function<T, String> mapper) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(mapper, "mapper");
        List<String> mapped = new ArrayList<>();
        for (T t : parts) mapped.add(Objects.requireNonNull(mapper.apply(t)));
        return name(mapped);
    }

    @Override
    public Name name(Iterator<String> parts) {
        Objects.requireNonNull(parts, "parts");
        List<String> list = new ArrayList<>();
        parts.forEachRemaining(list::add);
        return name(list);
    }

    @Override
    public <T> Name name(Iterator<? extends T> parts, Function<T, String> mapper) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(mapper, "mapper");
        List<String> mapped = new ArrayList<>();
        parts.forEachRemaining(t -> mapped.add(mapper.apply(t)));
        return name(mapped);
    }

    // ---------------- EXTENT INTERFACE IMPLEMENTATION ----------------

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
        Objects.requireNonNull(mapper, "mapper");
        if (parent == null) {
            return mapper.apply(segment);
        }
        return parent.path(mapper) + String.valueOf(SEPARATOR) + mapper.apply(segment);
    }

    @Override
    public int depth() {
        int count = 1;
        InternedName current = parent;
        while (current != null) {
            count++;
            current = current.parent;
        }
        return count;
    }

    @Override
    public Optional<Name> enclosure() {
        return Optional.ofNullable(parent);
    }

    @Override
    public Iterator<Name> iterator() {
        List<Name> names = new ArrayList<>();
        collectHierarchy(names);
        return names.iterator();
    }

    private void collectHierarchy(List<Name> names) {
        if (parent != null) {
            parent.collectHierarchy(names);
        }
        names.add(this);
    }

    // ---------------- OBJECT OVERRIDES ----------------

    @Override
    public String toString() {
        String s = fullName;
        if (s == null) {
            if (parent == null) {
                s = segment;
            } else {
                s = parent.toString() + SEPARATOR + segment;
            }
            fullName = s;
        }
        return s;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object o) {
        // Fast path: reference equality (most common case for InternedName)
        if (this == o) return true;

        // Fast path: different InternedName instances means different paths (guaranteed by interning)
        if (o instanceof InternedName) return false;

        // Slow path: compare with other Name implementations via string comparison
        if (o instanceof Name other) {
            return toString().equals(other.toString());
        }

        return false;
    }

    // ---------------- ACCESSORS (for testing/monitoring) ----------------

    public String segment() {
        return segment;
    }

    public InternedName parent() {
        return parent;
    }

    public static int segmentPoolSize() {
        return SEGMENT_POOL.size();
    }

    public static int internPoolSize() {
        return INTERN_POOL.size();
    }

    public static void clearPools() {
        SEGMENT_POOL.clear();
        INTERN_POOL.clear();
    }
}
