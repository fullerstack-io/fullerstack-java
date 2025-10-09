package io.fullerstack.substrates.pipe;

import io.humainary.substrates.api.Substrates.*;
import io.fullerstack.substrates.channel.ChannelImpl;

/**
 * Implementation of Substrates.Pipe interface.
 *
 * <p>A Pipe is a simple wrapper around a Channel that provides typed emission.
 * It's the standard percept type returned by conduits when using Composer.pipe().
 *
 * @param <E> the emission type (e.g., MonitorSignal, ServiceSignal)
 */
public class PipeImpl<E> implements Pipe<E> {

    private final ChannelImpl<E> channel;

    public PipeImpl(Channel<E> channel) {
        // Cast to ChannelImpl to access emit method
        if (channel instanceof ChannelImpl) {
            this.channel = (ChannelImpl<E>) channel;
        } else {
            throw new IllegalArgumentException("PipeImpl requires ChannelImpl instance");
        }
    }

    @Override
    public void emit(E value) {
        channel.emit(value);
    }
}
