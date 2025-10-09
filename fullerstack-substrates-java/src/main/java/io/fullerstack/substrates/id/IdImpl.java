package io.fullerstack.substrates.id;

import io.humainary.substrates.api.Substrates.Id;

import java.util.Objects;
import java.util.UUID;

/**
 * UUID-based implementation of Substrates.Id.
 *
 * <p>Provides unique identifiers for Substrates subjects.
 *
 * @see Id
 */
public class IdImpl implements Id {
    private final UUID uuid;

    private IdImpl(UUID uuid) {
        this.uuid = Objects.requireNonNull(uuid, "UUID cannot be null");
    }

    /**
     * Generates a new random ID.
     *
     * @return new unique ID
     */
    public static Id generate() {
        return new IdImpl(UUID.randomUUID());
    }

    /**
     * Creates an ID from existing UUID.
     *
     * @param uuid the UUID
     * @return ID wrapping the UUID
     */
    public static Id of(UUID uuid) {
        return new IdImpl(uuid);
    }

    /**
     * Gets the underlying UUID.
     *
     * @return the UUID
     */
    public UUID uuid() {
        return uuid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdImpl other)) return false;
        return uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }

    @Override
    public String toString() {
        return uuid.toString();
    }
}
