package io.fullerstack.substrates.bootstrap;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for sharing objects between SPI providers during bootstrap.
 * <p>
 * Implements the Service Registry pattern to allow CircuitStructureProvider
 * and SensorProvider to share references to cells, conduits, and other components.
 * <p>
 * <b>Pattern: Publish-Find-Bind</b>
 * <ol>
 *   <li><b>Publish</b>: CircuitStructureProvider creates components and registers them</li>
 *   <li><b>Find</b>: SensorProvider queries registry for components</li>
 *   <li><b>Bind</b>: SensorProvider uses retrieved components to emit signals</li>
 * </ol>
 * <p>
 * <b>Example Usage:</b>
 * <pre>{@code
 * // CircuitStructureProvider: Publish
 * Cell<BrokerMetrics, MonitorSignal> brokerCell = circuit.cell(...);
 * context.register("brokers-cell", brokerCell);
 *
 * // SensorProvider: Find and Bind
 * Cell<BrokerMetrics, MonitorSignal> brokerCell =
 *     context.get("brokers-cell", Cell.class)
 *            .orElseThrow();
 *
 * BrokerSensor sensor = new BrokerSensor(brokerCell);
 * }</pre>
 * <p>
 * <b>Thread Safety:</b> All operations are thread-safe using ConcurrentHashMap.
 *
 * @see CircuitStructureProvider
 * @see io.fullerstack.substrates.spi.SensorProvider
 */
public class BootstrapContext {

  private final String circuitName;
  private final Map<String, Object> registry;

  /**
   * Creates a new BootstrapContext for a circuit.
   *
   * @param circuitName the circuit name this context is for
   */
  public BootstrapContext(String circuitName) {
    this.circuitName = Objects.requireNonNull(circuitName, "circuitName cannot be null");
    this.registry = new ConcurrentHashMap<>();
  }

  /**
   * Register a component in the registry.
   * <p>
   * Used by CircuitStructureProvider to publish components for later retrieval.
   *
   * @param name component name (e.g., "brokers-cell", "metrics-conduit")
   * @param component the component to register
   * @throws NullPointerException if name or component is null
   * @throws IllegalStateException if name is already registered
   */
  public void register(String name, Object component) {
    Objects.requireNonNull(name, "name cannot be null");
    Objects.requireNonNull(component, "component cannot be null");

    Object existing = registry.putIfAbsent(name, component);
    if (existing != null) {
      throw new IllegalStateException(
        "Component '" + name + "' already registered for circuit '" + circuitName + "'"
      );
    }
  }

  /**
   * Retrieve a component from the registry.
   * <p>
   * Used by SensorProvider to find components published by CircuitStructureProvider.
   *
   * @param name component name
   * @param type expected component type
   * @param <T> the component type
   * @return Optional containing the component if found and type matches, empty otherwise
   * @throws NullPointerException if name or type is null
   * @throws ClassCastException if component exists but type doesn't match
   */
  public <T> Optional<T> get(String name, Class<T> type) {
    Objects.requireNonNull(name, "name cannot be null");
    Objects.requireNonNull(type, "type cannot be null");

    Object component = registry.get(name);
    if (component == null) {
      return Optional.empty();
    }

    return Optional.of(type.cast(component));
  }

  /**
   * Retrieve a required component from the registry.
   * <p>
   * Convenience method that throws if component is not found.
   *
   * @param name component name
   * @param type expected component type
   * @param <T> the component type
   * @return the component
   * @throws NullPointerException if name or type is null
   * @throws IllegalStateException if component not found
   * @throws ClassCastException if component exists but type doesn't match
   */
  public <T> T getRequired(String name, Class<T> type) {
    return get(name, type).orElseThrow(() ->
      new IllegalStateException(
        "Required component '" + name + "' not found in circuit '" + circuitName + "'"
      )
    );
  }

  /**
   * Check if a component is registered.
   *
   * @param name component name
   * @return true if registered, false otherwise
   */
  public boolean contains(String name) {
    return registry.containsKey(name);
  }

  /**
   * Get the circuit name this context is for.
   *
   * @return circuit name
   */
  public String getCircuitName() {
    return circuitName;
  }

  /**
   * Get all registered component names.
   * <p>
   * Useful for debugging and logging.
   *
   * @return set of registered component names
   */
  public java.util.Set<String> getRegisteredNames() {
    return java.util.Collections.unmodifiableSet(registry.keySet());
  }
}
