package io.fullerstack.substrates.functional;

import io.humainary.substrates.api.Substrates.*;
import lombok.experimental.UtilityClass;

import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Functional composition utilities for Substrates components.
 *
 * <p>Provides functional helpers for composing Substrates entities created via the Cortex API.
 * These utilities work WITH the API, not as replacements.
 *
 * <h3>Correct Usage Pattern:</h3>
 * <pre>{@code
 * // 1. Create Cortex and components via API
 * Cortex cortex = new CortexRuntime();
 * Circuit circuit = cortex.circuit(cortex.name("broker-health"));
 *
 * // 2. Use Composers for functional patterns
 * circuit.source(MonitorSignal.class)
 *     .subscribe((subject, registrar) ->
 *         Composers.hierarchicalPipes(circuit, subject, Channel::pipe)
 *             .forEach(registrar::register)
 *     );
 * }</pre>
 *
 * @see Streams
 * @see Functions
 */
@UtilityClass
public class Composers {

    // ========== Hierarchical Routing ==========

    /**
     * Creates a stream of Pipes for hierarchical routing.
     *
     * <p>For a subject with name "broker.1.heap", this creates pipes for:
     * <ul>
     *   <li>broker</li>
     *   <li>broker.1</li>
     *   <li>broker.1.heap</li>
     * </ul>
     *
     * <p>This implements the hierarchical subscription pattern from the Humainary blog.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * circuit.source(MonitorSignal.class)
     *     .subscribe((subject, registrar) ->
     *         Composers.hierarchicalPipes(circuit, subject, Channel::pipe)
     *             .forEach(registrar::register)
     *     );
     * }</pre>
     *
     * @param circuit the circuit containing conduits (created via cortex.circuit())
     * @param subject the subject to route
     * @param composer the composer for creating pipes
     * @param <E> the emission type
     * @return stream of pipes for each hierarchical level
     */
    public static <E> Stream<Pipe<E>> hierarchicalPipes(
        Circuit circuit,
        Subject subject,
        Composer<Pipe<E>, E> composer
    ) {
        return Streams.hierarchical(subject.name())
            .map(prefix -> circuit.conduit(prefix, composer))
            .map(conduit -> conduit.get(subject.name()));
    }

    /**
     * Creates a stream of Conduits for hierarchical levels.
     *
     * <p>For a name "broker.1.heap", this returns conduits for:
     * <ul>
     *   <li>broker</li>
     *   <li>broker.1</li>
     *   <li>broker.1.heap</li>
     * </ul>
     *
     * @param circuit the circuit (created via cortex.circuit())
     * @param name the hierarchical name
     * @param composer the composer for creating percepts
     * @param <P> the percept type
     * @param <E> the emission type
     * @return stream of conduits at each level
     */
    public static <P, E> Stream<Conduit<P, E>> hierarchicalConduits(
        Circuit circuit,
        Name name,
        Composer<? extends P, E> composer
    ) {
        return Streams.hierarchical(name)
            .map(prefix -> circuit.conduit(prefix, composer));
    }

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
