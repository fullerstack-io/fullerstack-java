package io.fullerstack.substrates.subject;

import io.humainary.substrates.api.Substrates.Id;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.State;
import io.humainary.substrates.api.Substrates.Subject;

import java.util.Objects;

/**
 * Implementation of Substrates.Subject.
 *
 * <p>Subjects represent identifiable entities in the Substrates system,
 * combining an ID, name, state, and type.
 *
 * @see Subject
 */
public class SubjectImpl implements Subject {
    private final Id id;
    private final Name name;
    private final State state;
    private final Type type;

    /**
     * Creates a new Subject.
     *
     * @param id unique identifier
     * @param name hierarchical name
     * @param state associated state (may be null)
     * @param type subject type
     */
    public SubjectImpl(Id id, Name name, State state, Type type) {
        this.id = Objects.requireNonNull(id, "Subject ID cannot be null");
        this.name = Objects.requireNonNull(name, "Subject name cannot be null");
        this.state = state;
        this.type = Objects.requireNonNull(type, "Subject type cannot be null");
    }

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
    public Type type() {
        return type;
    }

    @Override
    public CharSequence part() {
        return name.part();
    }

    @Override
    public String toString() {
        return String.format("Subject[type=%s, name=%s, id=%s]", type, name, id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubjectImpl other)) return false;
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
