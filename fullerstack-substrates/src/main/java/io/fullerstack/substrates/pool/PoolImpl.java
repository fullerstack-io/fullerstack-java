package io.fullerstack.substrates.pool;

import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pool;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Implementation of Substrates.Pool for percept instance management.
 *
 * <p>Pools cache percept instances by name, creating them on-demand using a factory function.
 * Instances are cached and reused for the same name.
 *
 * @param <T> the percept type
 * @see Pool
 */
public class PoolImpl<T> implements Pool<T> {
    private final Map<Name, T> percepts = new ConcurrentHashMap<>();
    private final Function<Name, T> factory;

    /**
     * Creates a new Pool with the given factory.
     *
     * @param factory function to create percepts for given names
     */
    public PoolImpl(Function<Name, T> factory) {
        this.factory = Objects.requireNonNull(factory, "Pool factory cannot be null");
    }

    @Override
    public T get(Name name) {
        Objects.requireNonNull(name, "Name cannot be null");
        return percepts.computeIfAbsent(name, factory);
    }

    /**
     * Gets the number of cached percepts.
     *
     * @return percept count
     */
    public int size() {
        return percepts.size();
    }

    /**
     * Clears all cached percepts.
     */
    public void clear() {
        percepts.clear();
    }

    @Override
    public String toString() {
        return "Pool[size=" + percepts.size() + "]";
    }
}
