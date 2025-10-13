package io.fullerstack.substrates.name;

import io.humainary.substrates.api.Substrates.Name;

import java.lang.reflect.Member;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;

/**
 * Implementation of Substrates.Name providing hierarchical naming.
 *
 * <p>Names are immutable and support hierarchical paths like "kafka.broker.1.jvm.heap".
 * Each Name has a part (current segment) and optional parent (enclosing segment).
 *
 * @see Name
 */
public class NameImpl implements Name {
    private final String part;
    private final Name parent;

    private NameImpl(String part, Name parent) {
        this.part = Objects.requireNonNull(part, "Name part cannot be null");
        this.parent = parent;
    }

    @Override
    public CharSequence part() {
        return part;
    }

    @Override
    public Name name(Name suffix) {
        // If suffix has no parent, create new Name with suffix's part and this as parent
        if (suffix instanceof NameImpl impl && impl.parent == null) {
            return new NameImpl(impl.part, this);
        }
        // Otherwise, suffix is already hierarchical - need to rebuild with this as base
        // This handles cases where suffix itself has structure
        Name current = this;
        for (Name part : suffix) {
            current = new NameImpl(((NameImpl)part).part, current);
        }
        return current;
    }

    @Override
    public Name name(String s) {
        return new NameImpl(s, this);
    }

    @Override
    public Name name(Enum<?> e) {
        return new NameImpl(e.name(), this);
    }

    @Override
    public Name name(Iterable<String> parts) {
        Name current = this;
        for (String part : parts) {
            current = new NameImpl(part, current);
        }
        return current;
    }

    @Override
    public <T> Name name(Iterable<? extends T> items, Function<T, String> mapper) {
        Name current = this;
        for (T item : items) {
            current = new NameImpl(mapper.apply(item), current);
        }
        return current;
    }

    @Override
    public Name name(Iterator<String> parts) {
        Name current = this;
        while (parts.hasNext()) {
            current = new NameImpl(parts.next(), current);
        }
        return current;
    }

    @Override
    public <T> Name name(Iterator<? extends T> items, Function<T, String> mapper) {
        Name current = this;
        while (items.hasNext()) {
            current = new NameImpl(mapper.apply(items.next()), current);
        }
        return current;
    }

    @Override
    public Name name(Class<?> clazz) {
        return new NameImpl(clazz.getSimpleName(), this);
    }

    @Override
    public Name name(Member member) {
        return new NameImpl(member.getName(), this);
    }

    @Override
    public CharSequence path() {
        return toPath();
    }

    @Override
    public CharSequence path(char separator) {
        return toPath().replace(SEPARATOR, separator);
    }

    @Override
    public CharSequence path(Function<? super String, ? extends CharSequence> mapper) {
        if (parent == null) {
            return mapper.apply(part);
        }
        return ((Name) parent).path(mapper) + String.valueOf(SEPARATOR) + mapper.apply(part);
    }

    @Override
    public String value() {
        return toPath();
    }

    // Factory methods for creating root names
    public static Name of(String part) {
        return new NameImpl(part, null);
    }

    public static Name of(String... parts) {
        if (parts.length == 0) {
            throw new IllegalArgumentException("At least one part required");
        }
        Name name = new NameImpl(parts[0], null);
        for (int i = 1; i < parts.length; i++) {
            name = new NameImpl(parts[i], name);
        }
        return name;
    }

    public static Name of(Iterable<String> parts) {
        Iterator<String> iter = parts.iterator();
        if (!iter.hasNext()) {
            throw new IllegalArgumentException("At least one part required");
        }
        Name name = new NameImpl(iter.next(), null);
        while (iter.hasNext()) {
            name = new NameImpl(iter.next(), name);
        }
        return name;
    }

    public static <T> Name of(Iterable<T> items, Function<T, String> mapper) {
        Iterator<T> iter = items.iterator();
        if (!iter.hasNext()) {
            throw new IllegalArgumentException("At least one item required");
        }
        Name name = new NameImpl(mapper.apply(iter.next()), null);
        while (iter.hasNext()) {
            name = new NameImpl(mapper.apply(iter.next()), name);
        }
        return name;
    }

    // Helper to get full path as string
    private String toPath() {
        if (parent == null) {
            return part;
        }
        return ((NameImpl) parent).toPath() + SEPARATOR + part;
    }

    @Override
    public String toString() {
        return toPath();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NameImpl other)) return false;
        return toPath().equals(other.toPath());
    }

    @Override
    public int hashCode() {
        return toPath().hashCode();
    }

    // ========== Extent Interface Implementations ==========

    /**
     * Returns an iterator over the hierarchical path from root to this name.
     * For "broker.1.heap", iterates: broker → broker.1 → broker.1.heap
     */
    @Override
    public java.util.Iterator<Name> iterator() {
        // Build list of all names from root to this
        java.util.List<Name> names = new java.util.ArrayList<>();
        collectHierarchy(names);
        return names.iterator();
    }

    /**
     * Returns the parent/enclosing name.
     * For "broker.1.heap", returns Optional.of("broker.1")
     */
    @Override
    public java.util.Optional<Name> enclosure() {
        return java.util.Optional.ofNullable(parent);
    }

    /**
     * Returns the depth of this name (number of parts).
     * For "broker.1.heap", returns 3
     */
    @Override
    public int depth() {
        int count = 1;
        Name current = parent;
        while (current != null) {
            count++;
            if (current instanceof NameImpl impl) {
                current = impl.parent;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Recursively collects all names from root to leaf into a list.
     */
    private void collectHierarchy(java.util.List<Name> names) {
        if (parent != null && parent instanceof NameImpl impl) {
            impl.collectHierarchy(names);
        }
        names.add(this);
    }
}
