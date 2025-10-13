package io.fullerstack.substrates.functional;

import io.humainary.substrates.api.Substrates.*;
import lombok.experimental.UtilityClass;

import java.util.function.Consumer;
import java.util.function.Predicate;
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

    // ========== Filtering and Transformation ==========

    /**
     * Creates a Sequencer that applies a predicate filter.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * Conduit<Pipe<Integer>, Integer> conduit = circuit.conduit(
     *     cortex.name("positive-numbers"),
     *     Channel::pipe,
     *     Composers.filter(n -> n > 0)
     * );
     * }</pre>
     *
     * @param predicate the filter predicate
     * @param <E> the emission type
     * @return a sequencer that applies the filter
     */
    public static <E> Sequencer<Segment<E>> filter(Predicate<E> predicate) {
        return segment -> segment.guard(predicate);
    }

    /**
     * Creates a Sequencer that limits emissions.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * Conduit<Pipe<Signal>, Signal> conduit = circuit.conduit(
     *     cortex.name("first-10"),
     *     Channel::pipe,
     *     Composers.limit(10)
     * );
     * }</pre>
     *
     * @param maxEmissions the maximum number of emissions
     * @param <E> the emission type
     * @return a sequencer that limits emissions
     */
    public static <E> Sequencer<Segment<E>> limit(long maxEmissions) {
        return segment -> segment.limit(maxEmissions);
    }

    /**
     * Creates a Sequencer that only emits changed values.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * Conduit<Pipe<String>, String> conduit = circuit.conduit(
     *     cortex.name("changes-only"),
     *     Channel::pipe,
     *     Composers.diff()
     * );
     * }</pre>
     *
     * @param <E> the emission type
     * @return a sequencer that filters duplicate consecutive values
     */
    public static <E> Sequencer<Segment<E>> diff() {
        return Segment::diff;
    }

    /**
     * Creates a Sequencer that samples every Nth emission.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * // Only emit every 10th value
     * Conduit<Pipe<Metric>, Metric> conduit = circuit.conduit(
     *     cortex.name("sampled"),
     *     Channel::pipe,
     *     Composers.sample(10)
     * );
     * }</pre>
     *
     * @param n the sampling rate (emit every Nth value)
     * @param <E> the emission type
     * @return a sequencer that samples emissions
     */
    public static <E> Sequencer<Segment<E>> sample(int n) {
        return segment -> segment.sample(n);
    }

    /**
     * Composes multiple Sequencers into one.
     *
     * <p>Transformations are applied in order.
     *
     * <h3>Example:</h3>
     * <pre>{@code
     * Sequencer<Segment<Integer>> pipeline = Composers.compose(
     *     Composers.filter(n -> n > 0),
     *     Composers.sample(10),
     *     Composers.limit(100)
     * );
     * }</pre>
     *
     * @param sequencers the sequencers to compose
     * @param <E> the emission type
     * @return a composed sequencer
     */
    @SafeVarargs
    public static <E> Sequencer<Segment<E>> compose(Sequencer<Segment<E>>... sequencers) {
        return segment -> {
            for (Sequencer<Segment<E>> sequencer : sequencers) {
                sequencer.apply(segment);
            }
        };
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
