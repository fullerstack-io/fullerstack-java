package io.fullerstack.kafka.runtime.counter;

import io.humainary.substrates.ext.serventis.ext.Counters;
import io.humainary.substrates.ext.serventis.ext.Counters.Counter;
import io.humainary.substrates.api.Substrates.*;

import java.util.function.BiConsumer;

import io.humainary.substrates.api.Substrates;
import static io.humainary.substrates.api.Substrates.*;

/**
 * Circuit for counter signal monitoring (RC5 Serventis API).
 * <p>
 * Provides infrastructure for counter instrumentation using {@link Counter} instruments
 * that emit increment/overflow/underflow/reset signals for monotonic counter tracking.
 * <p>
 * <b>RC5 Instrument Pattern</b>:
 * <ul>
 *   <li>Uses {@link Counter} instrument with method calls (increment(), overflow(), etc.)</li>
 *   <li>Signals are {@link Counters.Sign} enums representing counter operations</li>
 *   <li>Subject context in Channel, not in signals</li>
 *   <li>NO manual Signal construction needed - instruments handle it</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * CounterFlowCircuit circuit = new CounterFlowCircuit();
 * Counter requestCounter = circuit.counterFor("http.requests");
 *
 * // Normal operation - monotonic increase
 * requestCounter.increment();     // Request count increased
 *
 * // Boundary violation
 * if (counter == Long.MAX_VALUE) {
 *     requestCounter.overflow();  // Counter wrapped/reset due to overflow
 * }
 *
 * // Error condition
 * if (attemptedDecrement) {
 *     requestCounter.underflow(); // Invalid decrement operation
 * }
 *
 * // Explicit reset
 * if (dailyRollover) {
 *     requestCounter.reset();     // Counter explicitly reset to zero
 * }
 *
 * circuit.close();
 * }</pre>
 *
 * <h3>Counter Semantics:</h3>
 * <ul>
 *   <li><b>INCREMENT</b> - Normal counter accumulation (monotonic increase)</li>
 *   <li><b>OVERFLOW</b> - Counter exceeded maximum and wrapped/reset</li>
 *   <li><b>UNDERFLOW</b> - Invalid decrement attempted (counters don't decrease)</li>
 *   <li><b>RESET</b> - Explicit zeroing by operator/agent</li>
 * </ul>
 *
 * <h3>Counters vs Gauges:</h3>
 * <ul>
 *   <li><b>Counters</b> - Monotonically increasing (requests, bytes, events)</li>
 *   <li><b>Gauges</b> - Bidirectional (connections, queue depth, utilization)</li>
 * </ul>
 *
 * @see Counters
 * @see Counter
 */
public class CounterFlowCircuit implements AutoCloseable {

  
  private final Circuit circuit;
  private final Conduit < Counter, Counters.Sign > conduit;

  /**
   * Creates a new counter flow circuit.
   * <p>
   * Initializes circuit "counter.flow" with a conduit using {@link Counters#composer}.
   */
  public CounterFlowCircuit () {
    // Create circuit using static Cortex methods
    this.circuit = Substrates.cortex().circuit ( Substrates.cortex().name ( "counter.flow" ) );

    // Create conduit with Counters composer (returns Counter instruments)
    this.conduit = circuit.conduit (
      Substrates.cortex().name ( "counter-monitoring" ),
      Counters::composer
    );
  }

  /**
   * Get Counter instrument for specific counter entity.
   *
   * @param entityName counter identifier (e.g., "http.requests", "bytes.sent")
   * @return Counter instrument for emitting counter signals via method calls
   */
  public Counter counterFor ( String entityName ) {
    return conduit.get ( Substrates.cortex().name ( entityName ) );
  }

  /**
   * Subscribe to counter signals for observability.
   *
   * @param name       subscriber name
   * @param subscriber subscriber function receiving Subject and Registrar
   */
  public void subscribe ( String name, BiConsumer < Subject < Channel < Counters.Sign > >, Registrar < Counters.Sign > > subscriber ) {
    conduit.subscribe ( Substrates.cortex().subscriber ( Substrates.cortex().name ( name ), subscriber ) );
  }

  /**
   * Closes the circuit and releases resources.
   */
  @Override
  public void close () {
    circuit.close ();
  }
}
