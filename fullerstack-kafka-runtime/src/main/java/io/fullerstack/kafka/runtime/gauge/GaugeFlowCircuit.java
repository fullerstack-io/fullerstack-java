package io.fullerstack.kafka.runtime.gauge;

import io.humainary.substrates.ext.serventis.Gauges;
import io.humainary.substrates.ext.serventis.Gauges.Gauge;
import io.humainary.substrates.api.Substrates.*;

import java.util.function.BiConsumer;

import static io.fullerstack.substrates.CortexRuntime.cortex;

/**
 * Circuit for gauge signal monitoring (RC5 Serventis API).
 * <p>
 * Provides infrastructure for gauge instrumentation using {@link Gauge} instruments
 * that emit increment/decrement/overflow/underflow/reset signals for bidirectional metric tracking.
 * <p>
 * <b>RC5 Instrument Pattern</b>:
 * <ul>
 *   <li>Uses {@link Gauge} instrument with method calls (increment(), decrement(), overflow(), etc.)</li>
 *   <li>Signals are {@link Gauges.Sign} enums representing gauge operations</li>
 *   <li>Subject context in Channel, not in signals</li>
 *   <li>NO manual Signal construction needed - instruments handle it</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * GaugeFlowCircuit circuit = new GaugeFlowCircuit();
 * Gauge connectionGauge = circuit.gaugeFor("db.connections.active");
 *
 * // Connection established
 * connectionGauge.increment();    // Active connections increased
 *
 * // Connection closed
 * connectionGauge.decrement();    // Active connections decreased
 *
 * // Capacity violations
 * if (connections >= maxConnections) {
 *     connectionGauge.overflow(); // Hit maximum capacity
 * }
 * if (connections < 0) {
 *     connectionGauge.underflow(); // Fell below minimum
 * }
 *
 * // Explicit reset
 * if (poolRecycled) {
 *     connectionGauge.reset();    // Reset to baseline
 * }
 *
 * circuit.close();
 * }</pre>
 *
 * <h3>Gauge Semantics:</h3>
 * <ul>
 *   <li><b>INCREMENT</b> - Gauge increased (connection opened, queue entry)</li>
 *   <li><b>DECREMENT</b> - Gauge decreased (connection closed, queue exit)</li>
 *   <li><b>OVERFLOW</b> - Exceeded maximum value (capacity saturation)</li>
 *   <li><b>UNDERFLOW</b> - Fell below minimum value (resource depletion)</li>
 *   <li><b>RESET</b> - Explicit return to baseline</li>
 * </ul>
 *
 * <h3>Saturation vs Wrapping:</h3>
 * <ul>
 *   <li><b>Wrapping</b> - Overflow causes value to wrap to opposite bound (numeric gauges)</li>
 *   <li><b>Saturation</b> - Value sticks at boundary (resource limits)</li>
 * </ul>
 * Observer agents detect the difference by monitoring signal patterns after boundary events.
 *
 * <h3>Gauges vs Counters:</h3>
 * <ul>
 *   <li><b>Gauges</b> - Bidirectional (connections, queue depth, utilization)</li>
 *   <li><b>Counters</b> - Monotonically increasing (requests, bytes, events)</li>
 * </ul>
 *
 * @see Gauges
 * @see Gauge
 */
public class GaugeFlowCircuit implements AutoCloseable {

  private final Cortex cortex;
  private final Circuit circuit;
  private final Conduit < Gauge, Gauges.Sign > conduit;

  /**
   * Creates a new gauge flow circuit.
   * <p>
   * Initializes circuit "gauge.flow" with a conduit using {@link Gauges#composer}.
   */
  public GaugeFlowCircuit () {
    // Get Cortex instance
    this.cortex = cortex ();

    // Create circuit
    this.circuit = cortex.circuit ( cortex.name ( "gauge.flow" ) );

    // Create conduit with Gauges composer (returns Gauge instruments)
    this.conduit = circuit.conduit (
      cortex.name ( "gauge-monitoring" ),
      Gauges::composer
    );
  }

  /**
   * Get Gauge instrument for specific gauge entity.
   *
   * @param entityName gauge identifier (e.g., "connections.active", "queue.depth")
   * @return Gauge instrument for emitting gauge signals via method calls
   */
  public Gauge gaugeFor ( String entityName ) {
    return conduit.get ( cortex.name ( entityName ) );
  }

  /**
   * Subscribe to gauge signals for observability.
   *
   * @param name       subscriber name
   * @param subscriber subscriber function receiving Subject and Registrar
   */
  public void subscribe ( String name, BiConsumer < Subject < Channel < Gauges.Sign > >, Registrar < Gauges.Sign > > subscriber ) {
    conduit.subscribe ( cortex.subscriber ( cortex.name ( name ), subscriber ) );
  }

  /**
   * Closes the circuit and releases resources.
   */
  @Override
  public void close () {
    circuit.close ();
  }
}
