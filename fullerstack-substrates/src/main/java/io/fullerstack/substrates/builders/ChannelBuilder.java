package io.fullerstack.substrates.builders;

import io.fullerstack.substrates.channel.ChannelImpl;
import io.fullerstack.substrates.functional.Suppliers;
import io.humainary.substrates.api.Substrates.*;
import lombok.Builder;
import lombok.NonNull;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Functional builder for creating Channel instances.
 *
 * <p>Provides a fluent API for constructing Channels with customizable behavior
 * through function injection.
 *
 * <h3>Example Usage:</h3>
 * <pre>{@code
 * // Simple channel creation
 * Channel<MonitorSignal> channel = ChannelBuilder.<MonitorSignal>builder()
 *     .name(Name.name("broker.1.heap"))
 *     .queue(circuitQueue)
 *     .source(source)
 *     .build();
 *
 * // Channel with custom pipe factory
 * Channel<MonitorSignal> channel = ChannelBuilder.<MonitorSignal>builder()
 *     .name(Name.name("broker.1"))
 *     .queue(circuitQueue)
 *     .source(source)
 *     .pipeFactory((subject, queue) -> createCustomPipe(subject, queue))
 *     .build();
 *
 * // Channel with lazy pipe creation
 * Channel<MonitorSignal> channel = ChannelBuilder.<MonitorSignal>builder()
 *     .name(Name.name("broker.1"))
 *     .queue(circuitQueue)
 *     .source(source)
 *     .lazy(true)
 *     .build();
 * }</pre>
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class ChannelBuilder<E> {

    private final Name name;
    private final Queue queue;
    private final Source<E> source;
    private final Sequencer<Segment<E>> sequencer;
    private final boolean lazy;

    @lombok.Builder(builderMethodName = "builder", toBuilder = true)
    private ChannelBuilder(
        Name name,
        Queue queue,
        Source<E> source,
        Sequencer<Segment<E>> sequencer,
        Boolean lazy
    ) {
        this.name = java.util.Objects.requireNonNull(name, "name cannot be null");
        this.queue = java.util.Objects.requireNonNull(queue, "queue cannot be null");
        this.source = java.util.Objects.requireNonNull(source, "source cannot be null");
        this.sequencer = sequencer;
        this.lazy = lazy != null ? lazy : false;
    }

    /**
     * Builds a Channel instance with the configured parameters.
     *
     * @return a new Channel instance
     */
    public Channel<E> build() {
        // Use existing ChannelImpl as the underlying implementation
        return new ChannelImpl<>(name, queue, source, sequencer);
    }

    /**
     * Builds a Channel with lazy pipe creation.
     *
     * <p>The pipe is not created until the first call to {@code pipe()}.
     *
     * @return a new Channel with lazy semantics
     */
    public Channel<E> buildLazy() {
        Channel<E> delegate = build();
        Supplier<Pipe<E>> lazyPipe = Suppliers.memoized(delegate::pipe);

        return new Channel<E>() {
            @Override
            public Subject subject() {
                return delegate.subject();
            }

            @Override
            public Pipe<E> pipe() {
                return lazyPipe.get();
            }

            @Override
            public Pipe<E> pipe(Sequencer<? super Segment<E>> sequencer) {
                return delegate.pipe(sequencer);
            }
        };
    }

    /**
     * Builds a Channel with a custom transformation function.
     *
     * <p>This is a convenience method for applying a transformation to
     * the channel after creation.
     *
     * @param transformation function to apply to the built channel
     * @return the transformed channel
     */
    public Channel<E> buildWith(Function<Channel<E>, Channel<E>> transformation) {
        return transformation.apply(build());
    }

}
