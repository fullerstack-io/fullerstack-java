package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.lang.reflect.Member;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Node-based Name implementation - hierarchical parent-child structure.
 *
 * <p><b>Design Principles:</b>
 * <ul>
 *   <li>Cortex creates root names only: new NameNode(null, "root")</li>
 *   <li>Hierarchy built by name() methods: parent.name("child")</li>
 *   <li>Parent-child links via constructor (node structure)</li>
 *   <li>All Extent methods use defaults (path, depth, iterator)</li>
 * </ul>
 *
 * <p><b>Required implementations:</b>
 * <ul>
 *   <li>part() - returns this segment</li>
 *   <li>enclosure() - returns parent</li>
 *   <li>name() methods - create children with parent reference</li>
 * </ul>
 */
public final class NameNode implements Name {

    private final NameNode parent;
    private final String segment;

    /**
     * Package-private constructor.
     * Used internally by name() methods to create children.
     */
    NameNode(NameNode parent, String segment) {
        this.parent = parent;
        this.segment = Objects.requireNonNull(segment, "segment cannot be null");
    }

    /**
     * Public factory for creating names from dot-separated paths.
     * Splits on '.' to create hierarchical names.
     *
     * @param path the dot-separated path (e.g., "kafka.broker.1")
     * @return a hierarchical Name
     */
    public static Name of(String path) {
        Objects.requireNonNull(path, "path cannot be null");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path cannot be empty");
        }

        // Split on dots to create hierarchy
        String[] segments = path.split("\\.");
        // Create root with constructor, then use name() for children
        Name current = new NameNode(null, segments[0]);
        for (int i = 1; i < segments.length; i++) {
            current = current.name(segments[i]);
        }
        return current;
    }

    // ============ REQUIRED: Extent implementations ============

    @Override
    public CharSequence part() {
        return segment;
    }

    @Override
    public Optional<Name> enclosure() {
        return Optional.ofNullable(parent);
    }

    // ============ REQUIRED: Name interface - name() factory methods ============
    // Note: Name API doesn't provide defaults, must implement all

    @Override
    public Name name(Name suffix) {
        Objects.requireNonNull(suffix, "suffix");
        // Delegate to name(String) for each part - don't force NameNode creation
        Name current = this;
        for (Name part : suffix) {
            current = current.name(part.part().toString());
        }
        return current;
    }

    @Override
    public Name name(String segment) {
        Objects.requireNonNull(segment, "segment");
        if (segment.isEmpty()) return this;
        return new NameNode(this, segment);
    }

    @Override
    public Name name(Enum<?> e) {
        Objects.requireNonNull(e, "e");
        // Delegate to name(String) - don't force NameNode
        return name(e.getDeclaringClass().getName()).name(e.name());
    }

    @Override
    public Name name(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return name(type.getName());
    }

    @Override
    public Name name(Member member) {
        Objects.requireNonNull(member, "member");
        // Delegate to name(String) - don't force NameNode
        return name(member.getDeclaringClass().getName()).name(member.getName());
    }

    @Override
    public Name name(Iterable<String> parts) {
        Objects.requireNonNull(parts, "parts");
        // Delegate to name(String) - don't force NameNode
        Name current = this;
        for (String part : parts) {
            current = current.name(Objects.requireNonNull(part));
        }
        return current;
    }

    @Override
    public <T> Name name(Iterable<? extends T> parts, Function<T, String> mapper) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(mapper, "mapper");
        // Delegate to name(String) - don't force NameNode
        Name current = this;
        for (T item : parts) {
            current = current.name(Objects.requireNonNull(mapper.apply(item)));
        }
        return current;
    }

    @Override
    public Name name(Iterator<String> parts) {
        Objects.requireNonNull(parts, "parts");
        // Delegate to name(String) - don't force NameNode
        Name current = this;
        while (parts.hasNext()) {
            current = current.name(Objects.requireNonNull(parts.next()));
        }
        return current;
    }

    @Override
    public <T> Name name(Iterator<? extends T> parts, Function<T, String> mapper) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(mapper, "mapper");
        // Delegate to name(String) - don't force NameNode
        Name current = this;
        while (parts.hasNext()) {
            current = current.name(Objects.requireNonNull(mapper.apply(parts.next())));
        }
        return current;
    }

    // ============ ALL OTHER METHODS USE EXTENT DEFAULTS ============
    // - path() - builds from parts using Extent's foldTo()
    // - depth() - counts via fold
    // - iterator() - walks via enclosure()
    // - compareTo() - compares paths
    // - extent(), extremity(), fold(), foldTo() - all defaults

    // Override path(char) to always use "." separator for hierarchical names
    @Override
    public CharSequence path(char separator) {
        // Always use "." for hierarchical names, ignore requested separator
        return Name.super.path('.');
    }

    // Required: path(Function) is abstract in Name
    // Use "." as separator for hierarchical names (not "/" like filesystem paths)
    @Override
    public CharSequence path(Function<? super String, ? extends CharSequence> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        // Adapt Function<String, CharSequence> to Function<Name, CharSequence>
        // by extracting part() from each Name, then delegate to Extent's default
        // Use "." separator for dot-notation hierarchical names
        return path(name -> mapper.apply(name.part().toString()), '.');
    }

    @Override
    public String value() {
        // Return full path (uses '.' separator)
        return path().toString();
    }

    @Override
    public String toString() {
        // Use path() which uses '.' separator for hierarchical names
        return path().toString();
    }

    @Override
    public int hashCode() {
        // Hash based on full path string
        return path().toString().hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Name other)) return false;
        return path().toString().equals(other.path().toString());
    }
}
