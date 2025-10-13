package io.fullerstack.substrates.subject;

import io.humainary.substrates.api.Substrates.Id;
import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.State;
import io.humainary.substrates.api.Substrates.Subject;

import lombok.*;
import lombok.experimental.FieldDefaults;

/**
 * Implementation of Substrates.Subject using Lombok for boilerplate reduction.
 *
 * <p>Subjects represent identifiable entities in the Substrates system,
 * combining an ID, name, state, and type.
 *
 * <p>This class uses Lombok annotations to auto-generate:
 * <ul>
 *   <li>Public constructor via {@code @AllArgsConstructor}</li>
 *   <li>Null checks via {@code @NonNull}</li>
 *   <li>Getter methods via {@code @Getter}</li>
 *   <li>equals() and hashCode() via {@code @EqualsAndHashCode}</li>
 *   <li>toString() via {@code @ToString}</li>
 *   <li>Builder pattern via {@code @Builder}</li>
 * </ul>
 *
 * @see Subject
 */
@Getter
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class SubjectImpl implements Subject {
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
     * Subject type (CIRCUIT, CONDUIT, CHANNEL, etc.).
     */
    private final Type type;

    /**
     * Creates a Subject with all fields.
     */
    public SubjectImpl(@NonNull Id id, @NonNull Name name, State state, @NonNull Type type) {
        this.id = id;
        this.name = name;
        this.state = state;
        this.type = type;
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
    public Type type() {
        return type;
    }

    // Delegate Name interface methods to the name field

    @Override
    public CharSequence part() {
        return name.part();
    }

    @Override
    public CharSequence path() {
        return name.path();
    }

    @Override
    public CharSequence path(char separator) {
        return name.path(separator);
    }
}
