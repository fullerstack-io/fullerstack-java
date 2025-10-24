package io.fullerstack.substrates.subject;

import io.humainary.substrates.api.Substrates.Id;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.State;
import io.humainary.substrates.api.Substrates.Subject;
import io.humainary.substrates.api.Substrates.Substrate;

import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * Node-based Subject implementation - hierarchical parent-child structure.
 *
 * <p><b>Design Principles:</b>
 * <ul>
 *   <li>Subjects form hierarchical trees via parent references</li>
 *   <li>Circuit → Conduit → Channel hierarchy mirrors container relationships</li>
 *   <li>Each Subject has: Id (unique), Name (label), State (data), Type (class), Parent (optional)</li>
 *   <li>Subject.enclosure() returns parent Subject in hierarchy</li>
 *   <li>Subject.path() walks hierarchy via enclosure(), showing all ancestors</li>
 * </ul>
 *
 * <p><b>Comparison with HierarchicalName:</b>
 * <ul>
 *   <li>HierarchicalName: Hierarchical identifiers (strings)</li>
 *   <li>HierarchicalSubject: Hierarchical runtime entities (identity + state)</li>
 *   <li>Both use parent-child links for hierarchy</li>
 *   <li>Both implement Extent interface with enclosure()</li>
 * </ul>
 *
 * @param <S> The substrate type this subject represents
 * @see Subject
 * @see HierarchicalName
 * @see HierarchicalCell
 */
@Getter
@EqualsAndHashCode
@Builder(toBuilder = true)
public class HierarchicalSubject<S extends Substrate<S>> implements Subject<S>, Comparable<Subject<?>> {
    /**
     * Unique identifier for this subject.
     */
    private final Id id;

    /**
     * Hierarchical name (e.g., "circuit.conduit.channel").
     */
    private final Name name;

    /**
     * Associated state (may be null).
     */
    private final State state;

    /**
     * Subject type class (e.g., Channel.class, Circuit.class).
     */
    private final Class<S> type;

    /**
     * Parent subject in the hierarchy (nullable - root subjects have no parent).
     */
    private final Subject<?> parent;

    /**
     * Creates a Subject node with all fields (no parent - root node).
     */
    public HierarchicalSubject(@NonNull Id id, @NonNull Name name, State state, @NonNull Class<S> type) {
        this(id, name, state, type, null);
    }

    /**
     * Creates a Subject node with parent reference for hierarchy.
     */
    public HierarchicalSubject(@NonNull Id id, @NonNull Name name, State state, @NonNull Class<S> type, Subject<?> parent) {
        this.id = id;
        this.name = name;
        this.state = state;
        this.type = type;
        this.parent = parent;
    }

    // Override Subject interface methods
    @Override
    public Id id() {
        return id;
    }

    @Override
    public Name name() {
        return name;
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public Class<S> type() {
        return type;
    }

    // Override Extent.enclosure() to return parent Subject
    @Override
    public java.util.Optional<Subject<?>> enclosure() {
        return java.util.Optional.ofNullable(parent);
    }

    // NOTE: Do NOT override part() - use Subject's default implementation
    // which formats as "Subject[name=...,type=...,id=...]"

    // Subject.toString() is abstract - must implement
    @Override
    public String toString() {
        // Return hierarchical path using "/" separator (Extent default)
        return path().toString();
    }

    // Implement Comparable for Subject ordering
    @Override
    public int compareTo(Subject<?> other) {
        if (other == null) {
            return 1;
        }
        // Compare by name first
        int nameCompare = name().toString().compareTo(other.name().toString());
        if (nameCompare != 0) {
            return nameCompare;
        }
        // Then by ID
        return id().toString().compareTo(other.id().toString());
    }
}
