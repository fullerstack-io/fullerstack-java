package io.fullerstack.substrates.builders;

import io.fullerstack.substrates.conduit.ConduitImpl;
import io.humainary.substrates.api.Substrates.*;
import lombok.Builder;
import lombok.NonNull;

import java.util.function.Function;

/**
 * Functional builder for creating Conduit instances.
 *
 * <p>Provides a fluent API for constructing Conduits with customizable behavior
 * through function injection and optional transformation pipelines.
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * // Simple conduit creation
 * Conduit<Pipe<MonitorSignal>, MonitorSignal> conduit =
 *     ConduitBuilder.<Pipe<MonitorSignal>, MonitorSignal>builder()
 *         .name(Name.name("broker"))
 *         .composer(channel -> channel.pipe())
 *         .queue(circuitQueue)
 *         .build();
 *
 * // Conduit with transformation pipeline
 * Conduit<Pipe<MonitorSignal>, MonitorSignal> conduit =
 *     ConduitBuilder.<Pipe<MonitorSignal>, MonitorSignal>builder()
 *         .name(Name.name("broker"))
 *         .composer(channel -> channel.pipe())
 *         .queue(circuitQueue)
 *         .sequencer(segment -> segment.filter(MonitorSignal::requiresAttention))
 *         .build();
 *
 * // Conduit with custom composer
 * Conduit<Pipe<MonitorSignal>, MonitorSignal> conduit =
 *     ConduitBuilder.<Pipe<MonitorSignal>, MonitorSignal>builder()
 *         .name(Name.name("broker"))
 *         .composer(channel -> {
 *             Pipe<MonitorSignal> pipe = channel.pipe();
 *             // Custom pipe wrapping or transformation
 *             return pipe;
 *         })
 *         .queue(circuitQueue)
 *         .build();
 * }</pre>
 *
 * @param <P> the percept type (e.g., Pipe<E>)
 * @param <E> the emission type (e.g., MonitorSignal)
 */
public class ConduitBuilder<P, E> {

    private final Name name;
    private final Composer<? extends P, E> composer;
    private final Queue queue;
    private final Sequencer<Segment<E>> sequencer;

    @lombok.Builder(builderMethodName = "builder", toBuilder = true)
    private ConduitBuilder(
        Name name,
        Composer<? extends P, E> composer,
        Queue queue,
        Sequencer<Segment<E>> sequencer
    ) {
        this.name = java.util.Objects.requireNonNull(name, "name cannot be null");
        this.composer = java.util.Objects.requireNonNull(composer, "composer cannot be null");
        this.queue = java.util.Objects.requireNonNull(queue, "queue cannot be null");
        this.sequencer = sequencer;
    }

    /**
     * Builds a Conduit instance with the configured parameters.
     *
     * @return a new Conduit instance
     */
    public Conduit<P, E> build() {
        if (sequencer != null) {
            return new ConduitImpl<>(name, composer, queue, sequencer);
        } else {
            return new ConduitImpl<>(name, composer, queue);
        }
    }

    /**
     * Builds a Conduit with a custom transformation function.
     *
     * <p>This is a convenience method for applying a transformation to
     * the conduit after creation.
     *
     * @param transformation function to apply to the built conduit
     * @return the transformed conduit
     */
    public Conduit<P, E> buildWith(Function<Conduit<P, E>, Conduit<P, E>> transformation) {
        return transformation.apply(build());
    }
}
