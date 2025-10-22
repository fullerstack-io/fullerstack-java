package io.fullerstack.substrates.functional;

import io.humainary.substrates.api.Substrates.*;
import lombok.experimental.UtilityClass;

import java.util.function.Consumer;

/**
 * Functional composition utilities for Substrates components.
 *
 * <p>Provides functional helpers for composing Substrates entities created via the Cortex API.
 * These utilities work WITH the API, not as replacements.
 *
 * <h3>Usage Pattern:</h3>
 * <pre>{@code
 * Cortex cortex = new CortexRuntime();
 * Circuit circuit = cortex.circuit(cortex.name("broker-health"));
 *
 * // Use Circuit's built-in hierarchical routing via Cell/Container pattern
 * Cell<Signal, Event> cell = circuit.cell(Composer.pipe(), flowConfig);
 * }</pre>
 *
 * <p><b>Note:</b> Hierarchical routing is built into the Cell/Container pattern.
 * Use Cell.get() to create child cells that automatically propagate emissions up the hierarchy.
 *
 * @see Functions
 */
@UtilityClass
public class Composers {

    // ========== Circuit Configuration Helpers ==========

    /**
     * Configures a Circuit with a consumer and returns it for chaining.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * Circuit circuit = Composers.configure(
     *     cortex.circuit(cortex.name("metrics")),
     *     c -> c.clock().start()
     * );
     * }</pre>
     *
     * @param circuit the circuit to configure
     * @param configurator the configuration function
     * @return the configured circuit
     */
    public static Circuit configure(Circuit circuit, Consumer<Circuit> configurator) {
        configurator.accept(circuit);
        return circuit;
    }

    /**
     * Taps into a Circuit for side effects without breaking the chain.
     *
     * <p>Alias for circuit.tap() to support functional style.
     *
     * @param circuit the circuit
     * @param consumer the side effect
     * @return the same circuit for chaining
     */
    public static Circuit tap(Circuit circuit, Consumer<Circuit> consumer) {
        return circuit.tap(consumer);
    }
}
