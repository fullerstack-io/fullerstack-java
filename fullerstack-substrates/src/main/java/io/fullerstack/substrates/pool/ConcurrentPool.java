package io.fullerstack.substrates.pool;

import io.humainary.substrates.api.Substrates.Name;
import io.humainary.substrates.api.Substrates.Pool;
import io.humainary.substrates.api.Substrates.Subject;
import io.humainary.substrates.api.Substrates.Substrate;

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
public class ConcurrentPool<T> implements Pool<T> {
  private final Map<Name, T> percepts = new ConcurrentHashMap<>();
  private final Function<? super Name, ? extends T> factory;

  /**
   * Creates a new Pool with the given factory.
   *
   * @param factory function to create percepts for given names
   */
  public ConcurrentPool(Function<? super Name, ? extends T> factory) {
    this.factory = Objects.requireNonNull(factory, "Pool factory cannot be null");
  }

  @Override
  public T get(Name name) {
    Objects.requireNonNull(name, "Name cannot be null");
    return percepts.computeIfAbsent(name, factory::apply);
  }

  @Override
  public T get(Substrate<?> substrate) {
    Objects.requireNonNull(substrate, "Substrate cannot be null");
    return get(substrate.subject().name());
  }

  @Override
  public T get(Subject<?> subject) {
    Objects.requireNonNull(subject, "Subject cannot be null");
    return get(subject.name());
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
